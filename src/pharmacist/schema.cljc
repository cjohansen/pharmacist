(ns pharmacist.schema
  "Tools for mapping, coercing, and verifying data."
  (:require #?(:cljs [cljs.spec.alpha :as s]
               :clj [clojure.spec.alpha :as s])
            [clojure.string :as str]))

(s/def ::unique #{::identity})
(s/def ::spec (s/or :spec keyword? :fn ifn?))
(s/def ::source any?)
(s/def ::component? boolean?)

(defn- specs-by-fn
  "Extracts parts of the spec that was created with the provided fn. To find all
  key specs: (specs-by-fn ::spec 'clojure.spec.alpha/keys)"
  [f spec-form]
  (when (coll? spec-form)
    (if (= (first spec-form) f)
      (rest spec-form)
      (->> spec-form
           (filter coll?)
           (mapcat #(specs-by-fn f %))))))

(defn- spec-form [spec]
  (try
    (s/form spec)
    (catch #?(:clj Throwable
              :cljs :default) e
      nil)))

(defn specced-keys
  "Given a keyword representing a spec or an inline spec, returns all possible
  keys it specs, including ones from s/and and s/or."
  [spec]
  (when-let [form (spec-form spec)]
    (->> form
         (specs-by-fn #?(:cljs 'cljs.spec.alpha/keys
                         :clj 'clojure.spec.alpha/keys))
         (partition 2)
         (mapcat second)
         (into #{}))))

(defn coll-of
  "Returns what, if anything, the spec is a collection of"
  [spec]
  (when-let [form (spec-form spec)]
    (->> (s/form spec)
         (specs-by-fn #?(:cljs 'cljs.spec.alpha/coll-of
                         :clj 'clojure.spec.alpha/coll-of))
         first)))

(defn- lookup [{:keys [pharmacist.schema/source
                       pharmacist.schema/coerce]
                :as keyspec} data k & [default]]
  (let [v (if source
            (source data k)
            (get data k default))]
    (if coerce
      (try
        (coerce v)
        (catch #?(:clj Throwable
                  :cljs :default) e
          (throw (ex-info (str "Failed to coerce " k " with custom coercer")
                          keyspec e))))
      v)))

(defn coerce [schema data k]
  (let [{:keys [pharmacist.schema/spec] :as keyspec} (schema k)
        ks (specced-keys spec)
        collection-type (coll-of spec)]
    (cond
      (seq ks) (let [val (lookup keyspec data k data)]
                 (->> ks
                      (map (fn [k] [k (coerce schema val k)]))
                      (filter second)
                      (into {})))
      collection-type (when-let [coll (seq (lookup keyspec data k))]
                        (map #(coerce schema % collection-type) coll))
      :default (lookup keyspec data k))))

(defmulti coerce-data (fn [data-source-id data] data-source-id))

(defmethod coerce-data :default [data-source-id data]
  data)

(defn defschema [data-source-id root-spec & {:as schema}]
  (defmethod coerce-data data-source-id [_ data]
    (coerce schema data root-spec)))

(defn- camel-cased [k]
  (let [[head & tail] (str/split k #"-")]
    (apply str head (map str/capitalize tail))))

(defn infer-ns [m k]
  (get m (keyword (name k))))

(defn infer-camel-ns [m k]
  (get m (keyword (camel-cased (name k)))))
