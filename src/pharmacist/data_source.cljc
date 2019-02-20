(ns pharmacist.data-source
  "A data source is the declarative description of a single remote source of data.
  It must include a reference to a function (or an id if using multi-method
  dispatch), and can include additional information about parameters,
  dependencies on other sources/initial parameters, the maximum number of
  retries, and how to fetch child sources.

```clj
(require '[pharmacist.data-source :as data-source]
         '[pharmacist.result :as result])

(defn fetch-playlist [source]
  (result/success {:id (-> source ::data-source/params :playlist-id)}))

(def playlist-data-source
  {::data-source/fn #'fetch-playlist
   ::data-source/params {:playlist-id 42}})
```

  ## Fetch functions

  Pharmacist will try the following three ways to fetch your source, in order of
  preference:

  1. Call the function in `:pharmacist.data-source/fn`
  2. Call the function in `:pharmacist.data-source/async-fn`
  3. Dispatch [[fetch]] on the source's `:pharmacist.data-source/id`

  All of these functions accept the fully resolved source as their only
  argument. The return value from the function passed to
  `:pharmacist.data-source/fn` should be a [[pharmacist.result]]. The return
  value from `:pharmacist.data-source/async-fn` should be a clojure.core.async
  channel that emits a single message which should be a [[pharmacist.result]].

  If the fetch function throws an exception, or otherwise fails to meet its
  contract, the error will be wrapped in a descriptive [[parmacist.result]].

  ## Parameters and dependencies

  In its simplest form, the parameter map is just a map of values to pass to the
  fetch function. To further parameterize the data source, the parameter map can
  depend on values that will be provided as the full prescription is filled, or
  even resulting values from other sources. Dependencies are declared with a
  vector that has the `:pharmacist.data-source/dep` keyword set as meta-data:

```clojure
(require '[pharmacist.data-source :as data-source])

(def playlist
  {::data-source/fn #'fetch-playlist
   ::data-source/params {:id ^::data-source/dep [:playlist-id]}})
```

  Now Pharmacist will look for `:playlist-id` in the initial parameters passed
  to [[pharmacist.prescription/fill]] or other sources in the prescription. It
  will then retrieve the source and pass the full result as the `:id` parameter
  to the `fetch-playlist` source. If you just need to pick a single value from
  the dependency, use a path:

```clj
(def playlist
  {::data-source/fn #'fetch-playlist
   ::data-source/params {:id ^::data-source/dep [:user :id]}})
```

  In this updated example, the `:id` parameter will receive the value of `:id`
  in the result of the `:user` data source - or the `:id` key of the map passed
  as `:user` in the initial parameters:

```clj
(pharmacist.prescription/fill
  {:playlist playlist}
  {:params {:user {:id 42}}})
```

  When the keys of the dependency are the same as the parameters your source
  expects, like above (`:id => [:user :id]`), you can just make the whole
  parameters map a dependency:

```clj
(def playlist
  {::data-source/fn #'fetch-playlist
   ::data-source/params ^::data-source/dep [:user]})
```

  ## Collection sources

  Pharmacist can fetch collections where you don't know upfront how many sources
  you'll find. To do this you must provide two source definitions: one that
  defines a single source in the collection, and another that specifies how many
  times to fetch that source, and parameters for each individual fetch.

  Imagine that you wanted to fetch all of a user's playlists. First define a
  function that receives a user and returns a vector of all their playlists:

```clj
(require '[pharmacist.result :as result])

(defn all-playlists [{::data-source/keys [params]}]
  ;; params is now the user - find their playlists from the map, over HTTP, from
  ;; disk, or whatever
  (result/success (:playlists params)))
```

  Then define the prescription with the collection and the item source:

```clj
(require '[pharmacist.data-source :as data-source])

(def prescription
  {:playlist {::data-source/fn #'fetch-playlist
              ::data-source/params {:id ^::data-source/dep [:playlist-id]}}

   :user-playlists {::data-source/fn #'all-playlists
                    ::data-source/params {:user ^::data-source/dep [:user]}
                    ::data-source/coll-of :playlist}})
```

  You can now fill this with an initial user (you could of course also get the
  user from yet another data source):

```clj
(pharmacist.prescription/fill
  prescription
  {:params {:user {:playlists [{:id 1}
                               {:id 2}]}}})
```

  The `:playlist` source will be fetched twice - once with `{:id 1}` as its
  parameters, and once with `{:id 2}`. The parameters returned from the
  selection function are merged into the map in the prescription, so in the
  above example, `{:id ^::data-source/dep [:playlist-id]}` isn't strictly
  necessary, but it is recommended, because it documents the expected parameters
  **and** it makes the source usable outside of the collection as well.

  In order to load a different selection of the collection, define another
  selection function, and make it a `::data-source/coll-of` the same item
  source.

  ## Data source keys

| key                          | description |
| -----------------------------|-------------|
| `::data-source/id`           | Keyword identifying this source. Can be inferred from `::fn` or `::async-fn`, see [[id]]
| `::data-source/fn`           | Function that fetches this source. Should return a [[pharmacist.result]]
| `::data-source/async-fn`     | Function that fetches this source asynchronously. Should return a core.async channel that emits a single [[pharmacist.result]]
| `::data-source/params`       | Parameter map to pass to the fetch function
| `::data-source/retries`      | The number of times `::result/retryable?` results from this source can be retried
| `::data-source/retry-delays` | A vector of milliseconds to wait before retries
| `::data-source/timeout`      | The maximum number of milliseconds to wait for this source to be fetched.
| `::data-source/coll-of`      | The type of source this source is a collection of
| `::data-source/cache-params` | A collection of paths to extract as the cache key, see [[pharmacist.cache/cache-params]]
| `::data-source/cache-deps`   | The dependencies required to compute the cache key. Usually inferred from `::data-source/cache-params`, see [[pharmacist.cache/cache-deps]]
"
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [clojure.string :as str]
            [pharmacist.result :as result]))

