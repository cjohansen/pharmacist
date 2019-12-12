(ns pharmacist.schema-test
  (:require #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])
            [clojure.string :as str]
            [pharmacist.data-source :as data-source]
            [pharmacist.schema :as sut]))

(deftest specced-keys
  (testing "Gets keys of specs in registry"
    (s/def ::test1 (s/keys :req [::a ::b ::c]))
    (is (= (sut/specced-keys ::test1)
           #{::a ::b ::c})))

  (testing "Gets keys of inline specs"
    (is (= (sut/specced-keys (s/keys :req [::b ::c ::d]))
           #{::b ::c ::d})))

  (testing "Gets nested keys"
    (is (= (sut/specced-keys (s/or :string string?
                                   :keys (s/keys :req [::b ::c ::d])))
           #{::b ::c ::d})))

  (testing "Combines required and optional keys"
    (is (= (sut/specced-keys (s/keys :req [::b ::c ::d]
                                     :opt [::e ::f]))
           #{::b ::c ::d ::e ::f})))

  (testing "Gets un-namespaced keys too"
    (is (= (sut/specced-keys (s/keys :req-un [::b ::c ::d]
                                     :opt-un [::e ::f]))
           #{:b :c :d :e :f})))

  (testing "Combines multiple key sources"
    (is (= (sut/specced-keys (s/or :person (s/keys :req [::name ::age]
                                                   :opt [::hobby])
                                   :alien (s/keys :req [::name ::planet]
                                                  :opt [::hobby])
                                   :cat (s/and (s/keys :req [::name ::fur-length])
                                               (s/keys :req-un [::commands]))))
           #{::name ::age ::hobby ::planet ::fur-length :commands}))))

(deftest coll-of-test
  (testing "Collection of strings"
    (is (= (sut/coll-of (s/coll-of string?))
           #?(:clj 'clojure.core/string?
              :cljs 'cljs.core/string?))))

  (testing "Collection of spec"
    (is (= (sut/coll-of (s/coll-of ::some-spec))
           ::some-spec)))

  (testing "Returns the first kind of nested collection"
    (is (= (sut/coll-of (s/or :spec (s/coll-of ::some-spec)
                              :smeck (s/coll-of ::some-smeck)))
           ::some-spec))))

(deftest conform-test
  (testing "Gets key from output"
    (is (= (sut/conform {:image/url {::sut/spec string?}
                        :image/entity {::sut/spec (s/keys :req [:image/url])}}
                       {:image/url "url://ok"}
                       :image/entity)
           {:image/url "url://ok"})))

  (testing "Gets key from specified source"
    (is (= (sut/conform {:person/display-name {::sut/spec string?
                                              ::sut/source :displayName}
                        :person/entity {::sut/spec (s/keys :req [:person/display-name])}}
                       {:displayName "Miss Piggy"}
                       :person/entity)
           {:person/display-name "Miss Piggy"})))

  (testing "Gets key from nested source path"
    (is (= (sut/conform {:person/display-name {::sut/spec string?
                                              ::sut/source [:person :name]}
                        :person/entity {::sut/spec (s/keys :req [:person/display-name])}}
                       {:person {:name "Miss Piggy"}}
                       :person/entity)
           {:person/display-name "Miss Piggy"})))

  (testing "Gets un-namespaced keys from :req-un and :opt-un keyspecs"
    (is (= (sut/conform {:display-name {::sut/source :displayName}
                        :show {}
                        :person/entity {::sut/spec (s/keys :req-un [::display-name]
                                                           :opt-un [::show])}}
                       {:displayName "Miss Piggy"
                        :show "Muppets"}
                       :person/entity)
           {:display-name "Miss Piggy"
            :show "Muppets"})))

  (testing "Does not throw then source does not exist"
    (is (= (sut/conform {::name {::sut/source :name}
                        :person/entity {::sut/spec (s/keys :req [::name])}}
                       {}
                       :person/entity)
           {}))

    (is (nil? (sut/conform {:person/entity {::sut/source :body}}
                          {}
                          :person/entity)))

    (is (= (sut/conform {::name {::sut/source :name}
                        ::friend {::sut/spec (s/keys :req [::name])}
                        ::friends {::sut/source :friends
                                   ::sut/spec (s/coll-of ::friend)}
                        :person/entity {::sut/spec (s/keys :req [::friends])}}
                       {}
                       :person/entity)
           {}))

    (is (= (sut/conform {::name {::sut/source :name}
                        ::friend {::sut/spec (s/keys :req [::name])}
                        ::friends {::sut/source :friends
                                   ::sut/spec (s/coll-of ::friend)}
                        :person/entity {::sut/spec (s/keys :req [::friends])}}
                       {:friends []}
                       :person/entity)
           {::friends []})))

  (testing "Does not accidentally conform nil refs to empty maps"
    (is (= (sut/conform {::name {::sut/source :name}
                        ::friend {::sut/source :friend
                                  ::sut/spec (s/keys :req [::name])}
                        :person/entity {::sut/spec (s/keys :req [::friend])}}
                       {}
                       :person/entity)
           {})))

  (testing "Does not accidentally conform false to nil"
    (is (= (sut/conform {:really? {}
                        :thing/entity {::sut/spec (s/keys :req-un [::really?])}}
                       {:really? false}
                       :thing/entity)
           {:really? false})))

  (testing "Infers sources from camel-cased keys"
    (is (= (sut/conform {:person/display-name {::sut/spec string?
                                              ::sut/source sut/infer-camel-ns}
                        :person/entity {::sut/spec (s/keys :req [:person/display-name])}}
                       {:displayName "Miss Piggy"}
                       :person/entity)
           {:person/display-name "Miss Piggy"})))

  (testing "Maps contents of sequences"
    (is (= (sut/conform {:person/name {::sut/spec string?}
                        :person/friends {::sut/spec (s/coll-of :person/entity)}
                        :person/entity {::sut/spec (s/keys :opt [:person/name :person/friends])}}
                       {:person/friends [{:person/name "Miss Piggy"}]}
                       :person/entity)
           {:person/friends [{:person/name "Miss Piggy"}]})))

  (testing "Maps contents of sequences with un-namespaced key"
    (is (= (sut/conform {:name {::sut/spec string?}
                        :friends {::sut/spec (s/coll-of :person)}
                        :person {::sut/spec (s/keys :opt-un [::name ::friends])}}
                       {:friends [{:name "Miss Piggy"}]}
                       :person)
           {:friends [{:name "Miss Piggy"}]})))

  (testing "Conforms values"
    (is (= (sut/conform {:person/name {::sut/spec string?
                                      ::sut/coerce #(-> % (str/split #" ") last str/lower-case keyword)}
                        :person/friends {::sut/spec (s/coll-of :person/entity)}
                        :person/entity {::sut/spec (s/keys :opt [:person/name :person/friends])}}
                       {:person/name "Kermit"
                        :person/friends [{:person/name "Miss Piggy"}]}
                       :person/entity)
           {:person/name :kermit
            :person/friends [{:person/name :piggy}]})))

  (testing "Conforms sequence items"
    (is (= (sut/conform
            {::consumption-reading {::sut/coerce (fn [{:keys [timestamp reading-value]}]
                                                   {:timestamp (first (str/split timestamp #"T"))
                                                    :value reading-value})}
             ::entity {::sut/spec (s/coll-of ::consumption-reading)
                       ::sut/source [:consumption :readings]}}
            {:consumption {:readings [{:timestamp "2018-08-01T00:00:00"
                                       :reading-value 250
                                       :temperature 0}
                                      {:timestamp "2018-09-01T00:00:00"
                                       :reading-value 300
                                       :temperature 0}
                                      {:timestamp "2018-10-01T00:00:00"
                                       :reading-value 85
                                       :temperature 0}]}}
            ::entity)
           [{:timestamp "2018-08-01", :value 250}
            {:timestamp "2018-09-01", :value 300}
            {:timestamp "2018-10-01", :value 85}]))))

(deftest conform-data-source-test
  (testing "Leaves data untouched if no schema is defined"
    (is (= (sut/conform-data {::data-source/id :spotify/playlist} {:id 42})
           {:id 42})))

  (testing "Conforms data with provided schema"
    (let [schema {:muppet/name {::sut/spec string?
                                ::sut/source :name}
                  ::sut/entity {::sut/spec (s/keys :req [:muppet/name])}}]
      (is (= (sut/conform-data {::data-source/id :test/muppet
                                ::data-source/schema schema}
                               {:name "Animal"})
             {:muppet/name "Animal"})))))

(deftest datascript-schema-test
  (testing "Exports datascript schema"
    (is (= (sut/datascript-schema
            {:image/url {::sut/spec string?
                         ::sut/source :url}
             :image/width {::sut/spec int?
                           ::sut/source :width}
             :image/height {::sut/spec int?
                            ::sut/source :height}
             :playlist/id {::sut/unique ::sut/identity}
             :playlist/collaborative {::sut/spec boolean?
                                      ::sut/source :collaborative}
             :playlist/title {::sut/spec string?
                              ::sut/source :name}
             :playlist/image {::sut/spec (s/keys :req [:image/url :image/width :image/height])}
             :playlist/images {::sut/spec (s/coll-of :playlist/image)
                               ::sut/source :images}
             :playlist/entity {::sut/spec (s/keys :req [:playlist/id
                                                        :playlist/collaborative
                                                        :playlist/title
                                                        :playlist/images])}}
            :playlist/entity)
           {:playlist/id {:db/unique :db.unique/identity}
            :playlist/collaborative {}
            :playlist/title {}
            :playlist/image {:db/valueType :db.type/ref}
            :playlist/images {:db/cardinality :db.cardinality/many}
            :image/url {}
            :image/width {}
            :image/height {}}))))
