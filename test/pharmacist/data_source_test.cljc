(ns pharmacist.data-source-test
  (:require [pharmacist.data-source :as sut]
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario]])
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]]))
  #?(:cljs (:require-macros [pharmacist.cljs-test-helper :refer [defscenario]])))

(defmethod sut/cache-key :custom [{::sut/keys [params]}]
  [:customz (:id params)])

(defscenario "Uses prescription path and params to build cache-key"
  (is (= (sut/cache-key {::sut/id :source
                         ::sut/params {:lol 2}})
         [:source {[:lol] 2}])))

(defscenario "Calculates stable cache keys for same params"
  (is (= (sut/cache-key {::sut/id :source
                         ::sut/params {:hello 5
                                       :lal 3
                                       :lol 2}})
         (sut/cache-key {::sut/id :source
                         ::sut/params {:lol 2
                                       :lal 3
                                       :hello 5}}))))

(defscenario "Calculates custom cache keys"
  (is (= (sut/cache-key {::sut/id :custom
                         ::sut/params {:hello 5
                                       :id 14
                                       :lal 3
                                       :lol 2}})
         [:customz 14])))
