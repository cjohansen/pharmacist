(ns pharmacist.prescription-test
  (:require [pharmacist.prescription :as sut]
            [pharmacist.data-source :as data-source]
            [pharmacist.cache :as cache]
            [pharmacist.result :as result]
            [pharmacist.schema :as schema]
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario defscenario-async]]
               :cljs [pharmacist.cljs-test-helper :refer [defscenario defscenario-async]])
            [pharmacist.schema :as schema]
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])
            #?(:clj [clojure.core.async :refer [<! <!! go go-loop timeout]]
               :cljs [cljs.core.async :refer [<! <!! go go-loop timeout]])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(defscenario "Ignores no dependencies"
  (is (= (sut/resolve-deps {:data-1 {::data-source/id :data/one}
                            :data-2 {::data-source/id :data/two}})
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{}}
          :data-2 {::data-source/id :data/two
                   ::data-source/deps #{}}})))

(defscenario "Picks up single dependency"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:some-param ^::data-source/dep [:data-1 :data]}}})
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:some-param [:data-1 :data]}
                   ::data-source/deps #{:data-1}}})))

(defscenario "Picks up single non-existent dependency"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one
                    ::data-source/params {:id ^::data-source/dep [:config :id]}}})
         {:data-1 {::data-source/id :data/one
                   ::data-source/params {:id ^::data-source/dep [:config :id]}
                   ::data-source/deps #{:config}}})))

(defscenario "Picks up full params map as dependency"
  (is (= (sut/resolve-deps {:data-1 {::data-source/params ^::data-source/dep [:config]}})
         {:data-1 {::data-source/params ^::data-source/dep [:config]
                   ::data-source/deps #{:config}}})))

(defscenario "Ignores single dependency without meta data"
  (is (= (sut/resolve-deps {:data-1 {::data-source/id :data/one}
                            :data-2 {::data-source/id :data/two
                                     ::data-source/params {:some-param [:data-1 :data]}}})
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:some-param [:data-1 :data]}
                   ::data-source/deps #{}}})))

(defscenario "Fails when metadata indicates dependency, but dep is not a vector"
  (is (thrown?
       #?(:clj Exception
          :cljs js/Error)
       (sut/resolve-deps
        {:data-1 {::data-source/id :data/one}
         :data-2 {::data-source/id :data/two
                  ::data-source/params {:some-param ^::data-source/dep {}}}}))))

(defscenario "Fails when metadata indicates dependency, but params is not a vector"
  (is (thrown?
       #?(:clj Exception
          :cljs js/Error)
       (sut/resolve-deps
        {:data-1 {::data-source/id :data/one}
         :data-2 {::data-source/id :data/two
                  ::data-source/params ^::data-source/dep {}}}))))

(defscenario "Picks up multiple dependencies"
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
                   ::data-source/deps #{:data-1 :data-2}}})))

(defscenario "Only resolves dependencies from specified keys"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:some-param ^::data-source/dep [:data-1 :data]
                                          :other ^::data-source/dep [:data-1 :other]}}
           :data-3 {::data-source/id :data/three
                    ::data-source/params {:another ^::data-source/dep [:data-1 :data]
                                          :final ^::data-source/dep [:data-2 :prop]}}}
          [:data-1])
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{}}})))

