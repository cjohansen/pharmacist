(ns pharmacist.cache-test
  (:require [pharmacist.cache :as sut]
            [pharmacist.data-source :as data-source]
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario]])
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]]))
  #?(:cljs (:require-macros [pharmacist.cljs-test-helper :refer [defscenario]])))

(defscenario "Retrieves item put in cache"
  (let [cache (atom {})]
    (sut/cache-put cache [:source1] {::data-source/id :data-source-1
                                     ::data-source/params {:id "1"}} {:value "To cache"})
    (is (= (vals @cache) [{:value "To cache"}]))

    (is (= (sut/cache-get cache [:source1] {::data-source/id :data-source-1
                                            ::data-source/params {:id "1"}})
           {:value "To cache"}))))

(defscenario "Wraps cache in getter and setter"
  (let [cache (atom {})
        {:keys [cache-get cache-put]} (sut/atom-map cache)]
    (cache-put [:source1] {::data-source/id :data-source-1
                           ::data-source/params {:id "1"}} {:value "To cache"})
    (is (= (vals @cache) [{:value "To cache"}]))

    (is (= (cache-get [:source1] {::data-source/id :data-source-1
                                  ::data-source/params {:id "1"}})
           {:value "To cache"}))))
