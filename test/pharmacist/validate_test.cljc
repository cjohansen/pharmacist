(ns pharmacist.validate-test
  (:require [pharmacist.data-source :as data-source]
            [pharmacist.prescription :as p]
            [pharmacist.validate :as sut]
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario]]
               :cljs [pharmacist.cljs-test-helper :refer [defscenario]])))

(defscenario "Errors on cyclic dependency"
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #":data-1 .* cyclic .*:data-2 -> :data-1"
       (sut/deps-valid? {:data-1 {::data-source/deps #{:data-2}}
                         :data-2 {::data-source/deps #{:data-1}}}))))

(defscenario "Errors on deeply cyclic dependency with path"
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #":data-1 .* cyclic .*:data-2 -> :data-3 -> :data-4 -> :data-1"
       (sut/deps-valid? {:data-1 {::data-source/deps #{:data-2}}
                         :data-2 {::data-source/deps #{:data-3}}
                         :data-3 {::data-source/deps #{:data-4}}
                         :data-4 {::data-source/deps #{:data-1}}}))))

(defscenario "Errors on another cyclic dependency"
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #":data-3 .* cyclic .*:data-4 -> :data-3"
       (sut/deps-valid? {:data-1 {::data-source/deps #{:data-2}}
                         :data-2 {}
                         :data-3 {::data-source/deps #{:data-1 :data-4}}
                         :data-4 {::data-source/deps #{:data-3}}}))))

(defscenario "Errors on undefined dependency"
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #":data-3 .* missing .*:data-4"
       (sut/deps-valid? {:data-1 {::data-source/deps #{:data-2}}
                         :data-2 {}
                         :data-3 {::data-source/deps #{:data-1 :data-4}}}))))

(defscenario "Allows non-cyclic dependencies"
  (is (sut/deps-valid?
       (p/resolve-deps
        {:data-1 {}
         :data-2 {::data-source/params {:a ^::data-source/dep [:data-1 :something]}}}))))

(defscenario "Allows dependencies on parameters"
  (is (sut/deps-valid?
       (p/resolve-deps {:data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}})
       {:config {}})))

(defscenario "Allows full parameter map to be a single dependency"
  (is (sut/deps-valid?
       (p/resolve-deps {:data-2 {::data-source/params ^::data-source/dep [:config]}})
       {:config {}})))

(defscenario "Errors when params shadow sources"
  (is (thrown-with-msg?
       #?(:clj Exception
          :cljs js/Error)
       #":config shadow"
       (sut/deps-valid?
        (p/resolve-deps {:config {}
                         :data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}})
        {:config {}}))))
