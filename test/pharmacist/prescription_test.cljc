(ns pharmacist.prescription-test
  (:require #?(:clj [clojure.core.async :refer [<! <!! go go-loop timeout close!]]
               :cljs [cljs.core.async :refer [<! go go-loop timeout close!]])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            #?(:clj [clojure.test :refer [is]]
               :cljs [cljs.test :refer [is]])
            #?(:clj [pharmacist.clojure-test-helper :refer [defscenario defscenario-async]])
            [pharmacist.cache :as cache]
            [pharmacist.data-source :as data-source]
            [pharmacist.prescription :as sut]
            [pharmacist.result :as result]
            [pharmacist.schema :as schema]
            [pharmacist.test-helper])
  #?(:cljs (:require-macros [pharmacist.cljs-test-helper :refer [defscenario defscenario-async]])))

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

(defn stub [ref returns]
  (reset! ref {:returns (into [] (reverse returns)) :calls []}))

(def stub-1 (atom {}))
(def stub-2 (atom {}))

(defn echo-params [{::data-source/keys [params]}]
  (result/success params))

(defn echo-params-async [{::data-source/keys [params]}]
  (go (result/success params)))

(defn echo-stub-1 [{::data-source/keys [params] :as source}]
  (let [val (-> @stub-1 :returns last)]
    (swap! stub-1 update :returns pop)
    (swap! stub-1 update :calls conj source)
    (result/success val)))

