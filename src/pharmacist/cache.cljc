(ns pharmacist.cache
  "Caching tools for use with Pharmacist prescriptions"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [pharmacist.data-source :as data-source]))

(s/def ::path (s/coll-of keyword?))
(s/def ::prescription (s/keys :req [::data-source/id ::data-source/params]))
(s/def ::cache #(instance? clojure.lang.IRef %))
(s/def ::cache-get-args (s/cat :cache ::cache :path ::path :prescription ::prescription))

(defn cache-get
  "Look up data source in the cache. Expects all parameters in `prescription` to
  be dependency resolved and fully realized."
  [cache path prescription]
  (get @cache (data-source/cache-key prescription)))

(s/fdef cache-get
  :args ::cache-get-args
  :ret (s/or :nil nil? :val any?))

(s/def ::cache-put-args (s/cat :cache ::cache
                               :path ::path
                               :prescription ::prescription
                               :value any?))

(defn cache-put
  "Put item in cache. Expects all parameters in `prescription` to be dependency
  resolved and fully realized."
  [cache path prescription value]
  (swap! cache assoc (data-source/cache-key prescription) value)
  nil)

(s/fdef cache-put
  :args ::cache-put-args
  :ret nil?)

(s/def ::atom-map-args (s/cat :cache ::cache))
(s/def ::get fn?)
(s/def ::put fn?)
(s/def ::cache-params (s/keys :req-un [::get ::put]))

(defn atom-map
  "Given a ref to use as a cache, returns a map of parameters to pass as
  the :cache parameter to [[pharmacist.prescription/fill]] in order to look up
  and store loaded data in the cache.

```clojure
(require '[pharmacist.prescription :as p]
         '[pharmacist.cache :as c]
         '[clojure.core.cache :as cache])

(p/fill prescription {:cache (c/atom-map (atom (cache/ttl-cache-factory {})))})
```"
  [ref]
  {:get (partial cache-get ref)
   :put (partial cache-put ref)})

(s/fdef atom-map
  :args ::atom-map-args
  :ret ::cache-params)
