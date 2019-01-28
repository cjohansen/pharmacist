(ns pharmacist.validate
  (:require [pharmacist.data-source :as data-source]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn- err [message]
  (throw (#?(:cljs js/Error.
             :clj Exception.)
          message)))

(defn find-cyclic-dep [sources item visited]
  (if (nil? item)
    false
    (loop [[dep & deps] (::data-source/deps item)]
      (if (visited dep)
        [dep]
        (let [cyclic-key (find-cyclic-dep sources (sources dep) (conj visited dep))]
          (cond
            cyclic-key (concat [dep] cyclic-key)
            (seq deps) (recur (rest deps))
            :default nil))))))

(defn- inflect [xs w & [singular multiple]]
  (if (< 1 (count xs))
    (str w (or multiple "s"))
    (str w singular)))

(defn deps-valid? [prescription & [params]]
  (let [source-keys (set (keys prescription))
        param-keys (set (keys params))
        ks (set/union source-keys param-keys)
        overlap (set/intersection source-keys param-keys)]
    (when-not (empty? overlap)
      (err (str (inflect overlap "Parameter") " " (str/join " " overlap) " shadow "
                (inflect overlap "data source") " with same name")))
    (loop [[[k sdef] & srcs] prescription]
      (if-let [path (find-cyclic-dep prescription sdef #{k})]
        (err (str k " has cyclic dependency " (str/join " -> " path)))
        (let [non-existing (filter #(not (ks %)) (::data-source/deps sdef))]
          (if (seq non-existing)
            (err (str k " has missing "
                      (inflect non-existing "dependenc" "y" "ies")
                      ": " (str/join ", " non-existing)))
            (cond
              (seq srcs) (recur srcs)
              :default true)))))))