(defscenario "Resolves transitive dependencies when only resolving for selected keys"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:other ^::data-source/dep [:data-1 :other]}}
           :data-3 {::data-source/id :data/three
                    ::data-source/params {:final ^::data-source/dep [:data-2 :prop]}}}
          [:data-3])
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:other [:data-1 :other]}
                   ::data-source/deps #{:data-1}}
          :data-3 {::data-source/id :data/three
                   ::data-source/params {:final [:data-2 :prop]}
                   ::data-source/deps #{:data-2}}})))

(defscenario "Does not trip on circular dependencies"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one
                    ::data-source/params {:a ^::data-source/dep [:data-2 :data]}}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:a ^::data-source/dep [:data-1 :data]}}}
          [:data-1])
         {:data-1 {::data-source/id :data/one
                   ::data-source/params {:a [:data-2 :data]}
                   ::data-source/deps #{:data-2}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:a [:data-1 :data]}
                   ::data-source/deps #{:data-1}}})))

(defmethod data-source/fetch-sync :source-1 [{::data-source/keys [params]}]
  (result/success params))

(defn stub [ref returns]
  (reset! ref {:returns (into [] (reverse returns)) :calls []}))

(def stub-1 (atom {}))

(defmethod data-source/fetch-sync :stub-1 [{::data-source/keys [params] :as args}]
  (let [val (-> @stub-1 :returns last)]
    (swap! stub-1 update :returns pop)
    (swap! stub-1 update :calls conj args)
    (result/success val)))

(defn exhaust [port]
  (go-loop [messages []]
    (if-let [msg (<! port)]
      (recur (conj messages msg))
      messages)))

(defn results [port]
  (go (->> port
           exhaust
           <!
           (map :result))))

(defn sorted-results [port]
  (go (sort-by #(-> % ::result/data :id) (<! (results port)))))

(defscenario-async "Fetches single data source"
  (go
    (is (= (<! (sut/select (sut/fill {:data-1 {::data-source/id :source-1}}) [:data-1]))
           {:path :data-1
            :source {::data-source/id :source-1
                     ::data-source/params {}
                     ::data-source/deps #{}}
            :result {::result/success? true
                     ::result/attempts 1
                     ::result/data {}}}))))

(defscenario-async "Fetches multiple data sources"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id 1}}
                :data-2 {::data-source/id :source-1
                         ::data-source/params {:id 2}}
                :data-3 {::data-source/id :source-1
                         ::data-source/params {:id 3}}}
               sut/fill
               (sut/select [:data-1 :data-2 :data-3])
               sorted-results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 1}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id 2}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id 3}}]))))

(defscenario-async "Fetches only selected data sources"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id 1}}
                :data-2 {::data-source/id :source-1
                         ::data-source/params {:id 2}}
                :data-3 {::data-source/id :source-1
                         ::data-source/params {:id 3}}}
               sut/fill
               (sut/select [:data-1 :data-3])
               sorted-results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 1}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id 3}}]))))

(defscenario-async "Does not re-fetch sources from same filled prescription"
  (go
    (stub stub-1 [{:id 1}])
    (let [filled (sut/fill {:data-1 {::data-source/id :stub-1
                                     ::data-source/params {:id 1}}}
                           {:params {:config {:id 42}}})]
      (-> filled (sut/select [:data-1]) sorted-results <!)
      (is (= (<! (exhaust (sut/select filled [:data-1])))
             [{:path :data-1
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1}}
               :source {::data-source/id :stub-1
                        ::data-source/params {:id 1}}}]))
      (is (= (-> @stub-1 :calls count) 1)))))
0
(defscenario-async "Fetches immediate dependency of selected data sources"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id 1
                                               :dep ^::data-source/dep [:data-3 :id]}}
                :data-3 {::data-source/id :source-1
                         ::data-source/params {:id 3}}}
               sut/fill
               (sut/select [:data-1])
               results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 3}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id 1 :dep 3}}]))))

(defscenario-async "Fetches all transitive dependencies of selected data sources"
  (go
    (is (=
 (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id 1
                                               :dep ^::data-source/dep [:data-2 :dep]}}
                :data-2 {::data-source/id :source-1
                         ::data-source/params {:id 2
                                               :dep ^::data-source/dep [:data-3 :id]}}
                :data-3 {::data-source/id :source-1
                         ::data-source/params {:id 3}}}
               sut/fill
               (sut/select [:data-1])
               results
               <!)
 [{::result/success? true
   ::result/attempts 1
   ::result/data {:id 3}}
  {::result/success? true
   ::result/attempts 1
   ::result/data {:id 2 :dep 3}}
  {::result/success? true
   ::result/attempts 1 ::result/data {:id 1 :dep 3}}]))))
1
(defscenario-async "Fetches sources with initial params"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id ^::data-source/dep [:config :dep]}}}
               (sut/fill {:params {:config {:dep 42}}})
               (sut/select [:data-1])
               results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 42}}]))))

(defscenario-async "Gives up if requirements cannot be met"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-1
                         ::data-source/params {:id ^::data-source/dep [:config :dep]}}}
               sut/fill
               (sut/select [:data-1])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/id :source-1
                      ::data-source/params {:id ^::data-source/dep [:config :dep]}
                      ::data-source/deps #{:config}}
             :result {::result/success? false
                      ::result/attempts 0}}]))))

(defmethod data-source/fetch-sync :source-2 [{::data-source/keys [params]}]
  (result/success params {:data-1 {::data-source/id :source-1
                                   ::data-source/params {:id "Nested"}}}))