(defn echo-stub-2 [{::data-source/keys [params] :as args}]
  (let [val (-> @stub-2 :returns last)]
    (swap! stub-2 update :returns #(when (seq %) (pop %)))
    (swap! stub-2 update :calls conj args)
    (or val (result/failure))))

(defn refresh-id-when-1 [{::data-source/keys [params]}]
  (if (= 1 (:id params))
    (result/failure {:message "Oops!"} {::result/refresh #{:id}})
    (result/success params)))

(defn refresh-params-when-id-1 [{::data-source/keys [params]}]
  (if (= 1 (:id params))
    (result/failure {:message "Oops!"} {::result/refresh #{::data-source/params}})
    (result/success params)))

(defn squared [{::data-source/keys [params]}]
  (result/success (* (:number params) (:number params))))

(defn doubled [{::data-source/keys [params]}]
  (result/success (* (:number params) 2)))

(defn get-ids [{::data-source/keys [params]}]
  (result/success (:ids params)))

(defn now []
  #?(:cljs (.getTime (js/Date.))
     :clj (.toEpochMilli (java.time.Instant/now))))

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

(defscenario "Adds dependencies on collection items to source depending on collection"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:other ^::data-source/dep [:data-1]}}
           [:data-1 0] {::data-source/id :data/three
                        ::data-source/member-of :data-1}}
          [:data-2])
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{[:data-1 0]}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:other [:data-1]}
                   ::data-source/deps #{:data-1}}
          [:data-1 0] {::data-source/id :data/three
                       ::data-source/member-of :data-1
                       ::data-source/deps #{}}})))

(defscenario "Adds transitive dependencies from collection items to source depending on collection"
  (is (= (sut/resolve-deps
          {:data-1 {::data-source/id :data/one}
           :data-2 {::data-source/id :data/two
                    ::data-source/params {:other ^::data-source/dep [:data-1]}}
           :data-3 {::data-source/id :data/one}
           [:data-1 0] {::data-source/id :data/three
                        ::data-source/params {:id ^::data-source/dep [:data-3]}
                        ::data-source/member-of :data-1}}
          [:data-2])
         {:data-1 {::data-source/id :data/one
                   ::data-source/deps #{[:data-1 0]}}
          :data-2 {::data-source/id :data/two
                   ::data-source/params {:other [:data-1]}
                   ::data-source/deps #{:data-1}}
          :data-3 {::data-source/id :data/one
                   ::data-source/deps #{}}
          [:data-1 0] {::data-source/id :data/three
                       ::data-source/member-of :data-1
                       ::data-source/deps #{:data-3}
                       ::data-source/params {:id [:data-3]}}})))

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

(defscenario-async "Fetches single data source"
  (go
    (is (= (<! (-> (sut/fill {:data-1 {::data-source/id :some-func
                                       ::data-source/fn #'echo-params}})
                   (sut/select [:data-1])))
           {:path :data-1
            :source {::data-source/id :some-func
                     ::data-source/params {}
                     ::data-source/deps #{}}
            :result {::result/success? true
                     ::result/attempts 1
                     ::result/data {}}}))))

(defscenario-async "Infers source id from function var"
  (go
    (is (= (<! (-> (sut/fill {:data-1 {::data-source/fn #'echo-params}})
                   (sut/select [:data-1])))
           {:path :data-1
            :source {::data-source/id ::echo-params
                     ::data-source/params {}
                     ::data-source/deps #{}}
            :result {::result/success? true
                     ::result/attempts 1
                     ::result/data {}}}))))

(defscenario-async "Infers source id from function value"
  (go
    (is (= (<! (-> (sut/fill {:data-1 {::data-source/fn echo-params}})
                   (sut/select [:data-1])))
           {:path :data-1
            :source {::data-source/id ::echo-params
                     ::data-source/params {}
                     ::data-source/deps #{}}
            :result {::result/success? true
                     ::result/attempts 1
                     ::result/data {}}}))))

(defscenario-async "Defaults id to demunged keyword"
  (go
    (is (re-find #?(:clj #"pharmacist.prescription-test\$fn"
                    :cljs #".{8}-.{4}-.{4}-.{4}-.{12}")
                 (-> (sut/fill {:data-1 {::data-source/fn (fn [source])}})
                     (sut/select [:data-1])
                     <!
                     :source
                     ::data-source/id
                     str)))))

(defscenario-async "Infers source id from async function value"
  (go
    (is (= (<! (-> (sut/fill {:data-1 {::data-source/async-fn echo-params-async}})
                   (sut/select [:data-1])))
           {:path :data-1
            :source {::data-source/id ::echo-params-async
                     ::data-source/params {}
                     ::data-source/deps #{}}
            :result {::result/success? true
                     ::result/attempts 1
                     ::result/data {}}}))))

(defscenario-async "Fetches multiple data sources"
  (go
    (is (= (-> {:data-1 {::data-source/fn #'echo-params
                         ::data-source/params {:id 1}}
                :data-2 {::data-source/fn #'echo-params
                         ::data-source/params {:id 2}}
                :data-3 {::data-source/fn #'echo-params
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
    (is (= (-> {:data-1 {::data-source/fn #'echo-params
                         ::data-source/params {:id 1}}
                :data-2 {::data-source/fn #'echo-params
                         ::data-source/params {:id 2}}
                :data-3 {::data-source/fn #'echo-params
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
    (let [filled (sut/fill {:data-1 {::data-source/fn #'echo-stub-1
                                     ::data-source/params {:id 1}}}
                           {:params {:config {:id 42}}})]
      (-> filled (sut/select [:data-1]) sorted-results <!)
      (is (= (<! (exhaust (sut/select filled [:data-1])))
             [{:path :data-1
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1}}
               :source {::data-source/id ::echo-stub-1
                        ::data-source/params {:id 1}
                        ::data-source/deps #{}}}]))
      (is (= (-> @stub-1 :calls count) 1)))))

(defscenario-async "Fetches immediate dependency of selected data sources"
  (let [prescription {:data-1 {::data-source/fn #'echo-params
                               ::data-source/params {:id 1
                                                     :dep ^::data-source/dep [:data-3 :id]}}
                      :data-3 {::data-source/fn #'echo-params
                               ::data-source/params {:id 3}}}]
    (go
      (is (= (<! (results (sut/select (sut/fill prescription) [:data-1])))
             [{::result/success? true
               ::result/attempts 1
               ::result/data {:id 3}}
              {::result/success? true
               ::result/attempts 1
               ::result/data {:id 1 :dep 3}}])))))

(defscenario-async "Fetches all transitive dependencies of selected data sources"
  (let [prescription {:data-1 {::data-source/fn #'echo-params
                               ::data-source/params {:id 1
                                                     :dep ^::data-source/dep [:data-2 :dep]}}
                      :data-2 {::data-source/fn #'echo-params
                               ::data-source/params {:id 2
                                                     :dep ^::data-source/dep [:data-3 :id]}}
                      :data-3 {::data-source/fn #'echo-params
                               ::data-source/params {:id 3}}}]
    (go
      (is (= (<! (results (sut/select (sut/fill prescription) [:data-1])))
             [{::result/success? true
               ::result/attempts 1
               ::result/data {:id 3}}
              {::result/success? true
               ::result/attempts 1
               ::result/data {:id 2 :dep 3}}
              {::result/success? true
               ::result/attempts 1 ::result/data {:id 1 :dep 3}}])))))

(defscenario-async "Fetches sources with initial params"
  (let [prescription {:data-1 {::data-source/fn #'echo-params
                               ::data-source/params {:id ^::data-source/dep [:config :dep]}}}]
    (go
      (is (= (-> prescription
                 (sut/fill {:params {:config {:dep 42}}})
                 (sut/select [:data-1])
                 results
                 <!)
             [{::result/success? true
               ::result/attempts 1
               ::result/data {:id 42}}])))))

(defscenario-async "Gives up if requirements cannot be met"
  (let [prescription {:data-1 {::data-source/fn #'echo-params
                               ::data-source/params {:id ^::data-source/dep [:config :dep]}}}]
    (go
      (is (= (<! (exhaust (sut/select (sut/fill prescription) [:data-1])))
             [{:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:id ^::data-source/dep [:config :dep]}
                        ::data-source/deps #{:config}}
               :result {::result/success? false
                        ::result/attempts 0}}])))))

(defscenario-async "Retries failing fetch"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-2
                               ::data-source/params {}
                               ::data-source/retries 1}}]
    (stub stub-2 [(result/failure {:message "Oops!"})
                  (result/success {:id 13})])
    (go
      (is (= (<! (exhaust (sut/select (sut/fill prescription) [:data-1])))
             [{:path :data-1
               :source {::data-source/id ::echo-stub-2
                        ::data-source/params {}
                        ::data-source/retries 1
                        ::data-source/deps #{}}
               :result {::result/success? false
                        ::result/data {:message "Oops!"}
                        ::result/attempts 1
                        ::result/retrying? true
                        ::result/retry-delay 0}}
              {:path :data-1
               :source {::data-source/id ::echo-stub-2
                        ::data-source/params {}
                        ::data-source/retries 1
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/data {:id 13}
                        ::result/attempts 2}}])))))

(defscenario-async "Does not retry when retries is not set"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})])
    (is (= (-> {:data-1 {::data-source/fn #'echo-stub-2
                         ::data-source/params {}}}
               sut/fill
               (sut/select [:data-1])
               exhaust
               <!)
           [{:path :data-1
             :source {::data-source/id ::echo-stub-2
                      ::data-source/params {}
                      ::data-source/deps #{}}
             :result {::result/success? false
                      ::result/data {:message "Oops!"}
                      ::result/retrying? false
                      ::result/attempts 1}}]))))

(defscenario-async "Gives up after retrying n times"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-2
                               ::data-source/retries 2
                               ::data-source/params {}}
                      :data-2 {::data-source/fn #'echo-stub-2
                               ::data-source/params ^::data-source/dep [:data-1 :id]}}]
    (go
      (stub stub-2 [(result/failure {:message "Oops!"})
                    (result/failure {:message "Oops!"})
                    (result/failure {:message "Oops!"})])
      (is (= (<! (results (sut/select (sut/fill prescription) [:data-2])))
             [{::result/success? false
               ::result/data {:message "Oops!"}
               ::result/attempts 1
               ::result/retrying? true
               ::result/retry-delay 0}
              {::result/success? false
               ::result/data {:message "Oops!"}
               ::result/attempts 2
               ::result/retrying? true
               ::result/retry-delay 0}
              {::result/success? false
               ::result/data {:message "Oops!"}
               ::result/attempts 3
               ::result/retrying? false}
              {::result/success? false
               ::result/attempts 0}])))))

(defscenario-async "Same filled prescription does not further retry failed source"
  (go
    (stub stub-2 [(result/failure {:message "Oops!"})
                  (result/failure {:message "Oops!"})])
    (let [filled (sut/fill {:data-1 {::data-source/fn #'echo-stub-2
                                     ::data-source/retries 1
                                     ::data-source/params {}}})]
      (<! (exhaust (sut/select filled [:data-1])))
      (is (= (<! (results (sut/select filled [:data-1])))
             [{::result/success? false
               ::result/data {:message "Oops!"}
               ::result/attempts 2
               ::result/retrying? false}])))))

(defscenario-async "Refreshes dependency before retrying"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-1}
                      :data-2 {::data-source/fn #'refresh-id-when-1
                               ::data-source/retries 2
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}]
    (go
      (stub stub-1 [{:id 1} {:id 2}])
      (is (= (<! (exhaust (sut/select (sut/fill prescription) [:data-2])))
             [{:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/attempts 1
                        ::result/data {:id 1}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-id-when-1
                        ::data-source/params {:id 1}
                        ::data-source/retries 2}
               :result {::result/attempts 1
                        ::result/data {:message "Oops!"}
                        ::result/refresh #{:id}
                        ::result/success? false
                        ::result/retrying? true
                        ::result/retry-delay 0}}
              {:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/attempts 2
                        ::result/data {:id 2}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-id-when-1
                        ::data-source/params {:id 2}
                        ::data-source/retries 2}
               :result {::result/attempts 2
                        ::result/data {:id 2}
                        ::result/success? true}}])))))

(defscenario-async "Refreshes full params dependency"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-1}
                      :data-2 {::data-source/fn #'refresh-params-when-id-1
                               ::data-source/retries 2
                               ::data-source/params ^::data-source/dep [:data-1]}}]
    (go
      (stub stub-1 [{:id 1} {:id 2}])
      (is (= (<! (exhaust (sut/select (sut/fill prescription) [:data-2])))
             [{:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1}}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-params-when-id-1
                        ::data-source/params {:id 1}
                        ::data-source/retries 2}
               :result {::result/success? false
                        ::result/attempts 1
                        ::result/data {:message "Oops!"}
                        ::result/refresh #{::data-source/params}
                        ::result/retrying? true
                        ::result/retry-delay 0}}
              {:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/success? true
                        ::result/attempts 2
                        ::result/data {:id 2}}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-params-when-id-1
                        ::data-source/params {:id 2}
                        ::data-source/retries 2}
               :result {::result/success? true
                        ::result/attempts 2
                        ::result/data {:id 2}}}])))))

(defn echo-stub-2-with-time [source]
  (assoc (echo-stub-2 source) :attempted-at (now)))

(defscenario-async "Retries with timeouts"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-2-with-time
                               ::data-source/retries 2
                               ::data-source/retry-delays [10 20]
                               ::data-source/params {}}}]
    (go
      (stub stub-2 [(result/failure {:message "Oops!"})
                    (result/failure {:message "Oops!"})
                    (result/success {:data "Ok"})])
      (let [results (<! (results (sut/select (sut/fill prescription) [:data-1])))
            [initial first-retry second-retry] (map :attempted-at results)]
        (is (<= 10 (- first-retry initial) 50))
        (is (<= 20 (- second-retry first-retry) 50))))))

(defscenario-async "Retries with timeouts on result"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-2-with-time
                               ::data-source/retries 2
                               ::data-source/retry-delays [250]
                               ::data-source/params {}}}]
    (go
      (stub stub-2 [(assoc (result/failure {:message "Oops!"}) ::result/retry-delay 20)
                    (assoc (result/failure {:message "Oops!"}) ::result/retry-delay 10)
                    (result/success {:data "Ok"})])
      (let [results (<! (results (sut/select (sut/fill prescription) [:data-1])))
            [initial first-retry second-retry] (map :attempted-at results)]
        (is (<= 20 (- first-retry initial) 50))
        (is (<= 10 (- second-retry first-retry) 50))))))

