(ns pharmacist.prescription
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [clojure.string :as str]
            [pharmacist.data-source :as data-source]
            [pharmacist.result :as result]
            [pharmacist.schema :as schema]
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
  (every? (or available #{}) deps))

(defn batches [sources params]
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

(defn- retryable? [source result]
  (and
   (get result ::result/retryable? true)
   (if-let [retryable? (::data-source/retryable? source)]
     (retryable? result)
     true)
   (number? (::data-source/retries source))
   (<= (::result/attempts result) (::data-source/retries source))))

(defn- prefix-paths [prescriptions prefix]
  (map (fn [[path prescription]] [(concat prefix path) prescription]) prescriptions))

(defn- prepare-result [path source result]
  (let [path (if (coll? path) path [path])]
    {:path path
     :source source
     :result (if (seq (::result/prescriptions result))
               (-> result
                  (update ::result/prescriptions prefix-paths path))
               result)}))

(defn- fetch-data-sync [path source]
  (loop [attempts 1]
    (let [result (assoc (data-source/fetch-sync source) ::result/attempts attempts)]
      (if (and (not (result/success? result))
               (retryable? source result))
        (recur (inc attempts))
        (prepare-result path source result)))))

(defn- fetch-data [path source]
  (a/go
    (loop [attempts 1]
      (let [result (assoc (a/<! (data-source/fetch source)) ::result/attempts attempts)]
        (if (and (not (result/success? result))
                 (retryable? source result))
          (recur (inc attempts))
          (prepare-result path source result))))))

(defn- add-results [m results {:keys [cache-put]}]
  (loop [[{:keys [path source result]} & rest] (filter #(-> % :result ::result/success?) results)
         m m]
    (when (and (fn? cache-put) result (not (:pharmacist.cache/cached-at result)))
      (cache-put path source (-> result
                                 (dissoc ::result/attempts)
                                 (assoc :pharmacist.cache/cached-at (now)))))
    (if path
      (recur rest (assoc-in m path (schema/coerce-data
                                    (::data-source/id source)
                                    (::result/data result))))
      m)))

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

(defn- unused-sources [sources attempted]
  (let [unused (set/difference
                (set (keys sources))
                (set (map #(-> % :path first) attempted)))]
    (->> (select-keys sources unused)
         (map (fn [[path source]]
                (merge
                 {:path (if (coll? path) path [path])
                  :source source}
                 (when (get source ::data-source/fetch? true)
                   {:result {::result/success? false
                             ::result/attempts 0}})))))))

(defn- prepare-combined-result [sources attempted-sources res]
  {::result/success? (every? true? (map #(-> % :result ::result/success?) attempted-sources))
   ::result/data res
   ::result/sources (concat attempted-sources
                            (unused-sources sources attempted-sources))})

(defn- realize-results [ports ch]
  (a/go-loop [ports ports
              res []]
    (if (< 0 (count ports))
      (let [[val port] (a/alts! ports)]
        (cond
          (= 0 (count ports)) res
          (nil? val) (recur (remove #(= % port) ports) res)
          :default (let [{:keys [path source result]} val]
                     (recur (remove #(= % port) ports) (conj res val)))))
      res)))

(defn- prep-fill [prescription {:keys [params]}]
  (let [sources (resolve-deps prescription)]
    (when (deps-valid? sources params)
      {:batches (batches sources params)
       :sources sources
       :params (or params {})})))

(defn fill-sync [prescription & [{:keys [params] :as opt}]]
  (let [{:keys [batches sources params]} (prep-fill prescription opt)]
    (loop [[batch & batches] batches
           sources sources
           attempted-sources []
           res params]
      (if batch
        (let [batch-results (process-batch sources batch res (assoc opt :async? false))
              attempted-sources (concat attempted-sources batch-results)
              results (add-results res batch-results opt)
              nested-prescriptions (->> batch-results
                                        (mapcat #(-> % :result ::result/prescriptions))
                                        (into {}))]
          (if (seq nested-prescriptions)
            (let [updated-presc (-> dissoc
                                    (apply prescription (keys results))
                                    (merge (->> batch-results
                                                (mapcat #(-> % :result ::result/prescriptions))
                                                (map #(assoc-in % [1 ::data-source/fetch?] false))
                                                (into {}))))
                  nested (prep-fill updated-presc {:params results})]
              (recur
               batches ;;(:batches nested)
               (merge sources (select-keys (:sources nested) (keys updated-presc)))
               attempted-sources results))
            (recur batches sources attempted-sources results)))
        (prepare-combined-result sources attempted-sources res)))))

(defn fill [prescription & [opt]]
  (let [{:keys [batches sources params]} (prep-fill prescription opt)
        ch (a/chan)]
    (a/go
      (loop [[batch & batches] batches
             attempted-sources []
             res params]
        (if batch
          (let [ports (process-batch sources batch res (assoc opt :async? true))
                results (a/<! (realize-results ports ch))]
            (loop [[result & rest] results]
              (when result
                (a/>! ch result)
                (recur rest)))
            (recur batches (concat attempted-sources results) (add-results res results opt)))
          (let [unused (unused-sources sources attempted-sources)]
            (doseq [message (unused-sources sources attempted-sources)]
              (a/>! ch message)))))
      (a/close! ch))
    ch))

(defn collect [fill-ch]
  (a/go-loop [messages []
              res {}]
    (if-let [result (a/<! fill-ch)]
      (recur (conj messages result) (add-results res [result] {}))
      (prepare-combined-result {} messages res))))
