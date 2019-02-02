(ns pharmacist.validate-test
  (:require #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario]])
            [pharmacist.data-source :as data-source]
            [pharmacist.prescription :as p]
            [pharmacist.validate :as sut])
  #?(:cljs (:require-macros [pharmacist.cljs-test-helper :refer [defscenario]])))

(defscenario "Errors on cyclic dependency"
  (let [res (sut/validate-deps {:data-1 {::data-source/deps #{:data-2}}
                                :data-2 {::data-source/deps #{:data-1}}})]
    (is (= (select-keys res [:type :data])
           {:type :cyclic-dependency
            :data [:data-2 :data-1]}))
    (is (re-find #":data-1 .* cyclic .*:data-2 -> :data-1" (:message res)))))

(defscenario "Errors on deeply cyclic dependency with path"
  (let [res (sut/validate-deps {:data-1 {::data-source/deps #{:data-2}}
                              :data-2 {::data-source/deps #{:data-3}}
                              :data-3 {::data-source/deps #{:data-4}}
                              :data-4 {::data-source/deps #{:data-1}}})]
    (is (= (select-keys res [:type :data])
           {:type :cyclic-dependency
            :data [:data-2 :data-3 :data-4 :data-1]}))
    (is (re-find #":data-1 .* cyclic .*:data-2 -> :data-3 -> :data-4 -> :data-1" (:message res)))))

(defscenario "Errors on another cyclic dependency"
  (let [res (sut/validate-deps {:data-1 {::data-source/deps #{:data-2}}
                                :data-2 {}
                                :data-3 {::data-source/deps #{:data-1 :data-4}}
                                :data-4 {::data-source/deps #{:data-3}}})]
    (is (= (select-keys res [:type :data])
           {:type :cyclic-dependency
            :data [:data-4 :data-3]}))
    (is (re-find #":data-3 .* cyclic .*:data-4 -> :data-3" (:message res)))))

(defscenario "Errors on undefined dependency"
  (let [res (sut/validate-deps {:data-1 {::data-source/deps #{:data-2}}
                                :data-2 {}
                                :data-3 {::data-source/deps #{:data-1 :data-4}}})]
    (is (= (select-keys res [:type :data])
           {:type :missing-dep
            :data {:path :data-3
                   :missing [:data-4]}}))
    (is (re-find #":data-3 .* missing .*:data-4" (:message res)))))

(defscenario "Allows non-cyclic dependencies"
  (is (sut/validate-deps
       (p/resolve-deps
        {:data-1 {}
         :data-2 {::data-source/params {:a ^::data-source/dep [:data-1 :something]}}}))))

(defscenario "Allows dependencies on parameters"
  (is (sut/validate-deps
       (p/resolve-deps {:data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}})
       {:config {}})))

(defscenario "Allows full parameter map to be a single dependency"
  (is (sut/validate-deps
       (p/resolve-deps {:data-2 {::data-source/params ^::data-source/dep [:config]}})
       {:config {}})))

(defscenario "Errors when params shadow sources"
  (let [res (sut/validate-deps
             {:config {}
              :data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}}
             {:config {}})]
    (is (= (select-keys res [:type :data])
           {:type :source-shadowing
            :data #{:config}}))
    (is (re-find #":config shadow" (:message res)))))
