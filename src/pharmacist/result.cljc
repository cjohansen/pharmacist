(ns pharmacist.result
  "Namespace and functions to support construction and processing of data source
  results"
  (:require #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [pharmacist.data-source :as data-source]))

(s/def ::success? boolean?)
(s/def ::path (s/coll-of keyword?))
(s/def ::data any?)
(s/def ::prescriptions (s/coll-of (s/tuple (s/coll-of keyword?) ::data-source/prescription)))
(s/def ::result (s/keys :req [::success?]
                        :opt [::path ::data ::prescriptions]))

(s/def ::success?-args (s/cat :result ::result))

(defn success?
  "Returns true if this particular result was a success, as indicated by
  the :pharmacist.result/success? key"
  [result]
  (::success? result))

(s/fdef success?
  :args ::success?-args
  :ret boolean?)

(s/def ::success-args (s/or :unary (s/cat :data any?)
                            :binary (s/cat :data any? :prescriptions ::prescriptions)))

(defn success
  "Create a successful result with data, and optionally nested prescriptions"
  [data & [prescriptions]]
  (merge {::success? true
          ::data data}
         (when prescriptions
           {::prescriptions prescriptions})))

(s/fdef success
  :args ::success-args
  :ret ::result)

(s/def ::failure-args (s/or :nullary (s/cat)
                            :unary (s/cat :data any?)))

(defn failure
  "Create a failed result, optionally with data"
  [& [data]]
  (merge {::success? false}
         (when data {::data data})))

(s/fdef failure
  :args ::failure-args
  :ret ::result)
