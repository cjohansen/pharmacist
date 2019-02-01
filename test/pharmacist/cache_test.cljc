(ns pharmacist.cache-test
  (:require [pharmacist.cache :as sut]
            [pharmacist.data-source :as data-source]
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario]])
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]]))
  #?(:cljs (:require-macros [pharmacist.cljs-test-helper :refer [defscenario]])))

(defmethod data-source/cache-key :custom [{::data-source/keys [params]}]
  [:customz (:id params)])

(defscenario "Uses prescription path and params to build cache-key"
  (is (= (sut/cache-key {::data-source/id :source
                         ::data-source/params {:lol 2}})
         [:source {[:lol] 2}])))

(defscenario "Calculates stable cache keys for same params"
  (is (= (sut/cache-key {::data-source/id :source
                         ::data-source/params {:hello 5
                                               :lal 3
                                               :lol 2}})
         (sut/cache-key {::data-source/id :source
                         ::data-source/params {:lol 2
                                               :lal 3
                                               :hello 5}}))))

(defscenario "Calculates custom cache keys"
  (is (= (sut/cache-key {::data-source/id :custom
                         ::data-source/params {:hello 5
                                               :id 14
                                               :lal 3
                                               :lol 2}})
         [:customz 14])))

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
