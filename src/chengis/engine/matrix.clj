(ns chengis.engine.matrix
  "Matrix build support: expand pipeline stages across multiple parameter combinations.

   A matrix config is a map of dimension names to vectors of values:
     {:os [\"linux\" \"macos\"] :jdk [\"11\" \"17\"]}

   This produces all combinations (cartesian product) and expands each
   matrix-enabled stage into N copies, one per combination, with MATRIX_*
   environment variables injected.

   Supports :exclude to filter out specific combinations."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log]))

(def ^:const default-max-combinations
  "Default maximum number of matrix combinations to prevent explosion."
  25)

(defn- cartesian-product
  "Compute the cartesian product of a map of dimension → values.
   {:os [\"linux\" \"macos\"] :jdk [\"11\" \"17\"]}
   → [{:os \"linux\" :jdk \"11\"} {:os \"linux\" :jdk \"17\"}
      {:os \"macos\" :jdk \"11\"} {:os \"macos\" :jdk \"17\"}]"
  [dimensions]
  (if (empty? dimensions)
    [{}]
    (let [[dim values] (first dimensions)
          rest-product (cartesian-product (dissoc dimensions dim))]
      (for [v values
            combo rest-product]
        (assoc combo dim (str v))))))

(defn- matches-exclude?
  "Check if a combination matches an exclude rule.
   An exclude rule matches when ALL specified keys have matching values."
  [combination exclude-rule]
  (every? (fn [[k v]]
            (= (str v) (get combination k)))
          exclude-rule))

(defn expand-matrix
  "Generate all combinations from a matrix config map.
   Optionally filters out combinations matching :exclude rules.

   Input:  {:os [\"linux\" \"macos\"] :jdk [\"11\" \"17\"]}
   Output: [{:os \"linux\" :jdk \"11\"} {:os \"linux\" :jdk \"17\"} ...]

   Options:
     :exclude  — vector of maps specifying combinations to skip
     :max      — maximum combinations (default 25)"
  [{:keys [exclude] :as matrix-config}
   & {:keys [max] :or {max default-max-combinations}}]
  (let [;; Extract dimension map (everything except :exclude)
        dimensions (dissoc matrix-config :exclude)
        all-combos (cartesian-product dimensions)
        ;; Apply exclude filters
        filtered (if (seq exclude)
                   (remove (fn [combo]
                             (some #(matches-exclude? combo %)
                                   exclude))
                           all-combos)
                   all-combos)
        result (vec filtered)]
    (when (> (count result) max)
      (throw (ex-info (str "Matrix produces " (count result)
                           " combinations, exceeding limit of " max)
                      {:count (count result) :max max})))
    result))

(defn matrix-label
  "Generate a human-readable label for a matrix combination.
   {:os \"linux\" :jdk \"11\"} → \"os=linux, jdk=11\""
  [combination]
  (str/join ", "
    (map (fn [[k v]] (str (name k) "=" v))
         (sort-by (comp name key) combination))))

(defn matrix-env
  "Convert a matrix combination to environment variable map.
   {:os \"linux\" :jdk \"11\"} → {\"MATRIX_OS\" \"linux\" \"MATRIX_JDK\" \"11\"}"
  [combination]
  (reduce-kv (fn [m k v]
               (assoc m
                 (str "MATRIX_" (str/upper-case (str/replace (name k) "-" "_")))
                 (str v)))
             {} combination))

(defn expand-stages
  "Expand stages using matrix configuration.
   For each stage, creates N copies (one per combination) with:
   - Stage name suffixed with [dim1=val1, dim2=val2]
   - MATRIX_* env vars injected into each step

   Non-matrix stages pass through unchanged.
   If matrix-config is nil or empty, returns stages unchanged."
  [stages matrix-config & {:keys [max] :or {max default-max-combinations}}]
  (if (or (nil? matrix-config)
          (empty? (dissoc matrix-config :exclude)))
    stages
    (let [combinations (expand-matrix matrix-config :max max)]
      (log/info "Matrix expansion:" (count combinations) "combinations")
      (vec
        (mapcat (fn [stage]
                  (for [combo combinations]
                    (let [label (matrix-label combo)
                          env-vars (matrix-env combo)]
                      (-> stage
                          (assoc :stage-name (str (:stage-name stage) " [" label "]"))
                          (assoc :matrix-combination combo)
                          (update :steps
                            (fn [steps]
                              (mapv (fn [step]
                                      (update step :env
                                        (fn [existing-env]
                                          (merge env-vars existing-env))))
                                    steps)))))))
                stages)))))
