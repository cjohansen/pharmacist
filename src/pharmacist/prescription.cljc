(ns pharmacist.prescription
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [clojure.string :as str]
            [pharmacist.data-source :as data-source]
            [pharmacist.result :as result]
            [clojure.set :as set]))

(defn- err [message]
  (throw (#?(:cljs js/Error.
             :clj Exception.)
          message)))

(defn- now []
  #?(:cljs (.getTime (js/Date.))
     :clj (.toEpochMilli (java.time.Instant/now))))

(defn- get-dep [path sources param]
  (when (::data-source/dep (meta param))
    (if (vector? param)
      (first param)
      (err (str path " expected to be a dependency due to :pharmacist.data-source/dep metadata, but it is not a vector")))))

(defn- resolve-deps-1 [path sources {:keys [pharmacist.data-source/params]}]
  (if-let [dep (get-dep (str path " parameters") sources params)]
    #{dep}
    (loop [res []
           ks (keys params)]
      (if-let [k (first ks)]
        (recur
         (if-let [dep (get-dep (str path " parameter " k) sources (params k))]
           (conj res dep)
           (set res))
         (rest ks))
        (set res)))))

(defn resolve-deps [sources]
  (let [source-keys (set (keys sources))]
    (->> sources
         (map (fn [[k v]] [k (assoc v ::data-source/deps (resolve-deps-1 k source-keys v))]))
         (into {}))))

(defn find-cyclic-dep [sources item visited]
  (if (nil? item)
    false
    (loop [[dep & deps] (:data-source/deps item)]
      (if (visited dep)
        [dep]
        (let [cyclic-key (find-cyclic-dep sources (sources dep) (conj visited dep))]
          (cond
            cyclic-key (concat [dep] cyclic-key)
            (seq deps) (recur (rest deps))
            :default nil))))))

(defn- inflect [xs w & [singular multiple]]
  (if (< 1 (count xs))
    (str w (or multiple "s"))
    (str w singular)))