(defscenario-async "Collects results"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-1}
                      :data-2 {::data-source/fn #'refresh-id-when-1
                               ::data-source/retries 2
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}]
    (go
      (stub stub-1 [{:id 1} {:id 2}])
      (let [result (<! (sut/collect (sut/select (sut/fill prescription) [:data-2])))]
        (is (= (dissoc result ::result/sources)
               {::result/success? true
                ::result/data {:data-1 {:id 2}
                               :data-2 {:id 2}}}))
        (is (= 4 (count (::result/sources result))))))))

(defscenario-async "Collects partial data with failed overall success indicator"
  (let [prescription {:data-1 {::data-source/fn #'echo-stub-1}
                      :data-2 {::data-source/fn #'refresh-id-when-1
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}]
    (go
      (stub stub-1 [{:id 1} {:id 2}])
      (let [result (<! (sut/collect (sut/select (sut/fill prescription) [:data-2])))]
        (is (= (dissoc result ::result/sources)
               {::result/success? false
                ::result/data {:data-1 {:id 1}}}))
        (is (= 2 (count (::result/sources result))))))))

#?(:clj
   (defscenario "Collects final result into map of data, sync"
     (is (= (::result/data
             (<!!
              (sut/collect
               (sut/select
                (sut/fill {:data-1 {::data-source/fn #'squared
                                    ::data-source/params {:number 3}}
                           :data-2 {::data-source/fn #'doubled
                                    ::data-source/params {:number ^::data-source/dep [:data-1]}}})
                [:data-2]))))
            {:data-1 9
             :data-2 18}))))

(defscenario-async "Retrieves cached result"
  (let [prescription {::data-source/fn #'echo-params}
        cache (atom {(data-source/cache-key prescription)
                     {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 333}}})]
    (go
      (is (= (<! (sut/select
                  (sut/fill {:data-1 prescription} {:cache (cache/atom-map cache)})
                  [:data-1]))
             {:path :data-1
              :source {::data-source/id ::echo-params
                       ::data-source/params {}
                       ::data-source/deps #{}}
              :result {::result/success? true
                       ::result/attempts 0
                       ::result/data {:id 333}
                       ::result/cached? true}})))))

(defscenario-async "Ignores cache when refreshing"
  (let [source {::data-source/fn #'echo-stub-1}
        prescription {:data-1 source
                      :data-2 {::data-source/fn #'refresh-id-when-1
                               ::data-source/retries 2
                               ::data-source/params {:id ^::data-source/dep [:data-1 :id]}}}
        cache (atom {(data-source/cache-key source)
                     {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 1}}})]
    (stub stub-1 [{:id 666}])
    (go
      (is (= (-> prescription
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-2])
                 exhaust
                 <!)
             [{:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/attempts 0
                        ::result/cached? true
                        ::result/data {:id 1}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-id-when-1
                        ::data-source/params {:id 1}
                        ::data-source/retries 2}
               :result {::result/attempts 1
                        ::result/data {:message "Oops!"}
                        ::result/refresh #{:id}
                        ::result/success? false
                        ::result/retrying? true
                        ::result/retry-delay 0}}
              {:path :data-1
               :source {::data-source/deps #{}
                        ::data-source/id ::echo-stub-1
                        ::data-source/params {}}
               :result {::result/attempts 1
                        ::result/data {:id 666}
                        ::result/success? true}}
              {:path :data-2
               :source {::data-source/deps #{:data-1}
                        ::data-source/id ::refresh-id-when-1
                        ::data-source/params {:id 666}
                        ::data-source/retries 2}
               :result {::result/attempts 2
                        ::result/data {:id 666}
                        ::result/success? true}}])))))

(defscenario-async "Retrieves cached result by resolved params"
  (let [cache (atom {(data-source/cache-key {::data-source/fn #'echo-params
                                       ::data-source/params {:dep 42}})
                     {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 333}}})
        prescription {:data-1 {::data-source/fn #'echo-params
                               ::data-source/params {:dep ^::data-source/dep [:data-2 :id]}}
                      :data-2 {::data-source/fn #'echo-params
                               ::data-source/params {:id 42}}}]
    (go
      (is (= (<! (exhaust (sut/select (sut/fill prescription {:cache (cache/atom-map cache)}) [:data-1])))
             [{:path :data-2
               :source {::data-source/id ::echo-params
                        ::data-source/deps #{}
                        ::data-source/params {:id 42}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 42}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/deps #{:data-2}
                        ::data-source/params {:dep 42}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/data {:id 333}
                        ::result/cached? true}}])))))

(defscenario-async "Does not fetch dependency when depending param is not part of cache key"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-deps #{:id}
                      ::data-source/params {:id 1
                                            :dep ^::data-source/dep [:data-2 :id]}}
        cache (atom {(data-source/cache-key prescription)
                     {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 333}}})]
    (go
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/fn #'echo-params
                           ::data-source/params {:id 42}}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:id 1 :dep [:data-2 :id]}
                        ::data-source/deps #{:data-2}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:id 333}}}])))))

#?(:clj
   (defscenario-async "caches data when successful"
     (go
       (let [cache (atom {})]
         (-> {:data-1 {::data-source/fn #'echo-params
                       ::data-source/params {:id 43}}}
             (sut/fill {:cache (cache/atom-map cache)})
             (sut/select [:data-1])
             <!)
         (<! (timeout 10))
         (is (= (-> @cache vals first (dissoc ::cache/cached-at))
                {::result/success? true
                 ::result/attempts 1
                 ::result/data {:id 43}}))))))

#?(:clj
   (defscenario-async "does not cache unsuccessful data"
     (go
       (stub stub-2 [(result/failure {:message "Oops!"})])
       (let [cache (atom {})]
         (-> {:data-1 {::data-source/fn #'echo-stub-2}}
             (sut/fill {:cache (cache/atom-map cache)})
             (sut/select [:data-1])
             <!)
         (<! (timeout 10))
         (is (= @cache {}))))))

(defscenario-async "does not re-cache data"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-deps #{:id}
                      ::data-source/params {:id 1
                                            :dep ^::data-source/dep [:data-2 :id]}}
        cache-key (data-source/cache-key prescription)
        cache (atom {cache-key
                     {::result/success? true
                      ::result/attempts 1
                      ::result/data {:id 333}
                      ::cache/cached-at 1548695867223}})]
    (go
      (<! (-> {:data-1 prescription
               :data-2 {::data-source/id ::echo-params
                        ::data-source/params {:id 42}}}
              (sut/fill {:cache (cache/atom-map cache)})
              (sut/select [:data-1])
              exhaust))
      (is (= (::cache/cached-at (get @cache cache-key)) 1548695867223)))))