(defscenario-async "Does not fetch nested prescriptions by default"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-2
                         ::data-source/params {:id 42}}}
               sut/fill
               (sut/select [:data-1])
               results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 42}
             ::result/prescriptions {:data-1 {::data-source/id :source-1
                                              ::data-source/params {:id "Nested"}}}}]))))

(defscenario-async "Fetches nested prescriptions from prescription"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-2
                         ::data-source/params {:id 42}
                         ::data-source/nested-prescriptions #{:source-1}}}
               sut/fill
               (sut/select [:data-1])
               results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 42}
             ::result/prescriptions {:data-1 {::data-source/id :source-1
                                              ::data-source/params {:id "Nested"}}}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id "Nested"}}]))))

(defscenario-async "Fetches all nested prescriptions"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-2
                         ::data-source/params {:id 42}}}
               (sut/fill {:walk-nested-prescriptions? true})
               (sut/select [:data-1])
               results
               <!)
           [{::result/success? true
             ::result/attempts 1
             ::result/data {:id 42}
             ::result/prescriptions {:data-1 {::data-source/id :source-1
                                              ::data-source/params {:id "Nested"}}}}
            {::result/success? true
             ::result/attempts 1
             ::result/data {:id "Nested"}}]))))

(defmethod data-source/fetch-sync :source-3 [{::data-source/keys [params]}]
  (result/success params {:nested {::data-source/id :source-2
                                     ::data-source/params {:id "Deeply nested"}}}))

(defscenario-async "Nests prescriptions recursively"
  (go
    (is (= (-> {:data-1 {::data-source/id :source-3
                         ::data-source/params {:id 42}}}
               (sut/fill {:walk-nested-prescriptions? true})
               (sut/select [:data-1])
               exhaust
               <!)
           [{:path :data-1
             :result {:pharmacist.result/attempts 1
                      :pharmacist.result/data {:id 42}
                      :pharmacist.result/prescriptions {:nested {:pharmacist.data-source/id :source-2
                                                                 :pharmacist.data-source/params {:id "Deeply nested"}}}
                      :pharmacist.result/success? true}
             :source {:pharmacist.data-source/deps #{}
                      :pharmacist.data-source/id :source-3
                      :pharmacist.data-source/params {:id 42}}}
            {:path [:data-1 :nested]
             :result {:pharmacist.result/attempts 1
                      :pharmacist.result/data {:id "Deeply nested"}
                      :pharmacist.result/prescriptions {:data-1 {:pharmacist.data-source/id :source-1
                                                                 :pharmacist.data-source/params {:id "Nested"}}}
                      :pharmacist.result/success? true}
             :source {:pharmacist.data-source/deps #{}
                      :pharmacist.data-source/id :source-2
                      :pharmacist.data-source/params {:id "Deeply nested"}}}
            {:path [:data-1 :nested :data-1]
             :result {:pharmacist.result/attempts 1
                      :pharmacist.result/data {:id "Nested"}
                      :pharmacist.result/success? true}
             :source {:pharmacist.data-source/deps #{}
                      :pharmacist.data-source/id :source-1
                      :pharmacist.data-source/params {:id "Nested"}}}]))))

(def stub-2 (atom {}))

(defmethod data-source/fetch-sync :stub-2 [{::data-source/keys [params] :as args}]
  (let [val (-> @stub-2 :returns last)]
    (swap! stub-2 update :returns #(when (seq %) (pop %)))
    (swap! stub-2 update :calls conj args)
    (or val (result/failure))))

(defscenario-async "Retries failing fetch"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})
                  (result/success {:id 13})])
    (is (= (-> {:data-1 {::data-source/id :stub-2
                         ::data-source/params {}
                         ::data-source/retries 1}}
               sut/fill
               (sut/select [:data-1])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/id :stub-2
                      ::data-source/params {}
                      ::data-source/retries 1
                      ::data-source/deps #{}}
             :result {::result/success? false
                      ::result/data {:message "Oops!"}
                      ::result/attempts 1
                      ::result/retrying? true}}
            {:path :data-1
             :source {::data-source/id :stub-2
                      ::data-source/params {}
                      ::data-source/retries 1
                      ::data-source/deps #{}}
             :result {::result/success? true
                      ::result/data {:id 13}
                      ::result/attempts 2}}]))))

