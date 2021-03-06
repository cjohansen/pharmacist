(ns pharmacist.prescription
  "A prescription is a map of `[path data-source]` that Pharmacist can fill.
  From this fulfilled prescription you can select all, or parts of the described
  data. You can consume/stream data as it becomes available, or combine
  everything into a single map of `[path result-data]`.

```clj
(require '[pharmacist.prescription :as p]
         '[pharmacist.data-source :as data-source]
         '[clojure.core.async :refer [<!!]])

(def prescription
  {::auth {::data-source/fn #'spotify-auth
           ::data-source/params ^::data-source/dep [:config]}

   ::playlists {::data-source/fn #'spotify-playlists
                ::data-source/params {:token ^::data-source/dep [::auth :access_token]}}})

(-> prescription
    (p/fill {:params {:config {:spotify-user \"...\"
                               :spotify-pass \"...\"}}})
    (p/select [::playlists])
    p/collect
    <!!
    :pharmacist.result/data)
```"
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

(defn- unresolved-collections [prescription k]
  (->> prescription
       (filter (fn [[_ source]] (and (::data-source/member-of source)
                                     (not (contains? source k)))))
       (reduce (fn [deps [p source]]
                 (update deps (::data-source/member-of source) #(conj (or % #{}) p))) {})))

(defn- add-collection-deps [prescription res k f]
  (loop [res res
         acquired #{}
         [[coll deps] & coll-deps] (unresolved-collections prescription k)]
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

(defn resolve-deps
  "Resolve dependencies in `prescription` by looking up any individual parameter
  (or full parameter map) that has the metadata `^:pharmacist.data-source/dep`.
  Returns a prescription where all sources have the
  `:pharmacist.data-source/deps` key added to them. It will contain a set of
  keywords, where the keywords are other sources they depend on. Does not
  require the keywords to exist in the input `prescription`, although if any are
  missing, [[fill]] will not be able to fetch everything.

  The optional keys in `ks` can be used to give the function focus. The
  resulting prescription will only include these keys, along with those keys'
  transitive dependencies, if any."
  [prescription & [ks]]
  (resolve-deps-with prescription ks ::data-source/deps #(resolve-deps-1 (::data-source/params %1) %2)))

(defn- cache-dep-params [{::data-source/keys [params] :as source}]
  (->> (data-source/cache-deps source)
       (select-keys params)
       vals
       (filter #(dep? %))
       (map first)
       (into #{})))

(defn resolve-cache-deps
  "Resolve cache dependencies. Like [[resolve-deps]], but only considers
  dependencies necessary to look sources up in cache. Refer to
  `:pharmacist.data-source/cache-deps` in [[pharmacist.data-source]] for more
  information on cache dependencies."
  [prescription ks]
  (resolve-deps-with prescription ks ::data-source/cache-deps (fn [source _] (data-source/cache-deps source))))

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
                    ::data-source/fn ::data-source/async-fn ::data-source/cache-params ::data-source/delay
                    ::data-source/template-path ::result/attempts ::result/refresh)))

(defn- result-error [message reason]
  {:message (str "Fetch did not return a pharmacist result: " message)
   :type :pharmacist.error/invalid-result
   :reason (keyword "pharmacist.error" (name reason))})

(def ^:private result-not-map (result-error "not a map" :result-not-map))
(def ^:private result-nil (result-error "nil" :result-nil))
(def ^:private result-not-result (result-error "no :pharmacist.result/success? or :pharmacist.result/data" :not-pharmacist-result))
(def ^:private result-not-unseqable (result-error "failed to realize lazy seq result data" :result-not-realizable))

(defn- retry-delay [{::data-source/keys [retry-delays] :as source} result]
  (or (::result/retry-delay result)
      (get (vec retry-delays) (dec (min (::result/attempts source) (count retry-delays))) 0)))

(defn- unseq [data]
  (if (seq? data)
    (into [] data)
    data))

(defn- prep-result [source result]
  (let [result (try
                 (cond
                   (nil? result) (result/error result result-nil)
                   (not (map? result)) (result/error result result-not-map)
                   (and (not (contains? result ::result/success?))
                        (not (contains? result ::result/data))) (result/error result result-not-result)
                   :default (if (::result/data result)
                              (update result ::result/data unseq)
                              result))
                 (catch #?(:clj Throwable :cljs :default) e
                   (result/error (::result/data result) (assoc result-not-unseqable :upstream e))))]
    (let [result (assoc result ::result/attempts (::result/attempts source))]
      (if (result/success? result)
        (if-let [conform (::data-source/conform source)]
          (-> result
              (update ::result/data #(conform source %))
              (assoc ::result/raw-data (::result/data result)))
          result)
        (let [retrying? (retryable? {:source source :result result})]
          (cond-> result
            retrying? (assoc ::result/retry-delay (retry-delay source result))
            :always (assoc ::result/retrying? retrying?)))))))

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
  (let [timeout (or timeout default-timeout)]
    (when (and (number? timeout) (< 0 timeout))
      timeout)))

(defn- partial? [{:keys [source result]}]
  (or (and (::data-source/coll-of source)
           (not (empty? (::result/data result))))
      (::data-source/begets source)))

(defn- cache-result [cache {:keys [path source result] :as message}]
  (when (and (ifn? (:put cache)) (result/success? result))
    ((:put cache) path source (assoc result :pharmacist.cache/cached-at (now)))))

(defn- fetch [{:keys [cache timeout]} path source]
  (a/go
    (when (< 0 (or (::data-source/delay source) 0))
      (a/<! (a/timeout (::data-source/delay source))))
    (let [attempts (inc (get source ::result/attempts 0))
          source (assoc source ::result/attempts attempts)
          start (now)
          result (a/<! (safe-take (data-source/safe-fetch source)
                                  (get-timeout source timeout)))
          message {:source source
                   :path path
                   :result (assoc
                            (prep-result source result)
                            ::result/elapsed-time (- (now) start))}]
      (when-not (partial? message)
        (cache-result cache message))
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
          (let [val (if (partial? val)
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
                             (assoc ::data-source/delay (::result/retry-delay result))
                             (assoc ::result/refresh (params->deps source (::result/refresh result))))]))
            (into {}))])))

(defn- pending [loaded ks]
  (into #{} (remove (set (keys loaded)) ks)))

(defn- ensure-id [source]
  (assoc source ::data-source/id (data-source/id source)))

(defn- prep-nested-source [source data]
  (-> source
      (dissoc ::data-source/original-params ::data-source/deps ::data-source/cache-deps)
      (update ::data-source/params merge data)))

(defn- prep-coll-item [prescription coll-path path source data]
  (let [coll (::data-source/coll-of source)]
    {:path path
     :source (-> (prep-nested-source (prescription coll) data)
                 (assoc ::data-source/member-of coll-path)
                 (assoc ::data-source/template-path coll))}))

(defn- map-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map (fn [[k v]]
              (prep-coll-item prescription path [path k] source v)))))

(defn- coll-items [prescription {:keys [path source result]}]
  (->> (::result/data result)
       (map #(prep-coll-item prescription path path source %))))

(defn- eligible-collections [prescription results]
  (let [eligible (filter #(-> % :source ::data-source/coll-of) results)]
    (if (map? (::result/data (:result (first eligible))))
      (->> eligible
           (mapcat #(map-items prescription %))
           (map (fn [{:keys [path source]}] [path source])))
      (->> eligible
           (map #(coll-items prescription %))
           (mapcat #(map-indexed (fn [idx {:keys [path source]}]
                                   [(conj (->path path) idx) source]) %))))))

(defn- nested-params [{:keys [source path result]} nested-path nested-source]
  (let [result-path (or (::data-source/template-path source) path)]
    (if ((resolve-deps-1 (::data-source/params nested-source) nested-path) result-path)
      (::data-source/params (provide-deps {result-path result} nested-source))
      {result-path (::result/data result)})))

(defn- eligible-nested [prescription results]
  (->> results
       (filter #(-> % :source ::data-source/begets))
       (mapcat (fn [{:keys [path source result] :as message}]
                 (let [base-path (->path path)]
                   (map (fn [[p template]]
                          (let [nested-path (conj base-path p)
                                nested-source (prescription template)]
                            [nested-path
                             (-> (prep-nested-source nested-source (nested-params message nested-path nested-source))
                                 (assoc ::data-source/member-of path)
                                 (assoc ::data-source/template-path template))]))
                        (::data-source/begets source)))))))

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

(defn- merge-collection-results [prescription results loaded]
  (->> results
       (filter #(-> % :source ::data-source/member-of))
       (remove #(-> % :result ::result/partial?))
       (reduce
        (fn [loaded {:keys [path source result]}]
          (let [coll (::data-source/member-of source)]
            (if (and (result/success? result))
              (assoc-in loaded [coll ::result/data (last path)] (::result/data result))
              (assoc-in loaded [coll ::result/success?] false))))
        loaded)))

(defn- update-results [prescription results batch-results refresh]
  (apply
   dissoc
   (->> batch-results
        (map (fn [{:keys [path source result]}]
               [path result]))
        (into {})
        (merge results)
        (merge-collection-results prescription batch-results))
   refresh))

(defn- desired-subs [prescription loaded path]
  (if (get-in prescription [path ::data-source/coll-of])
    (count (::result/data (loaded path)))
    (count (get-in prescription [path ::data-source/begets]))))

(defn- completed-subs [prescription loaded path]
  (->> loaded
       (remove #(-> % second ::result/partial?))
       (filter #(= path (get-in prescription [(first %) ::data-source/member-of])))
       count))

(defn- complete-collections [opt ch result loaded prescription ks]
  (let [incomplete (filter #(-> % second ::result/partial?) loaded)
        completed (->> incomplete
                       (filter (fn [[p source]]
                                 (= (desired-subs prescription loaded p)
                                    (completed-subs prescription loaded p))))
                       (map first))]
    (when (seq completed)
      (a/go
        (let [loaded (reduce #(update %1 %2 dissoc ::result/partial?) loaded completed)
              results (map (fn [k]
                             {:path k
                              :source (prep-source (prescription k))
                              :result (loaded k)}) completed)]
          (doseq [result results]
            (a/>! ch result))
          {:loaded (merge-collection-results prescription results loaded)
           :prescription prescription
           :ks ks})))))

(defn- process-batch-results [opt loaded prescription ks results retryable]
  (let [nested (into {} (concat (eligible-collections prescription results)
                                (eligible-nested prescription results)))
        refresh (mapcat ::result/refresh (vals retryable))
        loaded (update-results prescription loaded results refresh)]
    {:loaded loaded
     :prescription (update-prescription prescription nested results retryable refresh)
     :ks (set (pending loaded (concat ks (keys nested) refresh)))}))

(defn- load-cached [cache resolved ks]
  (when-let [cache-get (and (ifn? (:get cache)) (:get cache))]
    (->> ks
         (remove #(let [{::data-source/keys [params refreshing?] :as source} (get resolved %)]
                    (or (nil? source) refreshing? (dep? params))))
         (map #(vector % (get resolved %)))
         (remove #(seq (cache-dep-params (second %))))
         (keep (fn [[k source]]
                 (when-let [result (cache-get k source)]
                   {:path k
                    :source source
                    :result (assoc result
                                   ::result/attempts 0
                                   ::result/cached? true)}))))))

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
  (expand-keys loaded prescription ks (keys-by loaded prescription ks cache-dep-params)))

(defn- expand-cache-deps-selection [loaded prescription ks]
  (let [dep-ks (->> (select-keys prescription ks)
                    vals
                    (mapcat cache-dep-params))]
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

(defn- cache-completed-collections [{:keys [cache]} result loaded prescription]
  (let [completed (->> @result
                       (filter #(-> % second ::result/partial?))
                       (map first)
                       (select-keys loaded)
                       (remove #(-> % second ::result/partial?))
                       (into {}))]
    (doseq [[path result] (remove #(-> % second ::result/cached?) completed)]
      (cache-result cache {:path path :source (prescription path) :result result}))
    (swap! result merge completed)))

(defn fill
  "Fills a prescription. Returns a lazy representation of the result, from which
  you can [[select]] several times, while only fetching each source at most
  once. `fill` optionally takes a map of options:

| key        | description |
|------------|-------------|
| `:params`  | Initial data. Every key in this map will be available as dependencies in the prescription
| `:timeout` | The maximum number of milliseconds to wait for a fetch to complete. Default is no timeout.
| `:cache`   | A map of `:get` (function) and `:put` (function) to optionally cache results, see below.

  ## Caching

  To make Pharmacist cache results and make fewer requests, you can plug in a
  cache. A cache is two functions: `get` and `put`. `get` receives a `path`
  (which is one of the keys in your `prescription`), and a `source` (the key's
  corresponding value in the `prescription`) and should return a
  [[pharmacist.result]], if any exists for the source. `put` receives the
  `path`, `source`, and additionally the `result`."
  [prescription & [opt]]
  (let [result (atom (mapvals result/success (:params opt)))
        prescription (mapvals ensure-id prescription)]
    (fn [ks]
      (let [ch (a/chan 512)]
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
                  (cache-completed-collections opt result loaded prescription)
                  (recur loaded prescription ks))
                (let [resolved (resolve-deps prescription (pending loaded ks))]
                  (doseq [k (pending loaded (keys resolved))]
                    (a/>! ch {:source (prep-source (get resolved k))
                              :path k
                              :result {::result/success? false
                                       ::result/attempts 0}}))
                  (a/close! ch))))))
        ch))))

(defn select
  "Given a filled prescription, as returned from [[fill]], this function selects
  one or more keys. Returns a `clojure.core.async` channel that emits one
  message for each event. When the channel closes, the fetching is complete.

  ## Consuming results

  You can monitor the process of resolving your select by consuming each
  message as it arrives:

```clj
(require '[pharmacist.prescription :as p]
         '[clojure.core.async :refer [go-loop <!]])

(def ch (p/select (p/fill prescription) [:data1 :data2]))

(go-loop []
  (when-let [message (<! ch)]
    (prn message)))
```

  Each message is a map of `:path` (the key from the prescription this message
  pertains to), `:source` (the original source, with parameters fully resolved
  from any dependencies), and `:result`, a result which may or may not be final.
  If the result was not successful, but it is retryable, then the result will
  include `:pharmacist.result/retrying?` to indicate that Pharmacist will try it
  again.

  Results that fail, or that are collections may appear multiple times. To
  combine all the result data into a handy map, see [[merge-results]] and
  [[collect]].
"
  [filled ks]
  (filled ks))

(defn success?
  "Takes a collection of events and returns an overall success indicator for the
  lot - `true` if every source eventually succeeded, `false` otherwise. Can
  return true even if there are failed individual events - e.g. when a source
  has failed, then retried successfully, it will count as an eventual success."
  [sources]
  (->> sources
       (map (fn [{:keys [path result]}]
              [path (::result/success? result)]))
       (into {})
       (map second)
       (reduce #(and %1 %2) true)))

(defn merge-results
  "Given `results`, a collection of messages as received from the channel
  returned from [[select]], merge all the successful result data into a single
  map whose keys are the same as the ones in the original prescription, and the
  values are those result's `:pharmacist.result/data`."
  [results]
  (->> results
       (filter #(get-in % [:result ::result/success?] true))
       (map #(update % :path ->path))
       (sort-by #(-> % :path count))
       (reduce (fn [res {:keys [path result]}]
                 (assoc-in res path (unseq (::result/data result)))) {})))

(defn collect
  "[[select]] returns a channel that emits many messages, allowing you to track
  the progression of loading your sources as it goes along. If you don't really
  care about the intermittent results, you can pipe the channel to `collect`,
  which will collect all the messages into a single result, and provide a handy
  summary of the entire operation, including data merged with [[merge-results]].
  Returns a `cojure.core.async` channel that emits a single message, which is a
  map of:

| key                           | description |
|-------------------------------|-------------|
| `:pharmacist.result/success?` | A boolean indicating overall success. `true` if all sources eventually loaded successfully
| `:pharmacist.result/sources`  | A list of all the messages received from the [[select]] channel
| `:pharmacist.result/data`     | The merged data - all messages passed through [[merge-results]]
"
  [port]
  (a/go-loop [results []]
    (let [result (a/<! port)]
      (cond
        (nil? result) {::result/success? (success? results)
                       ::result/sources results
                       ::result/data (merge-results results)}

        :default (recur (conj results result))))))

(defn pull!
  "Fills a prescription, makes a selection, collects the results and blocks for
  its completion. When you just want to block for the end-result, this
  high-level utility saves you some hassle. The `prescription` and `opt`
  arguments are passed directly to `pharmacist.prescription/fill`, and
  `selection` is passed directly to `pharmacist.prescription/select`."
  [prescription selection & [opt]]
  (-> prescription
      (fill opt)
      (select selection)
      collect
      a/<!!))
