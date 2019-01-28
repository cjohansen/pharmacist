(ns pharmacist.cache-test
  (:require [pharmacist.cache :as sut]
            [pharmacist.data-source :as data-source]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])))

(defmethod data-source/cache-key :custom [{::data-source/keys [params]}]
  [:customz (:id params)])

(deftest cache-key-test
  (testing "Uses prescription path and params"
    (is (= [:source {:lol 2}]
           (sut/cache-key {::data-source/id :source
                           ::data-source/params {:lol 2}}))))

  (testing "Calculates stable paths for same params"
    (is (= (sut/cache-key {::data-source/id :source
                           ::data-source/params {:hello 5
                                                 :lal 3
                                                 :lol 2}})
           (sut/cache-key {::data-source/id :source
                           ::data-source/params {:lol 2
                                                 :lal 3
                                                 :hello 5}}))))

  (testing "Calculates custom cache paths"
    (is (= [:customz 14]
           (sut/cache-key {::data-source/id :custom
                           ::data-source/params {:hello 5
                                                 :id 14
                                                 :lal 3
                                                 :lol 2}})))))

(deftest cache-get-put-test
  (testing "Retrieves item put in cache"
    (let [cache (atom {})]
      (sut/cache-put cache [:source1] {::data-source/id :data-source-1
                                       ::data-source/params {:id "1"}} {:value "To cache"})
      (is (= [{:value "To cache"}] (vals @cache)))

      (is (= {:value "To cache"}
             (sut/cache-get cache [:source1] {::data-source/id :data-source-1
                                              ::data-source/params {:id "1"}}))))))

(deftest atom-map-cache-test
  (testing "Wraps cache in getter and setter"
    (let [cache (atom {})
          {:keys [cache-get cache-put]} (sut/atom-map cache)]
      (cache-put [:source1] {::data-source/id :data-source-1
                             ::data-source/params {:id "1"}} {:value "To cache"})
      (is (= [{:value "To cache"}] (vals @cache)))

      (is (= {:value "To cache"}
             (cache-get [:source1] {::data-source/id :data-source-1
                                    ::data-source/params {:id "1"}}))))))