(s/def ::id keyword?)
(s/def ::fn ifn?)
(s/def ::async-fn ifn?)
(s/def ::params (s/or :dependency (s/coll-of keyword?)
                      :map map?))
(s/def ::retries number?)
(s/def ::path (s/or :coll (s/coll-of (s/or :keyword keyword? :number number?))
                    :keyword keyword?))
(s/def ::cache-params (s/coll-of ::path))
(s/def ::cache-deps (s/coll-of ::path))
(s/def ::coll-of keyword?)
(s/def ::in-coll keyword?)

(defn- fname [f]
  (when-let [name (some-> f str (str/replace #"_" "-"))]
    (second (re-find #"(?:#')?(.*)" name))))

(defn id
  "Return the id of the provided source. Returns the `:pharmacist.data-source/id`
  key if set, otherwise makes an effort to infer the id from either
  `:pharmacist.data-source/fn` or `:pharmacist.data-source/async-fn`, whichever
  is set. If you don't want to manually assign ids, it is strongly recommended
  that `:fn`/`:async-fn` is set to vars, not function values, as Pharmacist will
  be better able to infer function names.

  The id is used to address results from the source in cache, and to dispatch
  [[fetch]] (when not using `:pharmacist.data-source/fn` or
  `:pharmacist.data-source/async-fn`)."
  [{::keys [id fn async-fn]}]
  (or id
      (when-let [f (or fn async-fn)]
        #?(:clj
           (if-let [m (meta f)]
             (keyword (str (:ns m)) (str(:name m)))
             (let [name (fname f)]
               (if-let [[_ ns n] (re-find #"(.*)\$(.*)@" name)]
                 (keyword ns n)
                 (keyword name))))
           :cljs
           (let [name (fname f)]
             (if-let [[_ res] (re-find #"function (.+)\(" name)]
               (let [[f & ns ] (-> res (str/split #"\$") reverse)]
                 (keyword (str/join "." (reverse ns)) f))
               (if (re-find #"function \(" name)
                 (keyword (str (random-uuid)))
                 (keyword (str/replace name #" " "-")))))))))

(defmulti fetch-sync
  "Fetch a source synchronously. This method is dispatched on the
  `:pharmacist/data-source/id` key of the source. If no
  `:pharmacist.data-source/fn` or `:pharmacist.data-source/async-fn` is
  provided, and [[fetch]] is not implemented for the id, then this method is
  called with a single source. It should return a [[parmacist.result/success]]
  or [parmacist.result/failure]."
  (fn [source] (::id source)))

(defmulti fetch
  "Fetch a source asynchronously. This method is dispatched on the
  `:pharmacist/data-source/id` key of the source. If no
  `:pharmacist.data-source/fn` or `:pharmacist.data-source/async-fn` is
  provided, this method is called with a single source. It should return a
  `clojure.core.async/chan` that emits a single message, which should be one
  of [[parmacist.result/success]] or [[parmacist.result/failure]]. The default
  implementation delegates to [fetch-async]."
  (fn [source] (::id source)))

(defn- error-result [err]
  (result/error err {:message (str "Fetch threw exception: " #?(:clj (.getMessage err)
                                                                :cljs (.-message err)))
                     :type :pharmacist.error/fetch-exception}))

(defn- safe-sync [f source]
  (a/go
    (try
      (f source)
      (catch #?(:clj Throwable :cljs :default) e
        (error-result e)))))

(defn ^:no-doc safe-async [f source]
  (try
    (f source)
    (catch #?(:clj Throwable :cljs :default) e
      (a/go (error-result e)))))

(defmethod fetch :default [source]
  (cond
    (::async-fn source) (safe-async (::async-fn source) source)
    (::fn source) (safe-sync (::fn source) source)
    :default (safe-sync fetch-sync source)))

(defn ^:no-doc safe-fetch [source]
  (safe-async fetch source))

(defmulti cache-params
  "Selects which parameters to use to calculate the cache key for a source.
  Should return a vector of paths to extract from the source after all
  dependencies are resolved. Specify cache-params declaratively in your sources
  instead of implementing this method.

```clojure
(require '[pharmacist.data-source :as data-source])

(def my-source
  {::data-source/fn #'fetch-my-source
   ::data-source/params {:thing ^::data-source/dep [:dep1 :id]
                         :type ^::data-source/dep [:dep2 :type]}
   ::data-source/cache-params [[:thing :id]]})
```"
  (fn [source] (::id source)))

(defmethod cache-params :default [{::keys [params cache-params]}]
  (or cache-params
      (if (vector? params)
        [params]
        (map #(vector %) (keys params)))))

(defmulti cache-deps
  "Specify which parameters are relevant to compute a cache key. Should return a
  set of params. The default implementation extracts the params from the result
  from [[cache-params]]. Most use-cases are best solved using
  [[cache-params]], and in the rare cases where you need to control cache
  dependencies manually, it is recommended to specify it declaratively in your
  sources:

```clojure
(require '[pharmacist.data-source :as data-source])

(def my-source
  {::data-source/fn #'fetch-my-source
   ::data-source/params {:id ^::data-source/dep [:dep1 :id]
                         :type ^::data-source/dep [:dep2 :type]}
   ::data-source/cache-deps #{:id}})
```"
  (fn [source] (::id source)))

(defmethod cache-deps :default [source]
  (or (::cache-deps source)
      (->> (cache-params source)
           (map first)
           (into #{}))))

(defmulti cache-key
  "Given a source, return the cache key that uniquely addresses content loaded by
  the source. The default implementation combines the :pharmacist.data-source/id
  with the parameter values selected by [[cache-params]]. It is strongly
  recommended to make sure the source's variations are expressed as parameters
  so you can rely on the default implementation of this method. If this is
  somehow not possible, you can implement this method, which dispatches on a
  source's `:pharmacist.data-source/id`, to provide a custom cache key:

```clojure
(require '[pharmacist.data-source :as data-source])

;; This is just an example, NOT recommended. Environment variables could just as
;; easily be fed in as parameters
(defmethod data-source/cache-key ::my-source [source]
  [(get (System/getenv) \"app_env\") (-> source ::data-source/params :id)])
```"
  (fn [source] (::id source)))

(defmethod cache-key :default [source]
  (let [params (->> (cache-params source)
                    (map (fn [p] [p (get-in (::params source) p)]))
                    (into {}))]
    (cond-> [(id source)]
      (not (empty? params)) (conj params))))
