(ns pharmacist.prescription
  (:require [pharmacist.data-source :as data-source]
            [pharmacist.result :as result]
            #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [pharmacist.schema :as schema]))

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

(defn resolve-deps [prescription & [ks]]
  (loop [res {}
         [key & ks] (or ks (keys prescription))]
    (cond
      (nil? key) res

      (contains? res key) (recur res ks)

      (contains? prescription key)
      (let [source (get prescription key)
            deps (resolve-deps-1 (::data-source/params source) key)]
        (recur (assoc res key (assoc source ::data-source/deps deps))
               (set (concat ks deps))))

      :default (recur res ks))))

(defn- dep-path [[source & path]]
  (concat [source] [::result/data] path))

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
  (every? #(get-in loaded [% ::result/success?]) deps))

(defn- retryable? [{:keys [source result]}]
  (or (::result/retrying? result)
      (and (not (result/success? result))
           (get result ::result/retryable? true)
           (<= (::result/attempts result) (get source ::data-source/retries 0)))))

(defn- prep-source [source]
  (dissoc source ::data-source/original-params ::result/attempts ::result/refresh ::data-source/refreshing?))

(defn- prep-result [result source]
  (if (result/success? result)
    (update result ::result/data #(schema/coerce-data source %))
    (assoc result ::result/retrying? (retryable? {:source source :result result}))))

(defn- fetch [{:keys [cache-put]} path source]
  (let [attempts (inc (get source ::result/attempts 0))]
    (a/go
      (let [source (assoc source ::result/attempts attempts)
            result {:source source
                    :path path
                    :result (-> (data-source/fetch source)
                                a/<!
                                (assoc ::result/attempts attempts)
                                (prep-result source))}]
        (when (and (ifn? cache-put) (result/success? (:result result)))
          (cache-put (->path path) (:source result) (assoc (:result result)
                                                           :pharmacist.cache/cached-at (now))))
        result))))

(defn- fetch-satisfied-sources [opt loaded sources]
  (->> sources
       (filter (partial satisfied? loaded))
       (map (fn [[path source]] (fetch opt path source)))))

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
          (let [clean (update val :source prep-source)]
            (a/>! ch clean)
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
  (remove (set (keys loaded)) ks))

(defn- eligible-nested [{:keys [walk-nested-prescriptions?]} results]
  (->> results
       (mapcat (fn [{:keys [path result source]}]
                 (let [include (or (::data-source/nested-prescriptions source) #{})]
                   (->> result
                        ::result/prescriptions
                        (filter #(or walk-nested-prescriptions? (include (::data-source/id (second %)))))
                        (map (fn [[k v]] [(concat (->path path) (->path k)) v]))))))
       (into {})))

(defn- restore-refreshes [refreshes prescription]
  (reduce
   (fn [prescription refresh]
     (update prescription refresh #(assoc %
                                          ::data-source/params (::data-source/original-params %)
                                          ::data-source/refreshing? true)))
   prescription
   refreshes))

(defn- update-prescription [prescription nested results retryable refresh]
  (->> (map (fn [{:keys [path source]}] [path source]) results)
       (into {})
       (apply merge prescription nested retryable)
       (restore-refreshes refresh)))

(defn- update-results [results batch-results refresh]
  (apply
   dissoc
   (->> batch-results
        (map (fn [{:keys [path result]}] [path result]))
        (into {})
        (merge results))
   refresh))

(defn- load-cached [{:keys [cache-get]} resolved ks]
  (when (ifn? cache-get)
    (->> ks
         (remove #(let [{::data-source/keys [params refreshing?] :as source} (get resolved %)]
                    (or (nil? source) refreshing? (dep? params))))
         (map #(vector % (get resolved %)))
         (remove (fn [[k {::data-source/keys [params] :as source}]]
                   (let [pks (data-source/cache-deps source)]
                     (seq (filter #(dep? %) (vals (select-keys params pks)))))))
         (map (fn [[k source]]
                (when-let [result (cache-get (->path k) source)]
                  {:path k
                   :source (prep-source source)
                   :result (assoc result
                                  ::result/attempts 0
                                  ::result/cached? true)})))
         (filter identity))))

(defn- process-batch-results [opt loaded prescription ks results retryable]
  (when (or (seq results) (seq retryable))
    (let [nested (eligible-nested opt results)
          refresh (mapcat ::result/refresh (vals retryable))
          loaded (update-results loaded results refresh)]
      {:loaded loaded
       :prescription (update-prescription prescription nested results retryable refresh)
       :ks (set (pending loaded (concat ks (keys nested) refresh)))})))

(defn- try-cache [opt ch result loaded prescription ks]
  (let [results (load-cached opt prescription ks)]
    (a/<!!
     (a/go
       (doseq [res results]
         (a/>! ch res)
         (swap! result assoc (:path res) (:result res)))
       (process-batch-results opt loaded prescription ks results nil)))))

(defn- deps-summary [prescription]
  (->> prescription
       (map (fn [[k {::data-source/keys [deps]}]] [k deps]))
       (filter second)
       (map first)
       (into #{})))

(defn- expand-selection [loaded prescription ks]
  (let [resolved (resolve-deps prescription (pending loaded ks))
        new-ks (pending loaded (keys resolved))]
    (when (or (seq (remove (deps-summary prescription) (deps-summary resolved)))
              (not= (into #{} ks) (into #{} new-ks)))
      {:loaded loaded
       :prescription (merge prescription resolved)
       :ks new-ks})))

(defn- fetch-sources [opt ch result loaded prescription ks]
  (a/<!!
   (a/go
     (let [[results retryable]
           (->> (select-keys prescription ks)
                (fetch-satisfied-sources opt loaded)
                (realize-results ch result)
                a/<!)]
       (process-batch-results opt loaded prescription ks results retryable)))))

(defn fill [prescription & [opt]]
  (let [result (atom (mapvals result/success (:params opt)))]
    (fn [ks]
      (let [ch (a/chan)]
        (a/go
          (doseq [[path result] (select-keys @result ks)]
            (a/>! ch {:source (prescription path)
                      :path path
                      :result result}))
          (loop [loaded @result
                 prescription prescription
                 ks ks]
            (let [prescription (mapvals #(provide-deps loaded %) prescription)]
              (if-let [res (or (try-cache opt ch result loaded prescription ks)
                               (expand-selection loaded prescription ks)
                               (fetch-sources opt ch result loaded prescription ks))]
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

(defn collect [port]
  (a/go-loop [res {}
              sources []]
    (let [{:keys [path result] :as message} (a/<! port)]
      (cond
        (nil? path) {::result/success? (success? sources)
                     ::result/sources sources
                     ::result/data res}

        (result/success? result)
        (recur (assoc-in res (->path path) (::result/data result))
               (conj sources message))

        :default (recur res (conj sources message))))))
