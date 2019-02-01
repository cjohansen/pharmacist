(ns pharmacist.data-source
  "Tools for implementing data sources"
  (:require #?(:clj [clojure.core.async :as a]
               :cljs [cljs.core.async :as a])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [clojure.string :as str]))

(s/def ::id keyword?)
(s/def ::params (s/map-of any? any?))
(s/def ::retryable? boolean?)
(s/def ::retries integer?)
(s/def ::dep boolean?)
(s/def ::nested-prescriptions (s/and set? (s/coll-of keyword?)))
(s/def ::prescription (s/keys :req [::id]
                              :opt [::params ::retryable? ::retries ::dep ::nested-prescriptions]))

(defn- fname [f]
  (when-let [name (some-> f str (str/replace #"_" "-"))]
    (second (re-find #"(?:#')?(.*)" name))))

(defn id [{::keys [id fn async-fn]}]
  (or id
      (when-let [f (or fn async-fn)]
        #?(:clj
           (if-let [m (meta f)]
             (keyword (str (:ns m)) (str(:name m)))
             (let [name (fname f)]
               (if-let [[_ ns n] (re-find #"(.*)\$(.*)@" name)]
                 (keyword ns n)
                 (keyword name))))
           :cljs
           (let [name (fname f)]
             (if-let [[_ res] (re-find #"function (.+)\(" name)]
               (let [[f & ns ] (-> res (str/split #"\$") reverse)]
                 (keyword (str/join "." (reverse ns)) f))
               (if (re-find #"function \(" name)
                 (keyword (str (random-uuid)))
                 (keyword (str/replace name #" " "-")))))))))

(defmulti fetch-sync (fn [prescription] (::id prescription)))
(defmulti fetch (fn [prescription] (::id prescription)))

(defmethod fetch :default [prescription]
  (cond
    (::async-fn prescription) ((::async-fn prescription) prescription)
    (::fn prescription) (a/go ((::fn prescription) prescription))
    :default (a/go (fetch-sync prescription))))

(defmulti cache-params (fn [prescription] (::id prescription)))

(defmethod cache-params :default [{::keys [params cache-params]}]
  (or cache-params
      (if (vector? params)
        [params]
        (map #(vector %) (keys params)))))

(defmulti cache-deps (fn [prescription] (::id prescription)))

(defmethod cache-deps :default [prescription]
  (or (::cache-deps prescription)
      (->> (cache-params prescription)
           (map first)
           (into #{}))))

(defmulti cache-key
  "Given a prescription, return the cache key that uniquely addresses content
  loaded by the prescription. The default implementation combines the id/type
  with all parameters"
  (fn [prescription] (::id prescription)))

(defmethod cache-key :default [prescription]
  (let [params (->> (cache-params prescription)
                    (map (fn [p] [p (get-in (::params prescription) p)]))
                    (into {}))]
    (cond-> [(id prescription)]
      (not (empty? params)) (conj params))))