(defscenario-async "Uses cache-params like cache-deps, and to shape cache key"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-params #{[:blob :id] [:blob :name]}
                      ::data-source/params {:blob ^::data-source/dep [:data-3]}}
        cache (atom {[::echo-params {[:blob :id] 1337, [:blob :name] "Something"}]
                     {::result/success? true
                      ::result/data {:something "Cached"}}})]
    (go
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (-> {:data-1 prescription
                  :data-3 {::data-source/fn #'echo-stub-1}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-3
               :source {::data-source/id ::echo-stub-1
                        ::data-source/deps #{}
                        ::data-source/params {}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:blob {:id 1337 :name "Something"}}
                        ::data-source/deps #{:data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))

(defscenario-async "Fetches only the cache deps and looks for cached data until loading all deps"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-params #{[:blob :id] [:blob :name]}
                      ::data-source/params {:blob ^::data-source/dep [:data-3]
                                            :dep ^::data-source/dep [:data-2 :id]}}
        cache (atom {[::echo-params {[:blob :id] 1337, [:blob :name] "Something"}]
                     {::result/success? true
                      ::result/data {:something "Cached"}}})]
    (go
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/fn #'echo-params}
                  :data-3 {::data-source/fn #'echo-stub-1
                           ::data-source/params {:id 42}}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-3
               :source {::data-source/id ::echo-stub-1
                        ::data-source/params {:id 42}
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:blob {:id 1337 :name "Something"}
                                              :dep [:data-2 :id]}
                        ::data-source/deps #{:data-2 :data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))

(defscenario-async "Goes deep before it goes wide"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-params #{[:blob :id] [:blob :name]}
                      ::data-source/params {:blob ^::data-source/dep [:data-3]
                                            :dep ^::data-source/dep [:data-2 :id]}}
        data-3 {::data-source/fn #'echo-params
                ::data-source/params {:id ^::data-source/dep [:data-4 :id]
                                      :name "New source"}}
        cache (atom {[::echo-params {[:blob :id] 1337, [:blob :name] "New source"}]
                     {::result/success? true
                      ::result/data {:something "Cached"}}})]
    (go
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/fn #'echo-params}
                  :data-3 data-3
                  :data-4 {::data-source/fn #'echo-stub-1
                           ::data-source/params {:id 42}}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-4
               :source {::data-source/id ::echo-stub-1
                        ::data-source/params {:id 42}
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-3
               :source {::data-source/id ::echo-params
                        ::data-source/params {:id 1337 :name "New source"}
                        ::data-source/deps #{:data-4}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "New source"}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:blob {:id 1337 :name "New source"}
                                              :dep [:data-2 :id]}
                        ::data-source/deps #{:data-2 :data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))

(defscenario-async "Goes deep and resolves all dependencies to fetch uncached item"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-params #{[:blob :id] [:blob :name]}
                      ::data-source/params {:blob ^::data-source/dep [:data-3]
                                            :dep ^::data-source/dep [:data-2 :id]}}
        data-3 {::data-source/fn #'echo-params
                ::data-source/cache-params #{[:id]}
                ::data-source/params {:id ^::data-source/dep [:data-4 :id]
                                      :name ^::data-source/dep [:data-5 :name]}}
        cache (atom {[::echo-params {[:blob :id] 1337, [:blob :name] "Deep deps"}]
                     {::result/success? true
                      ::result/data {:something "Cached"}}})]
    (go
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/fn #'echo-params}
                  :data-3 data-3
                  :data-4 {::data-source/fn #'echo-stub-1
                           ::data-source/params {:id 42}}
                  :data-5 {::data-source/fn #'echo-params
                           ::data-source/params {:name "Deep deps"}}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-4
               :source {::data-source/id ::echo-stub-1
                        ::data-source/params {:id 42}
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-5
               :source {::data-source/id ::echo-params
                        ::data-source/params {:name "Deep deps"}
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:name "Deep deps"}}}
              {:path :data-3
               :source {::data-source/id ::echo-params
                        ::data-source/params {:id 1337 :name "Deep deps"}
                        ::data-source/deps #{:data-4 :data-5}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Deep deps"}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:blob {:id 1337 :name "Deep deps"}
                                              :dep [:data-2 :id]}
                        ::data-source/deps #{:data-2 :data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))

(defscenario-async "Handles full params deps in cache resolution"
  (let [prescription {::data-source/fn #'echo-params
                      ::data-source/cache-params #{[:blob :id] [:blob :name]}
                      ::data-source/params {:blob ^::data-source/dep [:data-3]
                                            :dep ^::data-source/dep [:data-2 :id]}}
        data-3 {::data-source/fn #'echo-params
                ::data-source/params ^::data-source/dep [:data-4]}
        cache (atom {[::echo-params {[:blob :id] 1337, [:blob :name] "Something"}]
                     {::result/success? true
                      ::result/data {:something "Cached"}}})]
    (go
      (stub stub-1 [{:id 1337 :name "Something"}])
      (is (= (-> {:data-1 prescription
                  :data-2 {::data-source/fn #'echo-params}
                  :data-3 data-3
                  :data-4 {::data-source/fn #'echo-stub-1
                           ::data-source/params {:id 42}}}
                 (sut/fill {:cache (cache/atom-map cache)})
                 (sut/select [:data-1])
                 exhaust
                 <!)
             [{:path :data-4
               :source {::data-source/id ::echo-stub-1
                        ::data-source/params {:id 42}
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-3
               :source {::data-source/id ::echo-params
                        ::data-source/params {:id 1337 :name "Something"}
                        ::data-source/deps #{:data-4}}
               :result {::result/success? true
                        ::result/attempts 1
                        ::result/data {:id 1337 :name "Something"}}}
              {:path :data-1
               :source {::data-source/id ::echo-params
                        ::data-source/params {:blob {:id 1337 :name "Something"}
                                              :dep [:data-2 :id]}
                        ::data-source/deps #{:data-2 :data-3}}
               :result {::result/success? true
                        ::result/attempts 0
                        ::result/cached? true
                        ::result/data {:something "Cached"}}}])))))

(defscenario-async "Maps result with defined schema"
  (go
    (is (= (-> {:data {::data-source/fn #'echo-params
                       ::data-source/params {:some-attr "LOL"}
                       ::data-source/schema
                       {:source1/some-attr {::schema/source :some-attr}
                        ::schema/entity {::schema/spec (s/keys :opt [:source1/some-attr])}}}}
               sut/fill
               (sut/select [:data])
               sut/collect
               <!
               ::result/data)
           {:data {:source1/some-attr "LOL"}}))))

(defscenario-async "Includes raw data when there is a schema"
  (go
    (is (= (-> {:data {::data-source/fn #'echo-params
                       ::data-source/params {:some-attr "LOL"}
                       ::data-source/schema
                       {:source1/some-attr {::schema/source :some-attr}
                        ::schema/entity {::schema/spec (s/keys :opt [:source1/some-attr])}}}}
               sut/fill
               (sut/select [:data])
               <!
               :result
               ::result/raw-data)
           {:some-attr "LOL"}))))

(defscenario-async "Passes coerced data as dependencies"
  (let [prescription {:data {::data-source/fn #'echo-params
                             ::data-source/params {:some-attr "LOL"}
                             ::data-source/schema
                             {:source1/some-attr {::schema/source :some-attr}
                              ::schema/entity {::schema/spec (s/keys :opt [:source1/some-attr])}}}
                      :data2 {::data-source/fn #'echo-params
                              ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}]
    (go
      (is (= (-> (sut/fill prescription)
                 (sut/select [:data2])
                 sut/collect
                 <!
                 ::result/data)
             {:data {:source1/some-attr "LOL"}
              :data2 {:input "LOL"}})))))

(defscenario-async "Always gets coerced data from same filled prescription"
  (let [prescription {:data {::data-source/fn #'echo-params
                             ::data-source/params {:some-attr "LOL"}
                             ::data-source/schema
                             {:source1/some-attr {::schema/source :some-attr}
                              ::schema/entity {::schema/spec (s/keys :opt [:source1/some-attr])}}}
                      :data2 {::data-source/fn #'echo-params
                              ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}
        filled (sut/fill prescription)]
    (go
      (-> filled (sut/select [:data2]) sut/collect <!)
      (is (= (-> filled
                 (sut/select [:data2 :data])
                 sut/collect
                 <!
                 ::result/data)
             {:data {:source1/some-attr "LOL"}
              :data2 {:input "LOL"}})))))

(defscenario-async "Returns coerced cached data"
  (let [opt {:cache (cache/atom-map (atom {}))}
        prescription {:data {::data-source/fn #'echo-params
                             ::data-source/params {:some-attr "LOL"}
                             ::data-source/schema
                             {:source1/some-attr {::schema/source :some-attr}
                              ::schema/entity {::schema/spec (s/keys :opt [:source1/some-attr])}}}
                      :data2 {::data-source/fn #'echo-params
                              ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}]
    (go
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
    (is (= (-> (sut/fill {:data {::data-source/fn #'echo-params}})
               (sut/select [:non-existent])
               exhaust
               <!)
           []))))

(defscenario-async "Yields no messages when selecting non-existent key with cache"
  (let [prescription {:data {::data-source/fn #'echo-params}
                      :data2 {::data-source/fn #'echo-params
                              ::data-source/params {:input ^::data-source/dep [:data :source1/some-attr]}}}]
    (go
      (is (= (-> prescription
                 (sut/fill {:cache (cache/atom-map (atom {}))})
                 (sut/select [:non-existent])
                 exhaust
                 <!)
             [])))))

(defscenario-async "Loads empty collection"
  (go
    (is (= (into #{}
                 (-> {:facility {::data-source/fn #'echo-params
                                 ::data-source/params {:some "facility"}}
                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids []}}}
                     sut/fill
                     (sut/select [:facilities])
                     exhaust
                     <!))
           #{{:path :facilities
               :source {::data-source/id ::get-ids
                        ::data-source/params {:ids []}
                        ::data-source/coll-of :facility
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/data []
                        ::result/attempts 1}}}))))

(defscenario-async "Loads nested sources from collection"
  (go
    (is (= (into #{}
                 (-> {:facility {::data-source/fn #'echo-params
                                 ::data-source/params {:some "facility"}}
                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids (seq [{:facility-id 1}
                                                                    {:facility-id 2}])}}}
                     sut/fill
                     (sut/select [:facilities])
                     exhaust
                     <!))
           #{{:path :facilities
              :source {::data-source/id ::get-ids
                       ::data-source/params {:ids [{:facility-id 1}
                                                   {:facility-id 2}]}
                       ::data-source/coll-of :facility
                       ::data-source/deps #{}}
              :result {::result/success? true
                       ::result/partial? true
                       ::result/data [{:facility-id 1}
                                      {:facility-id 2}]
                       ::result/attempts 1}}
             {:path [:facilities 0]
              :source {::data-source/id ::echo-params
                       ::data-source/params {:some "facility"
                                             :facility-id 1}
                       ::data-source/deps #{}
                       ::data-source/member-of :facilities}
              :result {::result/success? true
                       ::result/data {:some "facility"
                                      :facility-id 1}
                       ::result/attempts 1}}
             {:path [:facilities 1]
              :source {::data-source/id ::echo-params
                       ::data-source/params {:some "facility"
                                             :facility-id 2}
                       ::data-source/deps #{}
                       ::data-source/member-of :facilities}
              :result {::result/success? true
                       ::result/data {:some "facility"
                                      :facility-id 2}
                       ::result/attempts 1}}
             {:path :facilities
              :source {::data-source/id ::get-ids
                       ::data-source/params {:ids [{:facility-id 1}
                                                   {:facility-id 2}]}
                       ::data-source/coll-of :facility
                       ::data-source/deps #{[:facilities 0] [:facilities 1]}}
              :result {::result/success? true
                       ::result/data [{:facility-id 1 :some "facility"}
                                      {:facility-id 2 :some "facility"}]
                       ::result/attempts 1}}}))))

(defscenario-async "Loads nested sources from map collection"
  (go
    (is (= (into #{}
                 (-> {:facility {::data-source/fn #'echo-params
                                 ::data-source/params {:some "facility"}}

                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids {:first {:facility-id 1}
                                                               :second {:facility-id 2}}}}}
                     sut/fill
                     (sut/select [:facilities])
                     exhaust
                     <!))
           #{{:path :facilities
               :source {::data-source/id ::get-ids
                        ::data-source/params {:ids {:first {:facility-id 1}
                                                    :second {:facility-id 2}}}
                        ::data-source/coll-of :facility
                        ::data-source/deps #{}}
               :result {::result/success? true
                        ::result/partial? true
                        ::result/data {:first {:facility-id 1}
                                       :second {:facility-id 2}}
                        ::result/attempts 1}}
             {:path [:facilities :first]
              :source {::data-source/id ::echo-params
                       ::data-source/params {:some "facility"
                                             :facility-id 1}
                       ::data-source/deps #{}
                       ::data-source/member-of :facilities}
              :result {::result/success? true
                       ::result/data {:some "facility"
                                      :facility-id 1}
                       ::result/attempts 1}}
             {:path [:facilities :second]
              :source {::data-source/id ::echo-params
                       ::data-source/params {:some "facility"
                                             :facility-id 2}
                       ::data-source/deps #{}
                       ::data-source/member-of :facilities}
              :result {::result/success? true
                       ::result/data {:some "facility"
                                      :facility-id 2}
                       ::result/attempts 1}}
             {:path :facilities
              :source {::data-source/id ::get-ids
                       ::data-source/params {:ids {:first {:facility-id 1}
                                                   :second {:facility-id 2}}}
                       ::data-source/coll-of :facility
                       ::data-source/deps #{[:facilities :first] [:facilities :second]}}
              :result {::result/success? true
                       ::result/data {:first {:facility-id 1 :some "facility"}
                                      :second {:facility-id 2 :some "facility"}}
                       ::result/attempts 1}}}))))

(defscenario-async "Loads nested sources shadowing dependencies"
  (let [prescription {:user {::data-source/fn #'echo-params
                             ::data-source/params {:id 13}}

                      :facility-id {::data-source/fn #'echo-params
                                    ::data-source/params {:id 1337}}

                      :facility {::data-source/fn #'echo-params
                                 ::data-source/params {:user ^::data-source/dep [:user]
                                                       :facility-id ^::data-source/dep [:facility-id :id]}}

                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids [{:facility-id 1}]}}}]
    (go
      (is (= (into #{}
                   (-> prescription
                       sut/fill
                       (sut/select [:facility :facilities])
                       exhaust
                       <!))
             #{{:path :facility-id
                :source {::data-source/id ::echo-params
                         ::data-source/params {:id 1337}
                         ::data-source/deps #{}}
                :result {::result/success? true
                         ::result/attempts 1
                         ::result/data {:id 1337}}}
               {:path :user
                :source {::data-source/id ::echo-params
                         ::data-source/params {:id 13}
                         ::data-source/deps #{}}
                :result {::result/success? true
                         ::result/attempts 1
                         ::result/data {:id 13}}}
               {:path :facility
                :source {::data-source/id ::echo-params
                         ::data-source/params {:user {:id 13}
                                               :facility-id 1337}
                         ::data-source/deps #{:user :facility-id}}
                :result {::result/success? true
                         ::result/attempts 1
                         ::result/data {:user {:id 13}
                                        :facility-id 1337}}}
               {:path :facilities
                :source {::data-source/id ::get-ids
                         ::data-source/params {:ids [{:facility-id 1}]}
                         ::data-source/coll-of :facility
                         ::data-source/deps #{}}
                :result {::result/success? true
                         ::result/partial? true
                         ::result/data [{:facility-id 1}]
                         ::result/attempts 1}}
               {:path [:facilities 0]
                :source {::data-source/id ::echo-params
                         ::data-source/params {:user {:id 13}
                                               :facility-id 1}
                         ::data-source/deps #{:user}
                         ::data-source/member-of :facilities}
                :result {::result/success? true
                         ::result/data {:user {:id 13}
                                        :facility-id 1}
                         ::result/attempts 1}}
               {:path :facilities
                :source {::data-source/id ::get-ids
                         ::data-source/params {:ids [{:facility-id 1}]}
                         ::data-source/coll-of :facility
                         ::data-source/deps #{[:facilities 0]}}
                :result {::result/success? true
                         ::result/data [{:facility-id 1 :user {:id 13}}]
                         ::result/attempts 1}}})))))

(defscenario-async "Does not load shadowed deps in nested sources"
  (let [prescription {:user {::data-source/fn #'echo-params
                             ::data-source/params {:id 13}}

                      :facility-id {::data-source/fn #'echo-params
                                    ::data-source/params {:id 1337}}

                      :facility {::data-source/fn #'echo-params
                                 ::data-source/params {:user ^::data-source/dep [:user]
                                                       :facility-id ^::data-source/dep [:facility-id :id]}}

                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids [{:facility-id 1}]}}}]
    (go
      (is (= (->> (-> prescription
                      sut/fill
                      (sut/select [:facilities])
                      exhaust
                      <!)
                  (map :path)
                  (into #{}))
             #{:user :facilities [:facilities 0]})))))

(defn thing [{::data-source/keys [params]}]
  (result/success
   (if (= 1 (:item-id params))
     {:id "Thingy"}
     {:id "Other thing"})))

(defscenario-async "Passes source with fully resolved nested sources as dependency"
  (go
    (is (= (filter #(= :collection (:path %))
                   (-> {:item {::data-source/fn #'thing}

                        :items {::data-source/coll-of :item
                                ::data-source/fn #'get-ids
                                ::data-source/params {:ids [{:item-id 1}
                                                            {:item-id 2}]}}

                        :collection {::data-source/id ::collection
                                     ::data-source/fn #'echo-params
                                     ::data-source/params {:items ^::data-source/dep [:items]}}}
                       sut/fill
                       (sut/select [:collection])
                       exhaust
                       <!))
           [{:path :collection
             :source {::data-source/id ::collection
                      ::data-source/params {:items [{:id "Thingy"}
                                                    {:id "Other thing"}]}
                      ::data-source/deps #{:items}}
             :result {::result/success? true
                      ::result/data {:items [{:id "Thingy"}
                                             {:id "Other thing"}]}
                      ::result/attempts 1}}]))))

(defscenario-async "Fails collection source when a collection item fails"
  (go
    (stub stub-2 [(result/failure)])
    (is (= (into #{}
                 (-> {:facility {::data-source/fn #'echo-stub-2}
                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids (seq [{:facility-id 1}])}}}
                     sut/fill
                     (sut/select [:facilities])
                     exhaust
                     <!))
           #{{:path :facilities
              :source {::data-source/id ::get-ids
                       ::data-source/params {:ids [{:facility-id 1}]}
                       ::data-source/coll-of :facility
                       ::data-source/deps #{}}
              :result {::result/success? true
                       ::result/partial? true
                       ::result/data [{:facility-id 1}]
                       ::result/attempts 1}}
             {:path [:facilities 0]
              :source {::data-source/id ::echo-stub-2
                       ::data-source/params {:facility-id 1}
                       ::data-source/deps #{}
                       ::data-source/member-of :facilities}
              :result {::result/success? false
                       ::result/retrying? false
                       ::result/attempts 1}}
             {:path :facilities
              :source {::data-source/id ::get-ids
                       ::data-source/params {:ids [{:facility-id 1}]}
                       ::data-source/coll-of :facility
                       ::data-source/deps #{[:facilities 0]}}
              :result {::result/success? false
                       ::result/data [{:facility-id 1}]
                       ::result/attempts 1}}}))))

(defscenario-async "Selects realized collection from previously filled prescription"
  (go
    (stub stub-1 [{:facility "OK"}])
    (let [filled (-> {:facility {::data-source/fn #'echo-stub-1}
                      :facilities {::data-source/coll-of :facility
                                   ::data-source/fn #'get-ids
                                   ::data-source/params {:ids (seq [{:facility-id 1}])}}}
                     sut/fill)]
      (<! (sut/collect (sut/select filled [:facilities])))
      (is (= (into #{}
                   (<! (exhaust (sut/select filled [:facilities]))))
             #{{:path :facilities
                :source {::data-source/id ::get-ids
                         ::data-source/params {:ids [{:facility-id 1}]}
                         ::data-source/coll-of :facility
                         ::data-source/deps #{}}
                :result {::result/success? true
                         ::result/data [{:facility "OK"}]
                         ::result/attempts 1}}})))))

(defscenario-async "Cache stores fully resolved collection"
  (go
    (stub stub-1 [{:facility "OK"}])
    (let [prescription {:facility {::data-source/fn #'echo-stub-1}
                        :facilities {::data-source/coll-of :facility
                                     ::data-source/fn #'get-ids
                                     ::data-source/params {:ids (seq [{:facility-id 1}])}}}
          cache (atom {})]
      (-> (sut/fill prescription {:cache (cache/atom-map cache)})
          (sut/select [:facilities])
          exhaust
          <!)
      (is (= (-> (sut/fill prescription {:cache (cache/atom-map cache)})
                 (sut/select [:facilities])
                 sut/collect
                 <!
                 ::result/sources
                 first
                 (update :result dissoc ::cache/cached-at))
             {:path :facilities
              :result {::result/attempts 0
                       ::result/data [{:facility "OK"}]
                       ::result/success? true
                       ::result/cached? true}
              :source {::data-source/coll-of :facility
                       ::data-source/deps #{}
                       ::data-source/id ::get-ids
                       ::data-source/params {:ids [{:facility-id 1}]}}})))))

(defscenario-async "Tracks indices per collection"
  (go
    (stub stub-1 [[{:id 1}]])
    (stub stub-2 [(result/success [{:id 10}])])
    (let [filled (-> {:pizza {::data-source/fn #'echo-params}
                      :hotdog {::data-source/fn #'echo-params}
                      :pizza-meals {::data-source/coll-of :pizza
                                    ::data-source/fn #'echo-stub-1}
                      :hotdog-meals {::data-source/coll-of :hotdog
                                     ::data-source/fn #'echo-stub-2}}
                     sut/fill)]
      (is (= (->> (sut/select filled [:pizza-meals :hotdog-meals])
                  sut/collect
                  <!
                  ::result/sources
                  (map :path)
                  (into #{}))
             #{:pizza-meals
               [:pizza-meals 0]
               :hotdog-meals
               [:hotdog-meals 0]})))))

(defscenario-async "Data begets data"
  (go
    (stub stub-1 [{:title "Good food"}])
    (is (= (-> {:pizza {::data-source/fn #'echo-params
                        ::data-source/params {:config ^::data-source/dep [:config]}}
                :person {::data-source/fn #'echo-stub-1
                         ::data-source/begets {:meal :pizza}}}
               (sut/fill {:params {:config {:id 12}}})
               (sut/select [:person])
               exhaust
               <!)
           [{:path :person
             :source {::data-source/id ::echo-stub-1
                      ::data-source/deps #{}
                      ::data-source/begets {:meal :pizza}
                      ::data-source/params {}}
             :result {::result/success? true
                      ::result/partial? true
                      ::result/attempts 1
                      ::result/data {:title "Good food"}}}
            {:path [:person :meal]
             :source {::data-source/id ::echo-params
                      ::data-source/deps #{}
                      ::data-source/params {:config {:id 12}
                                            :title "Good food"}
                      ::data-source/member-of :person}
             :result {::result/success? true
                      ::result/attempts 1
                      ::result/data {:title "Good food"
                                     :config {:id 12}}}}
            {:path :person
             :source {::data-source/id ::echo-stub-1
                      ::data-source/deps #{[:person :meal]}
                      ::data-source/begets {:meal :pizza}
                      ::data-source/params {}}
             :result {::result/success? true
                      ::result/attempts 1
                      ::result/data {:title "Good food"
                                     :meal {:title "Good food"
                                            :config {:id 12}}}}}]))))

(defn event-summary [events]
  (->> events
       (map (fn [{:keys [path source result]}]
              {:path path
               :partial? (boolean (::result/partial? result))
               :params (::data-source/params source)
               :data (::result/data result)}))))

(defscenario-async "Data begets data begets data"
  (go
    (stub stub-1 [{:title "Good food"}])
    (stub stub-2 [(result/success {:title "Mozarella"})])
    (is (= (-> {:cheese {::data-source/fn #'echo-stub-2}
                :pizza {::data-source/fn #'echo-params
                        ::data-source/params {:config ^::data-source/dep [:config]}
                        ::data-source/begets {:cheese :cheese}}
                :person {::data-source/fn #'echo-stub-1
                         ::data-source/begets {:meal :pizza}}}
               (sut/fill {:params {:config {:id 12}}})
               (sut/select [:person])
               exhaust
               <!
               event-summary)
           [{:path :person
             :params {}
             :partial? true
             :data {:title "Good food"}}
            {:path [:person :meal]
             :params {:config {:id 12}
                      :title "Good food"}
             :partial? true
             :data {:title "Good food"
                    :config {:id 12}}}
            {:path [:person :meal :cheese]
             :params {:title "Good food"
                      :config {:id 12}}
             :partial? false
             :data {:title "Mozarella"}}
            {:path [:person :meal]
             :params {:config {:id 12}
                      :title "Good food"}
             :partial? false
             :data {:title "Good food"
                    :config {:id 12}
                    :cheese {:title "Mozarella"}}}
            {:path :person
             :partial? false
             :params {}
             :data {:title "Good food"
                    :meal {:title "Good food"
                           :config {:id 12}
                           :cheese {:title "Mozarella"}}}}]))))

(defn- cheese [{::data-source/keys [params]}]
  (if (= 1 (:id params))
    (result/success {:title "Gorgonzola"})
    (result/success {:title "Mozarella"})))

(defscenario-async "Collection items begets data"
  (go
    (is (= (into
            #{}
            (-> {:cheese {::data-source/fn #'cheese}
                 :pizza {::data-source/fn #'echo-params
                         ::data-source/begets {:cheese :cheese}}
                 :pizzas {::data-source/fn #'get-ids
                          ::data-source/params {:ids [{:id 1} {:id 2}]}
                          ::data-source/coll-of :pizza}}
                sut/fill
                (sut/select [:pizzas])
                exhaust
                <!
                event-summary))
           #{{:path :pizzas
              :params {:ids [{:id 1} {:id 2}]}
              :partial? true
              :data [{:id 1} {:id 2}]}
             {:path [:pizzas 0]
              :params {:id 1}
              :partial? true
              :data {:id 1}}
             {:path [:pizzas 1]
              :params {:id 2}
              :partial? true
              :data {:id 2}}
             {:path [:pizzas 0 :cheese]
              :params {:id 1}
              :partial? false
              :data {:title "Gorgonzola"}}
             {:path [:pizzas 1 :cheese]
              :params {:id 2}
              :partial? false
              :data {:title "Mozarella"}}
             {:path [:pizzas 0]
              :params {:id 1}
              :partial? false
              :data {:id 1
                     :cheese {:title "Gorgonzola"}}}
             {:path [:pizzas 1]
              :params {:id 2}
              :partial? false
              :data {:id 2
                     :cheese {:title "Mozarella"}}}
             {:path :pizzas
              :params {:ids [{:id 1} {:id 2}]}
              :partial? false
              :data [{:id 1
                      :cheese {:title "Gorgonzola"}}
                     {:id 2
                      :cheese {:title "Mozarella"}}]}}))))

(defscenario-async "Data begets data, begets collection, begets data"
  (go
    (stub stub-1 [{:title "Movie"
                   :ids [{:person "One"} {:person "Two"}]}])
    (is (= (into
            #{}
            (-> {:actor {::data-source/fn #'echo-params}
                 :person {::data-source/fn #'echo-params
                          ::data-source/begets {:actor :actor}}
                 :people {::data-source/fn #'get-ids
                          ::data-source/coll-of :person}
                 :movie {::data-source/fn #'echo-stub-1
                         ::data-source/params {:id ^::data-source/dep [:config :id]}
                         ::data-source/begets {:characters :people}}}
                (sut/fill {:params {:config {:id 12}}})
                (sut/select [:movie])
                exhaust
                <!
                event-summary))
           #{{:path :movie
              :partial? true
              :params {:id 12}
              :data {:title "Movie"
                     :ids [{:person "One"} {:person "Two"}]}}
             {:path [:movie :characters]
              :partial? true
              :params {:title "Movie"
                       :ids [{:person "One"} {:person "Two"}]}
              :data [{:person "One"} {:person "Two"}]}
             {:path [:movie :characters 0]
              :partial? true
              :params {:person "One"}
              :data {:person "One"}}
             {:path [:movie :characters 0 :actor]
              :partial? false
              :params {:person "One"}
              :data {:person "One"}}
             {:path [:movie :characters 0]
              :partial? false
              :params {:person "One"}
              :data {:person "One"
                     :actor {:person "One"}}}
             {:path [:movie :characters 1]
              :partial? true
              :params {:person "Two"}
              :data {:person "Two"}}
             {:path [:movie :characters 1 :actor]
              :partial? false
              :params {:person "Two"}
              :data {:person "Two"}}
             {:path [:movie :characters 1]
              :partial? false
              :params {:person "Two"}
              :data {:person "Two"
                     :actor {:person "Two"}}}
             {:path [:movie :characters]
              :partial? false
              :params {:title "Movie"
                       :ids [{:person "One"} {:person "Two"}]}
              :data [{:person "One"
                      :actor {:person "One"}}
                     {:person "Two"
                      :actor {:person "Two"}}]}
             {:path :movie
              :partial? false
              :params {:id 12}
              :data {:title "Movie"
                     :ids [{:person "One"} {:person "Two"}]
                     :characters [{:person "One"
                                   :actor {:person "One"}}
                                  {:person "Two"
                                   :actor {:person "Two"}}]}}}))))

(defscenario-async "Gracefully fails lazy seq in place of proper result"
  (go
    (is (= (<! (-> {:data {::data-source/id ::lazy-seq-fail
                           ::data-source/fn (constantly (map identity []))}}
                   sut/fill
                   (sut/select [:data])
                   results))
           [{::result/success? false
             ::result/attempts 1
             ::result/error {:message "Fetch did not return a pharmacist result: not a map"
                             :type :pharmacist.error/invalid-result
                             :reason :pharmacist.error/result-not-map}
             ::result/original-result (list)
             ::result/retrying? false}]))))

(defscenario-async "Gracefully fails nil in place of proper result"
  (go
    (is (= (<! (-> {:data {::data-source/id ::lazy-seq-fail
                           ::data-source/fn (constantly nil)}}
                   sut/fill
                   (sut/select [:data])
                   results))
           [{::result/success? false
             ::result/attempts 1
             ::result/error {:message "Fetch did not return a pharmacist result: nil"
                             :type :pharmacist.error/invalid-result
                             :reason :pharmacist.error/result-nil}
             ::result/original-result nil
             ::result/retrying? false}]))))

(defscenario-async "Gracefully fails when result does not have success or result"
  (go
    (is (= (<! (-> {:data {::data-source/id ::lazy-seq-fail
                           ::data-source/fn (constantly {:lol 42})}}
                   sut/fill
                   (sut/select [:data])
                   results))
           [{::result/success? false
             ::result/attempts 1
             ::result/error {:message "Fetch did not return a pharmacist result: no :pharmacist.result/success? or :pharmacist.result/data"
                             :type :pharmacist.error/invalid-result
                             :reason :pharmacist.error/not-pharmacist-result}
             ::result/original-result {:lol 42}
             ::result/retrying? false}]))))

(defscenario-async "Gracefully fails when async fetch throws exception"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (<! (-> {:data {::data-source/id ::async-throw
                             ::data-source/async-fn (fn [_] (throw error))}}
                     sut/fill
                     (sut/select [:data])
                     results))
             [{::result/success? false
               ::result/attempts 1
               ::result/error {:message "Fetch threw exception: Holy moly"
                               :type :pharmacist.error/fetch-exception}
               ::result/original-result error
               ::result/retrying? false}])))))

(defmethod data-source/fetch ::custom-fetch-throw [source]
  (throw (ex-info "Hole mole" {})))

(defscenario-async "Gracefully fails when custom fetch throws exception"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (dissoc (first (<! (-> {:data {::data-source/id ::custom-fetch-throw}}
                                    sut/fill
                                    (sut/select [:data])
                                    results)))
                     ::result/original-result)
             {::result/success? false
              ::result/attempts 1
              ::result/error {:message "Fetch threw exception: Hole mole"
                              :type :pharmacist.error/fetch-exception}
              ::result/retrying? false})))))

(defscenario-async "Gracefully fails when sync fetch throws exception"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (<! (-> {:data {::data-source/id ::sync-throw
                             ::data-source/fn (fn [_] (throw error))}}
                     sut/fill
                     (sut/select [:data])
                     results))
             [{::result/success? false
               ::result/attempts 1
               ::result/error {:message "Fetch threw exception: Holy moly"
                               :type :pharmacist.error/fetch-exception}
               ::result/original-result error
               ::result/retrying? false}])))))

(defscenario-async "Gracefully fails when fetch does not take a single argument"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (re-find
           #"Wrong number of args"
           (->> (<! (-> {:data {::data-source/id ::lazy-seq-fail
                                ::data-source/fn (fn [] (throw error))}}
                        sut/fill
                        (sut/select [:data])
                        results))
                first
                ::result/error
                :message))))))

(defscenario-async "Gracefully fails when fetch does not return a channel"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (-> (<! (-> {:data {::data-source/id ::no-port
                                 ::data-source/async-fn (fn [_] :lol)}}
                         sut/fill
                         (sut/select [:data])
                         results))
                 first
                 ::result/error
                 (dissoc :upstream))
             {:message "Fetch return value is not a core.async read port: :lol"
              :type :pharmacist.error/fetch-no-chan})))))

(defscenario-async "Times out long-running request"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (-> {:data {::data-source/id ::long-running
                         ::data-source/async-fn (fn [_]
                                                  (go
                                                    (<! (timeout 100))
                                                    (result/success {:ok true})))}}
                 (sut/fill {:timeout 20})
                 (sut/select [:data])
                 results
                 <!)
             [{::result/success? false
               ::result/timeout-after 20
               ::result/attempts 1
               ::result/retrying? false}])))))

