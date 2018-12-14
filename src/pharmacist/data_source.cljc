(ns pharmacist.data-source
  "Tools for implementing data sources"
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(s/def ::id keyword?)
(s/def ::params (s/map-of any? any?))
(s/def ::retryable? boolean?)
(s/def ::retries integer?)
(s/def ::dep boolean?)
(s/def ::nested-prescriptions (s/and set? (s/coll-of keyword?)))
(s/def ::prescription (s/keys :req [::id]
                              :opt [::params ::retryable? ::retries ::dep ::nested-prescriptions]))

(defmulti fetch-sync (fn [prescription] (::id prescription)))
(defmulti fetch (fn [prescription] (::id prescription)))

(defmethod fetch :default [prescription]
  (a/go (fetch-sync prescription)))

(defmulti cache-key
  "Given a prescription, and params (possibly a subset of the params in the
  prescription), return the cache key that uniquely addresses content loaded by
  the prescription. The default implementation combines the id/type with all
  parameters"
  (fn [prescription params] (::id prescription)))