(defscenario-async "Does not retry when retries is not set"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})])
    (is (= (-> {:data-1 {::data-source/id :stub-2
                         ::data-source/params {}}}
               sut/fill
               (sut/select [:data-1])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/id :stub-2
                      ::data-source/params {}
                      ::data-source/deps #{}}
             :result {::result/success? false
                      ::result/data {:message "Oops!"}
                      ::result/retrying? false
                      ::result/attempts 1}}]))))

(defscenario-async "Gives up after retrying n times"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})
                  (result/failure {:message "Oops!"})
                  (result/failure {:message "Oops!"})])
    (is (= (-> {:data-1 {::data-source/id :stub-2
                         ::data-source/retries 2
                         ::data-source/params {}}
                :data-2 {::data-source/id :stub-2
                         ::data-source/params ^::data-source/dep [:data-1 :id]}}
               sut/fill
               (sut/select [:data-2])
               results
               <!)
           [{::result/success? false
             ::result/data {:message "Oops!"}
             ::result/attempts 1
             ::result/retrying? true}
            {::result/success? false
             ::result/data {:message "Oops!"}
             ::result/attempts 2
             ::result/retrying? true}
            {::result/success? false
             ::result/data {:message "Oops!"}
             ::result/attempts 3
             ::result/retrying? false}
            {::result/success? false
             ::result/attempts 0}]))))

(defscenario-async "Same filled prescription does not further retry failed source"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})
                  (result/failure {:message "Oops!"})])
    (let [filled (sut/fill {:data-1 {::data-source/id :stub-2
                                     ::data-source/retries 1
                                     ::data-source/params {}}})]
      (<! (exhaust (sut/select filled [:data-1])))
      (is (= (<! (results (sut/select filled [:data-1])))
             [{::result/success? false
               ::result/data {:message "Oops!"}
               ::result/attempts 2
               ::result/retrying? false}])))))

(defmethod data-source/fetch-sync :with-refresh [{::data-source/keys [params]}]
  (if (= 1 (:id params))
    (result/failure {:message "Oops!"} {::result/refresh #{:id}})
    (result/success params)))

(defscenario-async "Refreshes dependency before retrying"
  (go
    (stub stub-1 [{:id 1} {:id 2}])
    (is (= (-> {:data-1 {::data-source/id :stub-1}
                :data-2 {::data-source/id :with-refresh
                         ::data-source/retries 2
                         ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}
               sut/fill
               (sut/select [:data-2])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/deps #{}
                      ::data-source/id :stub-1
                      ::data-source/params {}}
             :result {::result/attempts 1
                      ::result/data {:id 1}
                      ::result/success? true}}
            {:path :data-2
             :source {::data-source/deps #{:data-1}
                      ::data-source/id :with-refresh
                      ::data-source/params {:id 1}
                      ::data-source/retries 2}
             :result {::result/attempts 1
                      ::result/data {:message "Oops!"}
                      ::result/refresh #{:id}
                      ::result/success? false
                      ::result/retrying? true}}
            {:path :data-1
             :source {::data-source/deps #{}
                      ::data-source/id :stub-1
                      ::data-source/params {}}
             :result {::result/attempts 2
                      ::result/data {:id 2}
                      ::result/success? true}}
            {:path :data-2
             :source {::data-source/deps #{:data-1}
                      ::data-source/id :with-refresh
                      ::data-source/params {:id 2}
                      ::data-source/retries 2}
             :result {::result/attempts 2
                      ::result/data {:id 2}
                      ::result/success? true}}]))))

(defmethod data-source/fetch-sync :with-params-refresh [{::data-source/keys [params]}]
  (if (= 1 (:id params))
    (result/failure {:message "Oops!"} {::result/refresh #{::data-source/params}})
    (result/success params)))

(defscenario-async "Refreshes full params dependency"
  (go
    (stub stub-1 [{:id 1} {:id 2}])
    (is (= (-> {:data-1 {::data-source/id :stub-1}
                :data-2 {::data-source/id :with-params-refresh
                         ::data-source/retries 2
                         ::data-source/params ^::data-source/dep [:data-1]}}
               sut/fill
               (sut/select [:data-2])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/deps #{}
                      ::data-source/id :stub-1
                      ::data-source/params {}}
             :result {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 1}}}
            {:path :data-2
             :source {::data-source/deps #{:data-1}
                      ::data-source/id :with-params-refresh
                      ::data-source/params {:id 1}
                      ::data-source/retries 2}
             :result {::result/success? false
                      ::result/attempts 1
                      ::result/data {:message "Oops!"}
                      ::result/refresh #{::data-source/params}
                      ::result/retrying? true}}
            {:path :data-1
             :source {::data-source/deps #{}
                      ::data-source/id :stub-1
                      ::data-source/params {}}
             :result {::result/success? true
                      ::result/attempts 2
                      ::result/data {:id 2}}}
            {:path :data-2
             :source {::data-source/deps #{:data-1}
                      ::data-source/id :with-params-refresh
                      ::data-source/params {:id 2}
                      ::data-source/retries 2}
             :result {::result/success? true
                      ::result/attempts 2
                      ::result/data {:id 2}}}]))))

