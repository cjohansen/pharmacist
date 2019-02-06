(ns pharmacist.data-source
  "Tools for implementing data sources"
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            [clojure.string :as str]
            [pharmacist.result :as result]))

(defn- fname [f]
  (when-let [name (some-> f str (str/replace #"_" "-"))]
    (second (re-find #"(?:#')?(.*)" name))))

(defn id
  "Return the id of the provided source. Returns the `:pharmacist.data-source/id`
  key if set, otherwise makes an effort to infer the id from either
  `:pharmacist.data-source/fn` or `:pharmacist.data-source/async-fn`, whichever
  is set. If you don't want to manually assign ids, it is strongly recommended
  that `:fn`/`:async-fn` is set to vars, not function values, as Pharmacist will
  be better able to infer function names."
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

(defn safe-async [f source]
  (try
    (f source)
    (catch #?(:clj Throwable :cljs :default) e
      (a/go (error-result e)))))

(defmethod fetch :default [source]
  (cond
    (::async-fn source) (safe-async (::async-fn source) source)
    (::fn source) (safe-sync (::fn source) source)
    :default (safe-sync fetch-sync source)))

(defn safe-fetch [source]
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
   ::data-source/params {:id ^::data-source/dep [:dep1 :id]
                         :type ^::data-source/dep [:dep2 :type]}
   ::data-source/cache-params [[:dep1 :id]]})
```"
  (fn [source] (::id source)))

(defmethod cache-params :default [{::keys [params cache-params]}]
  (or cache-params
      (if (vector? params)
        [params]
        (map #(vector %) (keys params)))))

(defmulti cache-deps
  "Specify which dependencies are relevant to compute a cache key. Should return a
  set of dependencies. The default implementation extracts the dependencies from
  the result from [[cache-params]]. Most use-cases are best solved using
  [[cache-params]], and in the rare cases where you need to control cache
  dependencies manually, it is recommended to specify it declaratively in your
  sources:

```clojure
(require '[pharmacist.data-source :as data-source])

(def my-source
  {::data-source/fn #'fetch-my-source
   ::data-source/params {:id ^::data-source/dep [:dep1 :id]
                         :type ^::data-source/dep [:dep2 :type]}
   ::data-source/cache-deps #{:dep1}})
```"
  (fn [source] (::id source)))

(defmethod cache-deps :default [source]
  (or (::cache-deps source)
      (->> (cache-params source)
           (map first)
           (into #{}))))

(defmulti cache-key
  "Given a source, return the cache key that uniquely addresses content loaded by
  the source. The default implementation combines the id/type with the
  [[cache-params]]. It is strongly recommended to make sure the source's
  variations are expressed as parameters so you can rely on the default
  implementation of this method. If this is somehow not possible, you can
  implement this method, which dispatches on a source's
  `:pharmacist.data-source/id`, to provide a custom cache key:

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
