(ns pharmacist.prescription
  (:require [pharmacist.data-source :as data-source]
            [pharmacist.result :as result]
            #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [pharmacist.schema :as schema]
            [clojure.string :as str]))

(defn- mapvals [f m]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(defn- err [message]
  (throw (#?(:cljs js/Error.
             :clj Exception.)
          message)))

(defn- now []
  #?(:cljs (.getTime (js/Date.))
     :clj (.toEpochMilli (java.time.Instant/now))))

(defn- ->path [p]
  (if (coll? p)
    p
    [p]))

(defn- dep? [param]
  (::data-source/dep (meta param)))

(defn- get-dep [path param]
  (when (dep? param)
    (if (vector? param)
      (first param)
      (err (str path " expected to be a dependency due to :pharmacist.data-source/dep metadata, but it is not a vector")))))

(defn- resolve-deps-1 [params path]
  (if-let [dep (get-dep (str path " parameters") params)]
    #{dep}
    (loop [res []
           ks (keys params)]
      (if-let [k (first ks)]
        (recur
         (if-let [dep (get-dep (str path " parameter " k) (params k))]
           (conj res dep)
           (set res))
         (rest ks))
        (set res)))))

(defn- resolve-deps-with [prescription ks k f]
  (loop [res {}
         [key & ks] (or ks (keys prescription))]
    (cond
      (nil? key) res

      (contains? res key) (recur res ks)

      (contains? prescription key)
      (let [source (get prescription key)
            deps (f source key)]
        (recur (assoc res key (assoc source k deps))
               (set (concat ks deps))))

      :default (recur res ks))))