(defscenario-async "Overrides timeout on source"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (-> {:data {::data-source/id ::long-running
                         ::data-source/timeout 30
                         ::data-source/async-fn (fn [_]
                                                  (go
                                                    (<! (timeout 100))
                                                    (result/success {:ok true})))}}
                 (sut/fill {:timeout 20})
                 (sut/select [:data])
                 results
                 <!)
             [{::result/success? false
               ::result/timeout-after 30
               ::result/attempts 1
               ::result/retrying? false}])))))

(defscenario-async "Treats 0 as no timeout"
  (go
    (let [error (ex-info "Holy moly" {})]
      (is (= (-> {:data {::data-source/id ::long-running
                         ::data-source/timeout 0
                         ::data-source/async-fn (fn [_]
                                                  (go
                                                    (<! (timeout 20))
                                                    (result/success {:ok true})))}}
                 (sut/fill {:timeout 10})
                 (sut/select [:data])
                 results
                 <!)
             [{::result/success? true
               ::result/data {:ok true}
               ::result/attempts 1}])))))

(defscenario-async "Is not dependent on reading from port to fetch data"
  (go
    (stub stub-1 [{:data 1} {:data 2} {:data 3}])
    (let [res (-> {:data {::data-source/fn #'echo-stub-1}
                   :coll {::data-source/fn #'get-ids
                          ::data-source/params {:ids [{:id 1} {:id 2} {:id 3}]}
                          ::data-source/coll-of :data}}
                  sut/fill
                  (sut/select [:coll]))]
      (<! (timeout 20))
      (close! res))
    (is (= (count (:calls @stub-1)) 3))))

(defscenario "Merges data from results"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 1}}
                             {:path :name
                              :result {::result/data "Someone"}}
                             {:path :age
                              :result {::result/data 12}}])
         {:id 1
          :name "Someone"
          :age 12})))

