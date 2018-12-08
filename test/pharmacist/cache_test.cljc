(ns pharmacist.cache-test
  (:require [pharmacist.cache :as sut]
            [pharmacist.data-source :as data-source]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])))

(deftest cache-path-test
  (testing "Uses prescription path and params"
    (is (= ":source/:lol=2"
           (sut/cache-path {::data-source/id :source
                            ::data-source/params {:lol 2}}))))

  (testing "Calculates stable paths for same params"
    (is (= (sut/cache-path {::data-source/id :source
                            ::data-source/params {:hello 5
                                                  :lal 3
                                                  :lol 2}})
           (sut/cache-path {::data-source/id :source
                            ::data-source/params {:lol 2
                                                  :lal 3
                                                  :hello 5}})))))

(deftest cache-get-put-test
  (testing "Retrieves item put in cache"
    (let [cache (atom {})]
      (sut/cache-put cache [:source1] {::data-source/params {:id "1"}} {:value "To cache"})
      (is (= [{:value "To cache"}] (vals @cache)))

      (is (= {:value "To cache"}
             (sut/cache-get cache [:source1] {::data-source/params {:id "1"}}))))))

(deftest atom-map-cache-test
  (testing "Wraps cache in getter and setter"
    (let [cache (atom {})
          {:keys [cache-get cache-put]} (sut/atom-map cache)]
      (cache-put [:source1] {::data-source/params {:id "1"}} {:value "To cache"})
      (is (= [{:value "To cache"}] (vals @cache)))

      (is (= {:value "To cache"}
             (cache-get [:source1] {::data-source/params {:id "1"}}))))))