(defn deps-valid? [sources & [params]]
  (let [source-keys (set (keys sources))
        param-keys (set (keys params))
        ks (set/union source-keys param-keys)
        overlap (set/intersection source-keys param-keys)]
    (when-not (empty? overlap)
      (err (str (inflect overlap "Parameter") " " (str/join " " overlap) " shadow "
                (inflect overlap "data source") " with same name")))
    (loop [[[k sdef] & srcs] sources]
      (if-let [path (find-cyclic-dep sources sdef #{k})]
        (err (str k " has cyclic dependency " (str/join " -> " path)))
        (let [non-existing (filter #(not (ks %)) (::data-source/deps sdef))]
          (if (seq non-existing)
            (err (str k " has missing "
                      (inflect non-existing "dependenc" "y" "ies")
                      ": " (str/join ", " non-existing)))
            (cond
              (seq srcs) (recur srcs)
              :default true)))))))

(defn- satisfied? [available [_ {:keys [pharmacist.data-source/deps] :as a}]]
  (every? available deps))

(defn partition-fetches [sources params]
  (loop [sources sources
         fetch-order []]
    (if-not (seq sources)
      fetch-order
      (let [predicate (partial satisfied? (set (apply concat (keys params) fetch-order)))]
        (recur
         (->> sources (remove predicate) (into {}))
         (conj fetch-order (set (map first (filter predicate sources)))))))))

(defn- is-dep? [data v]
  (and (vector? v) (get data (first v))))

(defn provide-deps [data source]
  (update source ::data-source/params
          (fn [params]
            (if (is-dep? data params)
              (get-in data params)
              (->> params
                   (map (fn [[k v]]
                          (if (and (vector? v) (get data (first v)))
                            [k (get-in data v)]
                            [k v])))
                   (into {}))))))

(defn- prepare-result [path source result]
  {:path (if (coll? path) path [path])
   :source source
   :result (assoc result ::result/attempts 1)})

(defn- fetch-data-sync [path source]
  (prepare-result path source (data-source/fetch-sync source)))

(defn- fetch-data [path source]
  (a/go (prepare-result path source (a/<! (data-source/fetch source)))))

(defn- add-results [m results {:keys [cache-put]}]
  (loop [[{:keys [path source result]} & rest] (filter #(-> % :result ::result/success?) results)
         m m]
    (when (and (fn? cache-put) result)
      (cache-put path source (-> result
                                 (dissoc ::result/attempts)
                                 (assoc :pharmacist.cache/cached-at (now)))))
    (if path
      (recur rest (assoc-in m path (::result/data result)))
      m)))

(defn- prep-fill [prescription {:keys [params]}]
  (let [sources (resolve-deps prescription)]
    (when (deps-valid? sources params)
      {:batches (partition-fetches sources params)
       :sources sources
       :params (or params {})})))

(defn- prep-cached [path source cached]
  {:path path :source source :result (assoc cached ::result/attempts 0)})

(defn- get-cached [cache-get path source async?]
  (when-let [cached (and (fn? cache-get) (cache-get [path] source))]
    (let [res (prep-cached path source cached)]
      (if async? (a/go res) res))))

(defn- process-batch [sources batch res {:keys [async? cache-get]}]
  (->> (select-keys sources batch)
       (filter (partial satisfied? res))
       (map (fn [[path source]]
              (or (get-cached cache-get [path] source async?)
                  ((if async? fetch-data fetch-data-sync) path (provide-deps res source)))))))

(defn- unused [sources attempted]
  (set/difference
   (set (keys sources))
   (set (map #(-> % :path first) attempted))))

(defn- prepare-combined-result [sources attempted-sources res]
  {::result/success? (every? true? (map #(-> % :result ::result/success?) attempted-sources))
   ::result/data res
   ::result/sources (concat attempted-sources
                            (->> (select-keys sources (unused sources attempted-sources))
                                 (map (fn [[path source]]
                                        {:path [path]
                                         :source source
                                         :result {::result/success? false
                                                  ::result/attempts 0}}))))})

(defn- realize-results [ports ch]
  (let [port-count (count ports)]
    (a/go-loop [res []
                completed 0]
      (let [[val port] (a/alts! ports)]
        (cond
          (= completed port-count) res
          (nil? val) (recur res (inc completed))
          :default (let [{:keys [path source result]} val]
                     (a/put! ch val)
                     (recur (if (result/success? result)
                              (conj res val)
                              res)
                            completed)))))))

(defn fill-sync [prescription & [{:keys [params] :as opt}]]
  (let [{:keys [batches sources params]} (prep-fill prescription opt)]
    (loop [[batch & batches] batches
           attempted-sources []
           res params]
      (if batch
        (let [results (process-batch sources batch res (assoc opt :async? false))]
          (recur batches (concat attempted-sources results) (add-results res results opt)))
        (prepare-combined-result sources attempted-sources res)))))

(defn- fill-1 [{:keys [batches sources]} {:keys [params] :as opt}]
  (let [ch (a/chan)]
    (a/go
      (loop [[batch & batches] batches
             attempted-sources []
             res params]
        (when batch
          (let [ports (process-batch sources batch res (assoc opt :async? true))
                results (a/<! (realize-results ports ch))]
            (recur batches (concat attempted-sources results) (add-results res results opt)))))
      (a/close! ch))
    ch))

(defn fill [prescription & [opt]]
  (fill-1 (prep-fill prescription opt) opt))

(defn fill-collect [prescription & opt]
  (let [{:keys [sources params] :as prepped} (prep-fill prescription opt)
        in-ch (fill-1 prepped)]
    (a/go-loop [attempted-sources []
                res params]
      (if-let [result (a/<! in-ch)]
        (recur (conj attempted-sources result) (add-results res [result]))
        (prepare-combined-result sources attempted-sources res)))))
