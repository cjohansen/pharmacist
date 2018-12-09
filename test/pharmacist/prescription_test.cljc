(ns pharmacist.prescription-test
  (:require [pharmacist.prescription :as sut]
            [pharmacist.data-source :as data-source]
            [pharmacist.cache :as cache]
            [pharmacist.result :as result]
            [pharmacist.utils :refer [test-async test-within]]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])
            #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])))

(deftest resolve-deps-test
  (testing "Ignores no dependencies"
    (is (= (sut/resolve-deps {:data-1 {::data-source/id :data/one}
                              :data-2 {::data-source/id :data/two}})
           {:data-1 {::data-source/id :data/one
                     ::data-source/deps #{}}
            :data-2 {::data-source/id :data/two
                     ::data-source/deps #{}}})))

  (testing "Picks up single dependency"
    (is (= (sut/resolve-deps
            {:data-1 {::data-source/id :data/one}
             :data-2 {::data-source/id :data/two
                      ::data-source/params {:some-param ^::data-source/dep [:data-1 :data]}}})
           {:data-1 {::data-source/id :data/one
                     ::data-source/deps #{}}
            :data-2 {::data-source/id :data/two
                     ::data-source/params {:some-param [:data-1 :data]}
                     ::data-source/deps #{:data-1}}})))

  (testing "Picks up single non-existent dependency"
    (is (= (sut/resolve-deps
            {:data-1 {::data-source/id :data/one
                      ::data-source/params {:id ^::data-source/dep [:config :id]}}})
           {:data-1 {::data-source/id :data/one
                     ::data-source/params {:id ^::data-source/dep [:config :id]}
                     ::data-source/deps #{:config}}})))

  (testing "Picks up full params map as dependency"
    (is (= (sut/resolve-deps {:data-1 {::data-source/params ^::data-source/dep [:config]}})
           {:data-1 {::data-source/params ^::data-source/dep [:config]
                     ::data-source/deps #{:config}}})))

  (testing "Ignores single dependency without meta data"
    (is (= (sut/resolve-deps {:data-1 {::data-source/id :data/one}
                              :data-2 {::data-source/id :data/two
                                       ::data-source/params {:some-param [:data-1 :data]}}})
           {:data-1 {::data-source/id :data/one
                     ::data-source/deps #{}}
            :data-2 {::data-source/id :data/two
                     ::data-source/params {:some-param [:data-1 :data]}
                     ::data-source/deps #{}}})))

  (testing "Fails when metadata indicates dependency, but dep is not a vector"
    (is (thrown?
         #?(:clj Exception
            :cljs js/Error)
         (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:some-param ^::data-source/dep {}}}}))))

  (testing "Fails when metadata indicates dependency, but params is not a vector"
    (is (thrown?
         #?(:clj Exception
            :cljs js/Error)
         (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params ^::data-source/dep {}}}))))

  (testing "Picks up multiple dependencies"
    (is (= (sut/resolve-deps
            {:data-1 {::data-source/id :data/one}
             :data-2 {::data-source/id :data/two
                      ::data-source/params {:some-param ^::data-source/dep [:data-1 :data]
                                            :other ^::data-source/dep [:data-1 :other]}}
             :data-3 {::data-source/id :data/three
                      ::data-source/params {:another ^::data-source/dep [:data-1 :data]
                                            :final ^::data-source/dep [:data-2 :prop]}}})
           {:data-1 {::data-source/id :data/one
                     ::data-source/deps #{}}
            :data-2 {::data-source/id :data/two
                     ::data-source/params {:some-param [:data-1 :data]
                                           :other [:data-1 :other]}
                     ::data-source/deps #{:data-1}}
            :data-3 {::data-source/id :data/three
                     ::data-source/params {:another [:data-1 :data]
                                           :final [:data-2 :prop]}
                     ::data-source/deps #{:data-1 :data-2}}}))))

(deftest deps-valid?-test
  (testing "Errors on cyclic dependency"
    (is (thrown-with-msg?
         #?(:clj Exception
            :cljs js/Error)
         #":data-1 .* cyclic .*:data-2 -> :data-1"
         (sut/deps-valid? {:data-1 {:data-source/deps #{:data-2}}
                           :data-2 {:data-source/deps #{:data-1}}}))))

  (testing "Errors on deeply cyclic dependency with path"
    (is (thrown-with-msg?
         #?(:clj Exception
            :cljs js/Error)
         #":data-1 .* cyclic .*:data-2 -> :data-3 -> :data-4 -> :data-1"
         (sut/deps-valid? {:data-1 {:data-source/deps #{:data-2}}
                         :data-2 {:data-source/deps #{:data-3}}
                         :data-3 {:data-source/deps #{:data-4}}
                         :data-4 {:data-source/deps #{:data-1}}}))))

  (testing "Errors on another cyclic dependency"
    (is (thrown-with-msg?
         #?(:clj Exception
            :cljs js/Error)
         #":data-3 .* cyclic .*:data-4 -> :data-3"
         (sut/deps-valid? {:data-1 {:data-source/deps #{:data-2}}
                         :data-2 {}
                         :data-3 {:data-source/deps #{:data-1 :data-4}}
                         :data-4 {:data-source/deps #{:data-3}}}))))

  (testing "Errors on undefined dependency"
    (is (thrown-with-msg?
         #?(:clj Exception
            :cljs js/Error)
         #":data-3 .* missing .*:data-4"
         (sut/deps-valid? {:data-1 {::data-source/deps #{:data-2}}
                           :data-2 {}
                           :data-3 {::data-source/deps #{:data-1 :data-4}}}))))

  (testing "Allows non-cyclic dependencies"
    (is (sut/deps-valid?
         (sut/resolve-deps
          {:data-1 {}
           :data-2 {::data-source/params {:a ^::data-source/dep [:data-1 :something]}}}))))

  (testing "Allows dependencies on parameters"
    (is (sut/deps-valid?
         (sut/resolve-deps {:data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}})
         {:config {}})))

  (testing "Allows full parameter map to be a single dependency"
    (is (sut/deps-valid?
         (sut/resolve-deps {:data-2 {::data-source/params ^::data-source/dep [:config]}})
         {:config {}})))

  (testing "Errors when params shadow sources"
    (is (thrown-with-msg?
         #?(:clj Exception
            :cljs js/Error)
         #":config shadow"
         (sut/deps-valid?
          (sut/resolve-deps {:config {}
                             :data-2 {::data-source/params {:a ^::data-source/dep [:config :something]}}})
          {:config {}})))))

(deftest partition-fetches
  (testing "Loads everything in parallel with no deps"
    (is (= [#{:data-1 :data-2 :data-3}]
           (sut/partition-fetches {:data-1 {}
                                   :data-2 {}
                                   :data-3 {}} {}))))

  (testing "Loads sources with no dependencies first"
    (is (= [#{:data-1 :data-2} #{:data-3}]
           (sut/partition-fetches {:data-1 {}
                                   :data-2 {}
                                   :data-3 {::data-source/deps #{:data-1}}} {}))))

  (testing "Loads sources in batches"
    (is (= [#{:data-1 :data-2} #{:data-3 :data-4} #{:data-5 :data-6}]
           (sut/partition-fetches {:data-1 {}
                                   :data-2 {}
                                   :data-3 {::data-source/deps #{:data-1}}
                                   :data-4 {::data-source/deps #{:data-1 :data-2}}
                                   :data-5 {::data-source/deps #{:data-3}}
                                   :data-6 {::data-source/deps #{:data-4}}} {}))))

  (testing "Loads data sources only depending on params first"
    (is (= [#{:data-1} #{:data-2}]
           (sut/partition-fetches
            (sut/resolve-deps
             {:data-1 {::data-source/id ::test1
                       ::data-source/params {:id ^::data-source/dep [:config :id]}}
              :data-2 {::data-source/id ::test1
                       ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}})
            {:config {:id 1}}))))

  (testing "Resolves deps and loads data in batches"
    (is (= [#{:data-1} #{:data-2}]
           (sut/partition-fetches
            (sut/resolve-deps
             {:data-1 {::data-source/id ::test1
                       ::data-source/params {:id 42}}
              :data-2 {::data-source/id ::test1
                       ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}})
            {})))))

(deftest provide-deps-test
  (testing "Interpolates deps into individual parameters"
    (is (= {::data-source/params {:param "Data"}}
           (sut/provide-deps {:data-1 {:some "Data"}}
                             {::data-source/params {:param ^::data-source/dep [:data-1 :some]}}))))

  (testing "Provides full parameter map as dependency"
    (is (= {::data-source/params {:some "Data"}}
           (sut/provide-deps {:data-1 {:some "Data"}}
                             {::data-source/params ^::data-source/dep [:data-1]})))))

(defmethod data-source/fetch ::test1 [{:keys [pharmacist.data-source/params]}]
  (a/go
    (result/success {:input-params params})))

(defmethod data-source/fetch ::fail1 [{:keys [pharmacist.data-source/params]}]
  (a/go (result/failure {:error "Oops!!"})))

(deftest fill-prescription-test
  (testing "Puts single data source result on channel"
    (test-async
     (test-within 1000
       (a/go
         (let [v (a/<! (sut/fill {:data-1 {::data-source/id ::test1
                                           ::data-source/params {:id 42}}}))]
           (is (= {:path [:data-1]
                   :source {::data-source/id ::test1
                            ::data-source/params {:id 42}
                            ::data-source/deps #{}}
                   :result {::result/success? true
                            ::result/data {:input-params {:id 42}}
                            ::result/attempts 1}} v)))))))

  (testing "Consumes initial parameters as dependencies"
    (test-async
     (test-within 1000
       (a/go
         (let [v (a/<! (sut/fill {:data-1 {::data-source/id ::test1
                                           ::data-source/params {:id ^::data-source/dep [:config :id]}}}
                                 {:params {:config {:id 23}}}))]
           (prn (:result v))
           (is (= {::result/success? true
                   ::result/data {:input-params {:id 23}}
                   ::result/attempts 1}
                  (:result v))))))))

  (testing "Emits message for un-attempted sources"
    (test-async
     (test-within 1000
       (a/go
         (let [ch (sut/fill {:data-1 {::data-source/id ::fail1
                                      ::data-source/params {:id 42}}
                             :data-2 {::data-source/id ::test1
                                      ::data-source/params ^::data-source/dep [:data-1]}})
               messages (loop [messages []]
                          (if-let [message (a/<! ch)]
                            (recur (conj messages message))
                            messages))]
           (is (= [[:data-1] [:data-2]]
                  (mapv :path messages)))))))))

(deftest collect-test
  (testing "Collects all successful events into a map"
    (test-async
     (test-within 1000
       (a/go
         (prn "[===]")
         (let [data (-> {:data-1 {::data-source/id ::test1
                                  ::data-source/params {:id 42}}
                         :data-2 {::data-source/id ::test1
                                  ::data-source/params {:id 13}}}
                        sut/fill
                        sut/collect
                        a/<!
                        ::result/data)]
           (prn "[---]")
           (is (= {:data-1 {:input-params {:id 42}}
                   :data-2 {:input-params {:id 13}}}
                  data)))))))

  (testing "Provides dependencies to following batches"
    (test-async
     (test-within 1000
       (a/go
         (is (= {:data-1 {:input-params {:id 42}}
                 :data-2 {:input-params {:secondary-id 42}}}
                (-> {:data-1 {::data-source/id ::test1
                              ::data-source/params {:id 42}}
                     :data-2 {::data-source/id ::test1
                              ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
                    sut/fill
                    sut/collect
                    a/<!
                    ::result/data))))))))

(defmethod data-source/fetch-sync ::test2 [{:keys [pharmacist.data-source/params]}]
  (if (get params :succeed? true)
    (result/success {:input-params params})
    (result/failure {:error "Fail"})))

(deftest fill-sync-test
  (testing "Fills prescription synchronously"
    (is (= (-> {:data-1 {::data-source/id ::test2
                         ::data-source/params {:id 42}}
                :data-2 {::data-source/id ::test2
                         ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
               sut/fill-sync
               ::result/data)
           {:data-1 {:input-params {:id 42}}
            :data-2 {:input-params {:secondary-id 42}}})))

  (testing "Indicates overall success"
    (is (-> {:data-1 {::data-source/id ::test2
                      ::data-source/params {:id 42}}
             :data-2 {::data-source/id ::test2
                      ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
            sut/fill-sync
            ::result/success?)))

  (testing "Includes all sources and results"
    (is (= (-> {:data-1 {::data-source/id ::test2
                         ::data-source/params {:id 42}}
                :data-2 {::data-source/id ::test2
                         ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
               sut/fill-sync
               ::result/sources)
           [{:path [:data-1]
             :source {::data-source/id ::test2
                      ::data-source/params {:id 42}
                      ::data-source/deps #{}}
             :result {::result/success? true
                      ::result/data {:input-params {:id 42}}
                      ::result/attempts 1}}

            {:path [:data-2]
             :source {::data-source/id ::test2
                      ::data-source/params {:secondary-id 42}
                      ::data-source/deps #{:data-1}}
             :result {::result/success? true
                      ::result/data {:input-params {:secondary-id 42}}
                      ::result/attempts 1}}])))

  (testing "Indicates overall failure if a single source fails"
    (is (-> {:data-1 {::data-source/id ::test2
                      ::data-source/params {:id 42 :succeed? false}}
             :data-2 {::data-source/id ::test2
                      ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
            sut/fill-sync
            ::result/success?
            not)))

  (testing "Still provides what data is available for partial success"
    (is (= (-> {:data-1 {::data-source/id ::test2
                         ::data-source/params {:id 42}}
                :data-2 {::data-source/id ::test2
                         ::data-source/params {:succeed? false}}}
               sut/fill-sync
               (select-keys [::result/data ::result/success?]))
           {::result/success? false
            ::result/data {:data-1 {:input-params {:id 42}}}})))

  (testing "Reports results for un-attempted sources"
    (is (= (-> {:data-1 {::data-source/id ::test2
                         ::data-source/params {:id 42 :succeed? false}}
                :data-2 {::data-source/id ::test2
                         ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
               sut/fill-sync
               ::result/sources)
           [{:path [:data-1]
             :source {::data-source/id ::test2
                      ::data-source/params {:id 42 :succeed? false}
                      ::data-source/deps #{}}
             :result {::result/success? false
                      ::result/attempts 1
                      ::result/data {:error "Fail"}}}

            {:path [:data-2]
             :source {::data-source/id ::test2
                      ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}
                      ::data-source/deps #{:data-1}}
             :result {::result/success? false
                      ::result/attempts 0}}]))))

(deftest cached-fill-test
  (testing "fill gets cached data when available"
    (test-async
     (test-within 1000
       (a/go
         (let [prescription {::data-source/id ::test2
                             ::data-source/params {:id 42}}
               v (a/<! (sut/fill {:data-1 prescription}
                                 (cache/atom-map (atom {(cache/cache-path prescription)
                                                        {::result/success? true
                                                         ::result/data {:input-params {:id 333}}}}))))]
           (is (= {::result/success? true
                   ::result/data {:input-params {:id 333}}
                   ::result/attempts 0}
                  (:result v))))))))

  (testing "fill caches data when successful"
    (test-async
     (test-within 1000
       (let [cache (atom {})]
         (a/go
           (a/<! (sut/fill {:data-1 {::data-source/id ::test2
                                     ::data-source/params {:id 42}}}
                           (cache/atom-map cache)))
           (is (= {::result/success? true
                   ::result/data {:input-params {:id 42}}}
                  (-> @cache vals first (select-keys [::result/success? ::result/data])))))))))

  (testing "fill does not cache unsuccessful data"
    (test-async
     (test-within 1000
       (let [cache (atom {})]
         (a/go
           (a/<! (sut/fill {:data-1 {::data-source/id ::test2
                                     ::data-source/params {:succeed? false}}}
                           (cache/atom-map cache)))
           (is (nil? (-> @cache vals first))))))))

  (testing "fill-sync gets cached data when it exists"
    (let [prescription {::data-source/id ::test2
                        ::data-source/params {:id 42}}]
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/id ::test2
                           ::data-source/params {:secondary-id ^::data-source/dep [:data-1 :input-params :id]}}}
                 (sut/fill-sync (cache/atom-map (atom {(cache/cache-path prescription)
                                                       {::result/success? true
                                                        ::result/data {:input-params {:id 111}}}})))
                 ::result/data)
             {:data-1 {:input-params {:id 111}}
              :data-2 {:input-params {:secondary-id 111}}}))))

  (testing "fill-sync caches data when successful"
    (let [cache (atom {})]
      (sut/fill-sync {:data-1 {::data-source/id ::test2
                               ::data-source/params {:id 42}}}
                     (cache/atom-map cache))
      (is (= {::result/success? true
              ::result/data {:input-params {:id 42}}}
             (-> @cache vals first (select-keys [::result/success? ::result/data]))))))

  (testing "fill-sync does not cache unsuccessful data"
    (let [cache (atom {})]
      (sut/fill-sync {:data-1 {::data-source/id ::test2
                               ::data-source/params {:succeed? false}}}
                     (cache/atom-map cache))
      (is (nil? (-> @cache vals first))))))
