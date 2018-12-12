(ns pharmacist.schema-test
  (:require [pharmacist.schema :as sut]
            #?(:clj [clojure.test :refer [deftest testing is]]
               :cljs [cljs.test :refer [deftest testing is]])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [clojure.string :as str]))

(deftest specced-keys
  (testing "Gets keys of specs in registry"
    (s/def ::test1 (s/keys :req [::a ::b ::c]))
    (is (= #{::a ::b ::c}
           (sut/specced-keys ::test1))))

  (testing "Gets keys of inline specs"
    (is (= #{::b ::c ::d}
           (sut/specced-keys (s/keys :req [::b ::c ::d])))))

  (testing "Gets nested keys"
    (is (= #{::b ::c ::d}
           (sut/specced-keys (s/or :string string?
                                   :keys (s/keys :req [::b ::c ::d]))))))

  (testing "Combines required and optional keys"
    (is (= #{::b ::c ::d ::e ::f}
           (sut/specced-keys (s/keys :req [::b ::c ::d]
                                     :opt [::e ::f])))))

  (testing "Gets un-namespaced keys too"
    (is (= #{::b ::c ::d ::e ::f}
           (sut/specced-keys (s/keys :req-un [::b ::c ::d]
                                     :opt-un [::e ::f])))))

  (testing "Combines multiple key sources"
    (is (= #{::name ::age ::hobby ::planet ::fur-length ::commands}
           (sut/specced-keys (s/or :person (s/keys :req [::name ::age]
                                                   :opt [::hobby])
                                   :alien (s/keys :req [::name ::planet]
                                                  :opt [::hobby])
                                   :cat (s/and (s/keys :req [::name ::fur-length])
                                               (s/keys :req-un [::commands]))))))))

(deftest coll-of-test
  (testing "Collection of strings"
    (is (= 'clojure.core/string?
           (sut/coll-of (s/coll-of string?)))))

  (testing "Collection of spec"
    (is (= ::some-spec
           (sut/coll-of (s/coll-of ::some-spec)))))

  (testing "Returns the first kind of nested collection"
    (is (= ::some-spec
           (sut/coll-of (s/or :spec (s/coll-of ::some-spec)
                              :smeck (s/coll-of ::some-smeck)))))))

(deftest coerce-test
  (testing "Gets key from output"
    (is (= {:image/url "url://ok"}
           (sut/coerce {:image/url {::sut/spec string?}
                        :image/entity {::sut/spec (s/keys :req [:image/url])}}
                       {:image/url "url://ok"}
                       :image/entity))))

  (testing "Gets key from specified source"
    (is (= {:person/display-name "Miss Piggy"}
           (sut/coerce {:person/display-name {::sut/spec string?
                                              ::sut/source :displayName}
                        :person/entity {::sut/spec (s/keys :req [:person/display-name])}}
                       {:displayName "Miss Piggy"}
                       :person/entity))))

  (testing "Infers sources from camel-cased keys"
    (is (= {:person/display-name "Miss Piggy"}
           (sut/coerce {:person/display-name {::sut/spec string?
                                              ::sut/source sut/infer-camel-ns}
                        :person/entity {::sut/spec (s/keys :req [:person/display-name])}}
                       {:displayName "Miss Piggy"}
                       :person/entity))))

  (testing "Maps contents of sequences"
    (is (= {:person/friends [{:person/name "Miss Piggy"}]}
           (sut/coerce {:person/name {::sut/spec string?}
                        :person/friends {::sut/spec (s/coll-of :person/entity)}
                        :person/entity {::sut/spec (s/keys :opt [:person/name :person/friends])}}
                       {:person/friends [{:person/name "Miss Piggy"}]}
                       :person/entity))))

  (testing "Coerces values"
    (is (= {:person/name :kermit
            :person/friends [{:person/name :piggy}]}
           (sut/coerce {:person/name {::sut/spec string?
                                      ::sut/coerce #(-> % (str/split #" ") last str/lower-case keyword)}
                        :person/friends {::sut/spec (s/coll-of :person/entity)}
                        :person/entity {::sut/spec (s/keys :opt [:person/name :person/friends])}}
                       {:person/name "Kermit"
                        :person/friends [{:person/name "Miss Piggy"}]}
                       :person/entity)))))

(deftest defschema-coerce-data-test
  (testing "Leaves data untouched if no schema is defined"
    (is (= {:id 42}
           (sut/coerce-data :spotify/playlist {:id 42}))))

  (testing "Coerces data with provided schema"
    (sut/defschema :test/muppet :muppet/entity
      :muppet/name {::sut/spec string?
                    ::sut/source :name}
      :muppet/entity {::sut/spec (s/keys :req [:muppet/name])})
    (is (= {:muppet/name "Animal"}
           (sut/coerce-data :test/muppet {:name "Animal"})))))