(defscenario "Merges data in order of appearance"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 1}}
                             {:path :name
                              :result {::result/data "Someone"}}
                             ;; Refresh on retry can result in having two events
                             ;; for the same path with different data - if so,
                             ;; use the latest one
                             {:path :name
                              :result {::result/data "Mr Other"}}
                             {:path :age
                              :result {::result/data 12}}])
         {:id 1
          :name "Mr Other"
          :age 12})))

(defscenario "Merges nested sources into previously created maps and vectors"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 666}}
                             {:path :facilities
                              :result {::result/data [{:facility-id 1} {:facility-id 2}]}}
                             {:path [:facilities 0]
                              :result {::result/data {:id 1 :name "First one"}}}
                             {:path [:facilities 1]
                              :result {::result/data {:id 2 :name "Second one"}}}])
         {:id 666
          :facilities [{:id 1 :name "First one"}
                       {:id 2 :name "Second one"}]})))

(defscenario "Merges nested sources recursively when levels are out of order"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 666}}
                             {:path :facilities
                              :result {::result/data [{:facility-id 1} {:facility-id 2}]}}
                             {:path [:facilities 0 :load]
                              :result {::result/data :high}}
                             {:path [:facilities 1 :load]
                              :result {::result/data :medium}}
                             {:path [:facilities 0]
                              :result {::result/data {:id 1 :name "First one"}}}
                             {:path [:facilities 1]
                              :result {::result/data {:id 2 :name "Second one"}}}])
         {:id 666
          :facilities [{:id 1 :name "First one" :load :high}
                       {:id 2 :name "Second one" :load :medium}]})))