(defscenario-async "Collects results"
  (go
    (stub stub-1 [{:id 1} {:id 2}])
    (let [result (-> {:data-1 {::data-source/id :stub-1}
                      :data-2 {::data-source/id :with-refresh
                               ::data-source/retries 2
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}
                     sut/fill
                     (sut/select [:data-2])
                     sut/collect
                     <!)]
      (is (= (dissoc result ::result/sources)
             {::result/success? true
              ::result/data {:data-1 {:id 2}
                             :data-2 {:id 2}}}))
      (is (= 4 (count (::result/sources result)))))))

(defscenario-async "Collects partial data with failed overall success indicator"
  (go
    (stub stub-1 [{:id 1} {:id 2}])
    (let [result (-> {:data-1 {::data-source/id :stub-1}
                      :data-2 {::data-source/id :with-refresh
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}
                     sut/fill
                     (sut/select [:data-2])
                     sut/collect
                     <!)]
      (is (= (dissoc result ::result/sources)
             {::result/success? false
              ::result/data {:data-1 {:id 1}}}))
      (is (= 2 (count (::result/sources result)))))))

(defmethod data-source/fetch-sync :squared [{::data-source/keys [params]}]
  (result/success (* (:number params) (:number params))))

(defmethod data-source/fetch-sync :doubled [{::data-source/keys [params]}]
  (result/success (* (:number params) 2)))

(defscenario "Collects final result into map of data, sync"
  (is (= (::result/data
          (<!!
           (sut/collect
            (sut/select
             (sut/fill {:data-1 {::data-source/id :squared
                                 ::data-source/params {:number 3}}
                        :data-2 {::data-source/id :doubled
                                 ::data-source/params {:number ^::data-source/dep [:data-1]}}})
             [:data-2]))))
         {:data-1 9
          :data-2 18})))

(defscenario-async "Retrieves cached result"
  (go
    (let [prescription {::data-source/id :source-1}
          cache (atom {(cache/cache-key prescription)
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 333}}})]
      (is (= (<! (sut/select
                  (sut/fill {:data-1 prescription} (cache/atom-map cache))
                  [:data-1]))
             {:path :data-1
              :source {::data-source/id :source-1
                       ::data-source/params {}}
              :result {::result/success? true
                       ::result/attempts 0
                       ::result/data {:id 333}
                       ::result/cached? true}})))))

(defscenario-async "Ignores cache when refreshing"
  (go
    (stub stub-1 [{:id 666}])
    (let [prescription {::data-source/id :stub-1}
          cache (atom {(cache/cache-key prescription)
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1}}})]
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/id :with-refresh
                           ::data-source/retries 2
                           ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}
                 (sut/fill (cache/atom-map cache))
                 (sut/select [:data-2])
                 exhaust
                 <!)
             [{:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id :stub-1
                        ::data-source/params {}}
               :result {::result/attempts 0
                        ::result/cached? true
                        ::result/data {:id 1}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id :with-refresh
                        ::data-source/params {:id 1}
                        ::data-source/retries 2}
               :result {::result/attempts 1
                        ::result/data {:message "Oops!"}
                        ::result/refresh #{:id}
                        ::result/success? false
                        ::result/retrying? true}}
              {:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id :stub-1
                        ::data-source/params {}}
               :result {::result/attempts 1
                        ::result/data {:id 666}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id :with-refresh
                        ::data-source/params {:id 666}
                        ::data-source/retries 2}
               :result {::result/attempts 2
                        ::result/data {:id 666}
                        ::result/success? true}}])))))

