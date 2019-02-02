(ns pharmacist.validate
  "Tools to validate a prescription before attempting to fill it. Pharmacist
  will make the best the situation, and in the case of cyclic dependencies and
  other forms of invalid states, it will just stop processing. The functions in
  this namespace can help you trigger those situations as errors instead."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [pharmacist.data-source :as data-source]))

(defn- find-cyclic-dep-1 [prescription source visited]
  (if (nil? source)
    false
    (loop [[dep & deps] (::data-source/deps source)]
      (if (visited dep)
        [dep]
        (let [cyclic-key (find-cyclic-dep-1 prescription (prescription dep) (conj visited dep))]
          (cond
            cyclic-key (concat [dep] cyclic-key)
            (seq deps) (recur (rest deps))
            :default nil))))))

(defn find-cyclic-dep
  "Find the cyclic dependency from `path` in `prescription`, if any. If a
  cyclic dependency is found, the function will return a vector indicating the
  full path, otherwise it returns nil."
  [prescription path]
  (find-cyclic-dep-1 prescription (get prescription path) #{path}))

(defn- inflect [xs w & [singular multiple]]
  (if (< 1 (count xs))
    (str w (or multiple "s"))
    (str w singular)))

(defn validate-deps
  "Validate the dependencies in `prescription`, and ensure that every dependency
  can be met. Optionally provide initial parameters as `params`. Returns a map
  of `{:type :data :message}` in case of problems, where `:type` is one of
  `:cyclic-dependency`, `:missing-dep`, or `:source-shadowing`, `:message` is a
  human readable explanation of the problem, and `:data` provides details about
  the problem."
  [prescription & [params]]
  (let [source-keys (set (keys prescription))
        param-keys (set (keys params))
        ks (set/union source-keys param-keys)
        overlap (set/intersection source-keys param-keys)]
    (if-not (empty? overlap)
      {:message (str (inflect overlap "Parameter") " " (str/join " " overlap) " shadow "
                     (inflect overlap "data source") " with same name")
       :type :source-shadowing
       :data overlap}
      (loop [[[k source] & srcs] prescription]
        (if-let [path (find-cyclic-dep-1 prescription source #{k})]
          {:message (str k " has cyclic dependency " (str/join " -> " path))
           :type :cyclic-dependency
           :data path}
          (let [missing (filter #(not (ks %)) (::data-source/deps source))]
            (if (seq missing)
              {:message (str k " has missing "
                             (inflect missing "dependenc" "y" "ies")
                             ": " (str/join ", " missing))
               :type :missing-dep
               :data {:path k :missing missing}}
              (cond
                (seq srcs) (recur srcs)
                :default true))))))))
