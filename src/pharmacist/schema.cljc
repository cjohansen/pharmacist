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
         (mapcat (fn [[k keys]]
                   (if (#{:req-un :opt-un} k)
                     (map #(-> % name keyword) keys)
                     keys)))
         (into #{}))))

(defn coll-of
  "Returns what, if anything, the spec is a collection of"
  [spec]
  (when-let [form (spec-form spec)]
    (->> (s/form spec)
         (specs-by-fn #?(:cljs 'cljs.spec.alpha/coll-of
                         :clj 'clojure.spec.alpha/coll-of))
         first)))

(defn- lookup [{::keys [source coerce]
                :as keyspec} data k & [default]]
  (let [v (cond
            (nil? source) (get data k default)
            (keyword? source) (source data)
            :default (source data k))]
    (if coerce
      (try
        (coerce v)
        (catch #?(:clj Throwable
                  :cljs :default) e
          (throw (ex-info (str "Failed to coerce " k " with custom coercer")
                          keyspec e))))
      v)))

(defn coerce [schema data k]
  (let [{::keys [spec] :as keyspec} (schema k)
        ks (specced-keys spec)
        collection-type (coll-of spec)]
    (cond
      (seq ks) (let [val (lookup keyspec data k data)]
                 (when val
                   (->> ks
                        (map (fn [k] [k (coerce schema val k)]))
                        (filter #(not (nil? (second %))))
                        (into {}))))
      collection-type (when-let [coll (lookup keyspec data k)]
                        (map #(coerce schema % collection-type) coll))
      :default (lookup keyspec data k))))

(defmulti coerce-data
  "Coerces `data` according to the spec in `source`. The default implementation
  will use the schema in `:pharmacist.data-source/schema` and start coercion of
  `data` from the `:pharmacist.schema/entity` key."
  (fn [source data] (:pharmacist.data-source/id source)))

(defmethod coerce-data :default [source data]
  (if-let [schema (:pharmacist.data-source/schema source)]
    (coerce schema data ::entity)
    data))

(def ^:private schema->ds {::identity :db.unique/identity})

(defn- schema-keys [k keyspec]
  (let [spec (:pharmacist.schema/spec keyspec)
        coll-type (coll-of spec)]
    (cond-> (->> (keys keyspec)
             (filter #(= (namespace %) "db"))
             (select-keys keyspec))
      (::unique keyspec) (assoc :db/unique (schema->ds (::unique keyspec)))
      coll-type (assoc :db/cardinality :db.cardinality/many)
      (or (seq (specced-keys spec))
          (seq (specced-keys coll-type))) (assoc :db/valueType :db.type/ref))))

(defn datascript-schema
  "Generate a Datascript schema from the Pharmacist schema. The resulting schema
  will include keys for uniqueness, refs, and cardinality many. Any attributes
  not using these features will be emitted with an empty map for documentation
  purposes. The resulting schema can be passed directly to Datascript.

  When this function is called with a source, it uses the schema in
  `:pharmacist.data-source/schema`, and starts from the
  `:pharmacist.schema/entity` key in the schema.

  Alternatively, you can call the function with a schema, and a key (which must
  exist in said schema)."
  ([source] (datascript-schema (:pharmacist.data-source/schema source) ::entity))
  ([schema k]
   (let [ks (->> schema
                 (map (fn [[k v]] [k (schema-keys k v)]))
                 (into {}))]
     (->> (conj (keep coll-of (keys schema)) k)
          (apply dissoc ks)))))

(defn- camel-cased [k]
  (let [[head & tail] (str/split k #"-")]
    (apply str head (map str/capitalize tail))))

(defn infer-ns
  "Utility to help namespace keys from external sources. Pass in data and a
  namespaced key, and it will look up the unqualified key in the map to find the
  data. This can be used to extract namespaced keys from unqualified data:

```clojure
(require '[pharmacist.schema :as schema]
         '[clojure.spec.alpha :as s])

(def schema
  {:person/name {::schema/source schema/infer-ns}
   ::schema/entity {::schema/spec (s/keys :req [:person/name])}})

(def data {:name \"Wonderwoman\"})

(schema/coerce schema data ::schema/entity)
;;=> {:person/name \"Wonderwoman\"}
```"
  [m k]
  (get m (keyword (name k))))

(defn infer-camel-ns
  "Like [infer-ns], but also infers dash cased keys from their camel cased
  counterparts:

```clojure
(require '[pharmacist.schema :as schema]
         '[clojure.spec.alpha :as s])

(def schema
  {:person/first-name {::schema/source schema/infer-camel-ns}
   ::schema/entity {::schema/spec (s/keys :req [:person/first-name])}})

(def data {:firstName \"Wonderwoman\"})

(schema/coerce schema data ::schema/entity)
;;=> {:person/first-name \"Wonderwoman\"}
```"
  [m k]
  (get m (keyword (camel-cased (name k)))))