(defscenario-async "Retrieves cached result by resolved params"
  (go
    (let [cache (atom {(cache/cache-key {::data-source/id :source-1
                                         ::data-source/params {:dep 42}})
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 333}}})]
      (is (= (<! (exhaust
                  (sut/select
                   (sut/fill {:data-1 {::data-source/id :source-1
                                       ::data-source/params {:dep ^::data-source/dep [:data-2 :id]}}
                              :data-2 {::data-source/id :source-1
                                       ::data-source/params {:id 42}}}
                             (cache/atom-map cache))
                   [:data-1])))
             [{:path :data-2
               :source {::data-source/id :source-1
                        ::data-source/deps #{}
                        ::data-source/params {:id 42}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 42}}}
              {:path :data-1
               :source {::data-source/id :source-1
                        ::data-source/deps #{:data-2}
                        ::data-source/params {:dep 42}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/data {:id 333}
                        ::result/cached? true}}])))))

(defmethod data-source/cache-deps :cache-1 [_]
  #{:id})

(defscenario-async "Does not fetch dependency when depending param is not part of cache key"
  (go
    (let [prescription {::data-source/id :cache-1
                        ::data-source/params {:id 1
                                              :dep ^::data-source/dep [:data-2 :id]}}
          cache (atom {(cache/cache-key prescription)
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 333}}})]
      (is (= (<! (exhaust
                  (sut/select
                   (sut/fill {:data-1 prescription
                              :data-2 {::data-source/id :source-1
                                       ::data-source/params {:id 42}}}
                             (cache/atom-map cache))
                   [:data-1])))
             [{:path :data-1
               :source {::data-source/id :cache-1
                        ::data-source/params {:id 1 :dep [:data-2 :id]}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:id 333}}}])))))

(defscenario-async "Fetches nested prescriptions from cached result"
  (go
    (let [prescription {::data-source/id :source-2
                        ::data-source/params {:id 42}
                        ::data-source/nested-prescriptions #{:source-1}}
          cache (atom {(cache/cache-key prescription)
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 333}
                        ::result/prescriptions {:data-1 {::data-source/id :source-1
                                                         ::data-source/params {:id "Nested"}}}}})]
      (is (= (-> (sut/fill {:data-1 prescription} (cache/atom-map cache))
                 (sut/select [:data-1])
                 results
                 <!)
             [{::result/success? true
               ::result/attempts 0
               ::result/cached? true
               ::result/data {:id 333}
               ::result/prescriptions {:data-1 {::data-source/id :source-1
                                                ::data-source/params {:id "Nested"}}}}
              {::result/success? true
               ::result/attempts 1
               ::result/data {:id "Nested"}}])))))

(defscenario-async "caches data when successful"
  (go
    (let [cache (atom {})]
      (-> {:data-1 {::data-source/id :source-1
                    ::data-source/params {:id 43}}}
          (sut/fill (cache/atom-map cache))
          (sut/select [:data-1])
          <!)
      (<! (timeout 10))
      (is (= (-> @cache vals first (dissoc ::cache/cached-at))
             {::result/success? true
              ::result/attempts 1
              ::result/data {:id 43}})))))

(defscenario-async "does not cache unsuccessful data"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})])
    (let [cache (atom {})]
      (-> {:data-1 {::data-source/id :stub-2}}
          (sut/fill (cache/atom-map cache))
          (sut/select [:data-1])
          <!)
      (<! (timeout 10))
      (is (= @cache {})))))

(defscenario-async "does not re-cache data"
  (go
    (let [prescription {::data-source/id :cache-1
                        ::data-source/params {:id 1
                                              :dep ^::data-source/dep [:data-2 :id]}}
          cache-key (cache/cache-key prescription)
          cache (atom {cache-key
                       {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 333}
                        ::cache/cached-at 1548695867223}})]
      (<! (exhaust
           (sut/select
            (sut/fill {:data-1 prescription
                       :data-2 {::data-source/id :source-1
                                ::data-source/params {:id 42}}}
                      (cache/atom-map cache))
            [:data-1])))
      (is (= (::cache/cached-at (get @cache cache-key)) 1548695867223)))))

