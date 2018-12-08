(ns pharmacist.utils
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [async is]])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [orchestra.spec.test :as st]))

(s/check-asserts true)
(st/instrument)

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj
     (a/<!! ch)
     :cljs
     (async done
       (a/take! ch (fn [_] (done))))))

(defn test-within
  "Asserts that ch does not close or produce a value within ms. Returns a
  channel from which the value can be taken."
  [ms ch]
  (a/go (let [t (a/timeout ms)
              [v ch] (a/alts! [ch t])]
          (is (not= ch t)
              (str "Test should have finished within " ms "ms."))
          v)))