(defscenario "Uses latest value when merging out of order nested sources"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 666}}
                             {:path :facilities
                              :result {::result/data [{:facility-id 1} {:facility-id 2}]}}
                             {:path [:facilities 0 :load]
                              :result {::result/data :high}}
                             {:path [:facilities 1 :load]
                              :result {::result/data :medium}}
                             {:path [:facilities 0]
                              :result {::result/data {:id 1 :name "First one"}}}
                             {:path [:facilities 1 :load]
                              :result {::result/data :low}}
                             {:path [:facilities 1]
                              :result {::result/data {:id 2 :name "Second one"}}}])
         {:id 666
          :facilities [{:id 1 :name "First one" :load :high}
                       {:id 2 :name "Second one" :load :low}]})))

(defscenario "Merges results with lazy seqs"
  (is (= (sut/merge-results [{:path :facilities
                              :result {::result/data (map identity [{:facility-id 1} {:facility-id 2}])}}
                             {:path '(:facilities 0)
                              :result {::result/data {:id 1 :name "First one"}}}
                             {:path '(:facilities 1)
                              :result {::result/data {:id 2 :name "Second one"}}}])
         {:facilities [{:id 1 :name "First one"}
                       {:id 2 :name "Second one"}]})))

(defscenario "Ignores failed events"
  (is (= (sut/merge-results [{:path :id
                              :result {::result/data 666}}
                             {:path :facilities
                              :result {::result/data [{:facility-id 1} {:facility-id 2}]}}
                             {:path [:facilities 0 :load]
                              :result {::result/data :high}}
                             {:path [:facilities 1 :load]
                              :result {::result/data :medium}}
                             {:path [:facilities 0]
                              :result {::result/data {:id 1 :name "First one"}}}
                             {:path [:facilities 1 :load]
                              :result {::result/data :low}}
                             {:path [:facilities 1 :load]
                              :result {::result/success? false
                                       ::result/data :really-high}}
                             {:path [:facilities 1]
                              :result {::result/data {:id 2 :name "Second one"}}}])
         {:id 666
          :facilities [{:id 1 :name "First one" :load :high}
                       {:id 2 :name "Second one" :load :low}]})))