(defmethod data-source/cache-params :cache-2 [_]
  #{[:blob :id] [:blob :name]})

(defscenario-async "Uses cache-params like cache-deps, and to shape cache key"
  (go
    (let [prescription {::data-source/id :cache-2
                        ::data-source/params {:blob ^::data-source/dep [:data-3]}}
          cache (atom {[:cache-2 {[:blob :id] 1337, [:blob :name] "Something"}]
                       {::result/success? true
                        ::result/data {:something "Cached"}}})]
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (<! (exhaust
                  (sut/select
                   (sut/fill {:data-1 prescription
                              :data-3 {::data-source/id :stub-1}}
                             (cache/atom-map cache))
                   [:data-1])))
             [{:path :data-3
               :source {::data-source/id :stub-1
                        ::data-source/deps #{}
                        ::data-source/params {}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-1
               :source {::data-source/id :cache-2
                        ::data-source/params {:blob {:id 1337 :name "Something"}}
                        ::data-source/deps #{:data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))



#_(defscenario-async "Fetches only the cache deps and looks for cached data until loading all deps"
  (go
    (let [prescription {::data-source/id :cache-2
                        ::data-source/params {:blob ^::data-source/dep [:data-3]
                                              :dep ^::data-source/dep [:data-2 :id]}}
          cache (atom {[:cache-2 {[:blob :id] 1337, [:blob :name] "Something"}]
                       {::result/success? true
                        ::result/data {:something "Cached"}}})]
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (<! (exhaust
                  (sut/select
                   (sut/fill {:data-1 prescription
                              :data-2 {::data-source/id :source-1}
                              :data-3 {::data-source/id :source-1
                                       ::data-source/params {:id 42}}}
                             (cache/atom-map cache))
                   [:data-1])))
             [{:path :data-3
               :source {::data-source/id :source-1}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-1
               :source {::data-source/id :cache-1
                        ::data-source/params {:blob {:id 1337 :name "Something"}
                                              :dep [:data-2 :id]}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))



(schema/defschema ::mapped-source :source1/entity
  :source1/some-attr {::schema/source :some-attr}
  :source1/entity {::schema/spec (s/keys :opt [:source1/some-attr])})

(defmethod data-source/fetch-sync ::mapped-source [prescription]
  (result/success {:some-attr "LOL"}))

(defmethod data-source/fetch-sync ::echo [{::data-source/keys [params]}]
  (result/success params))

(defscenario-async "Maps result with defined schema"
  (go
    (is (= (-> (sut/fill {:data {::data-source/id ::mapped-source}})
               (sut/select [:data])
               sut/collect
               <!
               ::result/data)
           {:data {:source1/some-attr "LOL"}}))))

(defscenario-async "Passes coerced data as dependencies"
  (go
    (is (= (-> (sut/fill {:data {::data-source/id ::mapped-source}
                          :data2 {::data-source/id ::echo
                                  ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}})
               (sut/select [:data2])
               sut/collect
               <!
               ::result/data)
           {:data {:source1/some-attr "LOL"}
            :data2 {:input "LOL"}}))))

(defscenario-async "Always gets coerced data from same filled prescription"
  (go
    (let [prescription {:data {::data-source/id ::mapped-source}
                        :data2 {::data-source/id ::echo
                                ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}
          filled (sut/fill prescription)]
      (-> filled (sut/select [:data2] sut/collect <!))
      (is (= (-> filled
                 (sut/select [:data2])
                 sut/collect
                 <!
                 ::result/data)
             {:data {:source1/some-attr "LOL"}
              :data2 {:input "LOL"}})))))

(defscenario-async "Returns coerced cached data"
  (go
    (let [opt (cache/atom-map (atom {}))
          prescription {:data {::data-source/id ::mapped-source}
                        :data2 {::data-source/id ::echo
                                ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}]
      (<! (exhaust (sut/select (sut/fill prescription opt) [:data2])))
      (is (= (-> prescription
                 (sut/fill opt)
                 (sut/select [:data2])
                 sut/collect
                 <!
                 ::result/data)
             {:data {:source1/some-attr "LOL"}
              :data2 {:input "LOL"}})))))

(defscenario-async "Yields no messages when selecting non-existent key"
  (go
    (is (= (-> (sut/fill {:data {::data-source/id :source-1}})
               (sut/select [:non-existent])
               exhaust
               <!)
           []))))

(defscenario-async "Yields no messages when selecting non-existent key with cache"
  (go
    (is (= (-> {:data {::data-source/id ::mapped-source}
                :data2 {::data-source/id ::echo
                        ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}
               (sut/fill (cache/atom-map (atom {})))
               (sut/select [:non-existent])
               exhaust
               <!)
           []))))
