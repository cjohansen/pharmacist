(ns pharmacist.prescription
  (:require [pharmacist.data-source :as data-source]
            [pharmacist.result :as result]
            #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [pharmacist.schema :as schema]
            [clojure.string :as str]
            [clojure.set :as set]))

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
    (loop [res #{}
           ks (keys params)]
      (if-let [k (first ks)]
        (recur
         (if-let [dep (get-dep (str path " parameter " k) (params k))]
           (conj res dep)
           res)
         (rest ks))
        res))))

(declare resolve-deps-with)

(defn- add-collection-deps [prescription res k f]
  (loop [res res
         acquired #{}
         [[coll deps] & coll-deps]
         (->> prescription
              (filter (fn [[_ source]] (and (::data-source/in-coll source) (not (contains? source k)))))
              (reduce (fn [deps [p source]]
                        (update deps (::data-source/in-coll source) #(conj (or % #{}) p))) {}))]
    (if (nil? coll)
      (merge res (when (seq acquired)
                   (resolve-deps-with (merge prescription res) acquired k f)))
      (recur
       (merge res (select-keys (update-in prescription [coll k] set/union deps) [coll]))
       (set/union acquired (when (some #(contains? (k %) coll) (vals res))
                             deps))
       coll-deps))))

(defn- resolve-deps-with [prescription ks k f]
  (loop [res {}
         [key & ks] (or ks (keys prescription))]
    (cond
      (nil? key) (add-collection-deps prescription res k f)

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
       (map first)
       (into #{})))

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

(defn- result-error [message reason]
  {:message (str "Fetch did not return a pharmacist result: " message)
   :type :pharmacist.error/invalid-result
   :reason (keyword "pharmacist.error" (name reason))})

(def ^:private result-not-map (result-error "not a map" :result-not-map))
(def ^:private result-nil (result-error "nil" :result-nil))
(def ^:private result-not-result (result-error "no :pharmacist.result/success? or :pharmacist.result/data" :not-pharmacist-result))

(defn- prep-result [source result]
  (let [result (cond
                 (nil? result) (result/error result result-nil)
                 (not (map? result)) (result/error result result-not-map)
                 (and (not (contains? result ::result/success?))
                      (not (contains? result ::result/data))) (result/error result result-not-result)
                 :default result)]
    (let [result (assoc result ::result/attempts (::result/attempts source))]
      (if (result/success? result)
        (if (::data-source/schema source)
          (-> result
              (assoc ::result/raw-data (::result/data result))
              (update ::result/data #(schema/coerce-data source %)))
          result)
        (assoc result ::result/retrying? (retryable? {:source source :result result}))))))

(defn- safe-take [fetch-port timeout-ms]
  (a/go
    (try
      (let [ports (if timeout-ms [fetch-port (a/timeout timeout-ms)] [fetch-port])
            [result port] (a/alts! ports)]
        (if (= port fetch-port)
          result
          (result/failure nil {::result/timeout-after timeout-ms})))
      (catch #?(:clj Throwable :cljs :default) e
        (result/error
         fetch-port
         {:message (str "Fetch return value is not a core.async read port: " fetch-port)
          :type :pharmacist.error/fetch-no-chan
          :upstream e})))))

(defn- get-timeout [{::data-source/keys [timeout]} default-timeout]
  (let [timeout (or timeout default-timeout 5000)]
    (when (and (number? timeout) (< 0 timeout))
      timeout)))

(defn- fetch [{:keys [cache timeout]} path source]
  (a/go
    (let [attempts (inc (get source ::result/attempts 0))
          source (assoc source ::result/attempts attempts)
          message {:source source
                   :path path
                   :result (prep-result source (a/<! (safe-take (data-source/safe-fetch source)
                                                                (get-timeout source timeout))))}]
      (when (and (ifn? (:put cache)) (result/success? (:result message)))
        ((:put cache) (->path path) (:source message) (assoc (:result message)
                                                             :pharmacist.cache/cached-at (now))))
      message)))

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
          (let [val (if (and (::data-source/coll-of (:source val))
                             (not (empty? (::result/data (:result val)))))
                      (assoc-in val [:result ::result/partial?] true)
                      val)]
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

(defn- prep-coll-item [prescription coll-path path source data]
  {:path path
   :source (-> prescription
               (get (::data-source/coll-of source))
               (dissoc ::data-source/original-params ::data-source/deps ::data-source/cache-deps)
               (update ::data-source/params #(merge % data))
               (assoc ::data-source/in-coll coll-path))})

(defn- map-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map (fn [[k v]]
              (prep-coll-item prescription path [path k] source v)))))

(defn- coll-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map #(prep-coll-item prescription path path source %))))

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
              [[path idx] source]))
           (into {})))))

(defn- restore-refreshes [refreshes prescription]
  (reduce #(update %1 %2 assoc
                   ::data-source/params (get-in %1 [%2 ::data-source/original-params])
                   ::data-source/refreshing? true)
          prescription refreshes))

(defn- update-prescription [prescription nested results retryable refresh]
  (->> (map (fn [{:keys [path source]}] [path source]) results)
       (into {})
       (merge prescription nested (mapvals #(dissoc % ::data-source/deps ::data-source/cache-deps) retryable))
       (restore-refreshes refresh)))

(defn- merge-collection-results [results loaded]
  (->> results
       (filter #(-> % :source ::data-source/in-coll))
       (reduce
        (fn [loaded {:keys [path source result]}]
          (let [coll (::data-source/in-coll source)
                path (concat [coll ::result/data] (rest path))]
            (update-in loaded path merge (::result/data result))))
        loaded)))

(defn- update-results [results batch-results refresh]
  (apply
   dissoc
   (->> batch-results
        (map (fn [{:keys [path source result]}] [path result]))
        (into {})
        (merge results)
        (merge-collection-results batch-results))
   refresh))

(defn- complete-collections [opt ch result loaded prescription ks]
  (let [incomplete (filter #(-> % second ::result/partial?) loaded)
        completed (->> incomplete
                       (filter (fn [[p source]]
                                 (let [desired (count (::result/data (loaded p)))
                                       completed (->> loaded
                                                      (filter #(= p (get-in prescription [(first %) ::data-source/in-coll])))
                                                      count)]
                                   (= desired completed))))
                       (map first))]
    (when (seq completed)
      (a/go
        (let [loaded (reduce #(update %1 %2 dissoc ::result/partial?) loaded completed)]
          (doseq [k completed]
            (a/>! ch {:path k
                      :source (prep-source (prescription k))
                      :result (loaded k)}))
          {:loaded loaded
           :prescription prescription
           :ks ks})))))

(defn- process-batch-results [opt loaded prescription ks results retryable]
  (let [nested (eligible-nested prescription results)
        refresh (mapcat ::result/refresh (vals retryable))
        loaded (update-results loaded results refresh)]
    {:loaded loaded
     :prescription (update-prescription prescription nested results retryable refresh)
     :ks (set (pending loaded (concat ks (keys nested) refresh)))}))

(defn- load-cached [cache resolved ks]
  (when-let [cache-get (and (ifn? (:get cache)) (:get cache))]
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
  (when-let [results (seq (load-cached (:cache opt) prescription ks))]
    (a/go
      (doseq [res results]
        (a/>! ch (update res :source prep-source))
        (swap! result assoc (:path res) (:result res)))
      (process-batch-results opt loaded prescription ks results nil))))

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
    (when-let [res (or (expand-cache-selection loaded prescription pending-keys)
                       (expand-cache-deps loaded prescription pending-keys)
                       (expand-cache-deps-selection loaded prescription pending-keys)
                       (expand-deps-selection loaded prescription pending-keys)
                       (expand-deps loaded prescription pending-keys))]
      (a/go res))))

(defn- remove-partials [results]
  (->> results
       (remove #(::result/partial? (second %)))
       (into {})))

(defn- fetch-sources [opt ch result loaded prescription ks]
  (when-let [reqs (->> (select-keys prescription ks)
                       (filter (partial satisfied? (remove-partials loaded)))
                       (map (fn [[path source]]
                              (fetch opt path source)))
                       seq)]
    (a/go
      (let [[results retryable] (a/<! (realize-results ch result reqs))]
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
            (let [prescription (mapvals #(provide-deps (remove-partials loaded) %) prescription)]
              (if-let [res (or (complete-collections opt ch result loaded prescription ks)
                               (try-cache opt ch result loaded prescription ks)
                               (fetch-sources opt ch result loaded prescription ks)
                               (expand-selection loaded prescription ks))]
                (let [{:keys [loaded prescription ks]} (a/<! res)]
                  (recur loaded prescription ks))
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

(defn- unseq [data]
  (if (seq? data)
    (into [] data)
    data))

(defn merge-results [results]
  (->> results
       (filter #(get-in % [:result ::result/success?] true))
       (map #(update % :path ->path))
       (sort-by #(-> % :path count))
       (reduce (fn [res {:keys [path result]}]
                 (assoc-in res path (unseq (::result/data result)))) {})))

(defn collect [port]
  (a/go-loop [results []]
    (let [result (a/<! port)]
      (cond
        (nil? result) {::result/success? (success? results)
                       ::result/sources results
                       ::result/data (merge-results results)}

        :default (recur (conj results result))))))