(defn resolve-deps [prescription & [ks]]
  (resolve-deps-with prescription ks ::data-source/deps #(resolve-deps-1 (::data-source/params %1) %2)))

(defn- cache-deps [{::data-source/keys [params] :as source}]
  (->> (data-source/cache-deps source)
       (select-keys params)
       vals
       (filter #(dep? %))
       (map first)))

(defn resolve-cache-deps [prescription ks]
  (resolve-deps-with prescription ks ::data-source/cache-deps (fn [source _] (cache-deps source))))

(defn- dep-path [[source & path]]
  (concat [source ::result/data] path))

(defn- provide-deps [results source]
  (cond-> source
    (not (contains? source ::data-source/original-params))
    (assoc ::data-source/original-params (::data-source/params source))

    :always
    (update ::data-source/params
            (fn [params]
              (if (dep? params)
                (get-in results (dep-path params) params)
                (mapvals #(if (dep? %) (get-in results (dep-path %) %) %) params))))))

(defn- satisfied? [loaded [_ {::data-source/keys [deps] :as s}]]
  (and (not (nil? deps))
       (every? #(get-in loaded [% ::result/success?]) deps)))

(defn- retryable? [{:keys [source result]}]
  (or (::result/retrying? result)
      (and (not (result/success? result))
           (get result ::result/retryable? true)
           (<= (::result/attempts result) (get source ::data-source/retries 0)))))

(defn- prep-source [source]
  (cond-> source
    (empty? (::data-source/deps source)) (assoc ::data-source/deps (resolve-deps-1 (::data-source/original-params source) nil))
    :always (dissoc ::data-source/original-params ::data-source/refreshing? ::data-source/cache-deps
                    ::data-source/fn ::data-source/async-fn ::data-source/cache-params
                    ::result/attempts ::result/refresh)))

(defn- prep-result [result source]
  (if (result/success? result)
    (update result ::result/data #(schema/coerce-data source %))
    (assoc result ::result/retrying? (retryable? {:source source :result result}))))

(defn- fetch [{:keys [cache-put]} path source]
  (a/go
    (let [attempts (inc (get source ::result/attempts 0))
          source (assoc source ::result/attempts attempts)
          result {:source source
                  :path path
                  :result (-> (a/<! (data-source/fetch source))
                              (assoc ::result/attempts attempts)
                              (prep-result source))}]
      (when (and (ifn? cache-put) (result/success? (:result result)))
        (cache-put (->path path) (:source result) (assoc (:result result)
                                                         :pharmacist.cache/cached-at (now))))
      result)))

(defn- params->deps [{::data-source/keys [original-params]} params]
  (mapcat (fn [param]
            (if (dep? original-params)
              (when (= param ::data-source/params)
                (take 1 original-params))
              (->> original-params
                   (filter #(= param (first %)))
                   (map #(-> % second first)))))
          params))

(defn- realize-results [ch result ports]
  (a/go-loop [ports ports
              res []]
    (if (< 0 (count ports))
      (let [[val port] (a/alts! ports)]
        (if (nil? val)
          (recur (remove #(= % port) ports) res)
          (do
            (a/>! ch (update val :source prep-source))
            (when (not (retryable? val))
              (swap! result assoc (:path val) (:result val)))
            (recur (remove #(= % port) ports) (conj res val)))))
      [(remove retryable? res)
       (->> (filter retryable? res)
            (map (fn [{:keys [path source result]}]
                   [path (-> source
                             (assoc ::data-source/params (::data-source/original-params source))
                             (assoc ::result/refresh (params->deps source (::result/refresh result))))]))
            (into {}))])))

(defn- pending [loaded ks]
  (into #{} (remove (set (keys loaded)) ks)))

(defn- ensure-id [source]
  (assoc source ::data-source/id (data-source/id source)))

(defn- prep-coll-item [prescription path source data]
  {:path path
   :source (-> prescription
               (get (::data-source/coll-of source))
               (dissoc ::data-source/original-params ::data-source/deps ::data-source/cache-deps)
               (update ::data-source/params #(merge % data)))})

(defn- map-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map (fn [[k v]]
              (prep-coll-item prescription (concat (->path path) (->path k)) source v)))))

(defn- coll-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map #(prep-coll-item prescription path source %))))

(defn- eligible-nested [prescription results]
  (let [eligible (filter #(-> % :source ::data-source/coll-of) results)]
    (if (map? (::result/data (:result (first eligible))))
      (->> eligible
           (mapcat #(map-items prescription %))
           (map (fn [{:keys [path source]}] [path source]))
           (into {}))
      (->> eligible
           (mapcat #(coll-items prescription %))
           (map-indexed
            (fn [idx {:keys [path source]}]
              [(concat (->path path) [idx]) source]))
           (into {})))))

(defn- restore-refreshes [refreshes prescription]
  (reduce #(update %1 %2 assoc
                   ::data-source/params (get-in %1 [%2 ::data-source/original-params])
                   ::data-source/refreshing? true)
          prescription refreshes))

(defn- update-prescription [prescription nested results retryable refresh]
  (->> (map (fn [{:keys [path source]}] [path source]) results)
       (into {})
       (apply merge prescription nested (mapvals #(dissoc % ::data-source/deps ::data-source/cache-deps) retryable))
       (restore-refreshes refresh)))

(defn- update-results [results batch-results refresh]
  (apply
   dissoc
   (->> batch-results
        (map (fn [{:keys [path result]}] [path result]))
        (into {})
        (merge results))
   refresh))

(defn- process-batch-results [opt loaded prescription ks results retryable]
  (when (or (seq results) (seq retryable))
    (let [nested (eligible-nested prescription results)
          refresh (mapcat ::result/refresh (vals retryable))
          loaded (update-results loaded results refresh)]
      {:loaded loaded
       :prescription (update-prescription prescription nested results retryable refresh)
       :ks (set (pending loaded (concat ks (keys nested) refresh)))})))

(defn- load-cached [{:keys [cache-get]} resolved ks]
  (when (ifn? cache-get)
    (->> ks
         (remove #(let [{::data-source/keys [params refreshing?] :as source} (get resolved %)]
                    (or (nil? source) refreshing? (dep? params))))
         (map #(vector % (get resolved %)))
         (remove #(seq (cache-deps (second %))))
         (map (fn [[k source]]
                (when-let [result (cache-get (->path k) source)]
                  {:path k
                   :source source
                   :result (assoc result
                                  ::result/attempts 0
                                  ::result/cached? true)})))
         (filter identity))))

(defn- try-cache [opt ch result loaded prescription ks]
  (let [results (load-cached opt prescription ks)]
    (a/<!!
     (a/go
       (doseq [res results]
         (a/>! ch (update res :source prep-source))
         (swap! result assoc (:path res) (:result res)))
       (process-batch-results opt loaded prescription ks results nil)))))

(defn- expand-cache-deps [loaded prescription ks]
  (when (seq (remove ::data-source/cache-deps (vals (select-keys prescription ks))))
    {:loaded loaded
     :prescription (merge prescription (resolve-cache-deps prescription ks))
     :ks ks}))

(defn- keys-by [loaded prescription ks k & [target-ks]]
  (->> (select-keys prescription ks)
       vals
       (mapcat k)
       (into (or target-ks ks))
       (pending loaded)))

(defn- expand-keys [loaded prescription ks new-ks]
  (when (< (count ks) (count new-ks))
    {:loaded loaded :prescription prescription :ks new-ks}))

(defn- expand-cache-selection [loaded prescription ks]
  (expand-keys loaded prescription ks (keys-by loaded prescription ks ::data-source/cache-deps)))

(defn- expand-cache-deps-selection [loaded prescription ks]
  (let [dep-ks (->> (select-keys prescription ks)
                    vals
                    (mapcat ::data-source/cache-deps))]
    (expand-keys loaded prescription ks (keys-by loaded prescription dep-ks ::data-source/deps ks))))

(defn- expand-deps [loaded prescription ks]
  (when (seq (remove ::data-source/deps (vals (select-keys prescription ks))))
    {:loaded loaded
     :prescription (merge prescription (resolve-deps prescription ks))
     :ks ks}))

(defn- expand-deps-selection [loaded prescription ks]
  (expand-keys loaded prescription ks (keys-by loaded prescription ks ::data-source/deps)))

(defn- expand-selection [loaded prescription ks]
  (let [pending-keys (pending loaded ks)]
    (or (expand-cache-selection loaded prescription pending-keys)
        (expand-cache-deps loaded prescription pending-keys)
        (expand-cache-deps-selection loaded prescription pending-keys)
        (expand-deps-selection loaded prescription pending-keys)
        (expand-deps loaded prescription pending-keys))))

(defn- fetch-sources [opt ch result loaded prescription ks]
  (a/<!!
   (a/go
     (let [[results retryable]
           (->> (select-keys prescription ks)
                (filter (partial satisfied? loaded))
                (map (fn [[path source]] (fetch opt path source)))
                (realize-results ch result)
                a/<!)]
       (process-batch-results opt loaded prescription ks results retryable)))))

(defn fill [prescription & [opt]]
  (let [result (atom (mapvals result/success (:params opt)))
        prescription (mapvals ensure-id prescription)]
    (fn [ks]
      (let [ch (a/chan)]
        (a/go
          (doseq [[path result] (select-keys @result ks)]
            (a/>! ch {:source (prep-source (prescription path))
                      :path path
                      :result result}))
          (loop [loaded @result
                 prescription prescription
                 ks ks]
            (let [prescription (mapvals #(provide-deps loaded %) prescription)]
              (if-let [res (or (try-cache opt ch result loaded prescription ks)
                               (fetch-sources opt ch result loaded prescription ks)
                               (expand-selection loaded prescription ks))]
                (recur (:loaded res) (:prescription res) (:ks res))
                (let [resolved (resolve-deps prescription (pending loaded ks))]
                  (doseq [k (pending loaded (keys resolved))]
                    (a/>! ch {:source (prep-source (get resolved k))
                              :path k
                              :result {::result/success? false
                                       ::result/attempts 0}}))
                  (a/close! ch))))))
        ch))))

(defn select [filled ks]
  (filled ks))

(defn- success? [sources]
  (->> sources
       (map (fn [{:keys [path result]}]
              [path (::result/success? result)]))
       (into {})
       (map second)
       (reduce #(and %1 %2) true)))

(defn merge-results [results]
  (->> results
       (filter #(get-in % [:result ::result/success?] true))
       (map #(update % :path ->path))
       (sort-by #(-> % :path count))
       (reduce (fn [res {:keys [path result]}]
                 (assoc-in res path (::result/data result))) {})))

(defn collect [port]
  (a/go-loop [results []]
    (let [result (a/<! port)]
      (cond
        (nil? result) {::result/success? (success? results)
                       ::result/sources results
                       ::result/data (merge-results results)}

        :default (recur (conj results result))))))
