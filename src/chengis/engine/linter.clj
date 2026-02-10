(ns chengis.engine.linter
  "Comprehensive pipeline linter for all three formats (Clojure DSL, EDN, YAML).
   Validates structure, semantics, and catches common mistakes.
   Returns detailed diagnostics with errors, warnings, and pipeline info."
  (:require [chengis.dsl.core :as dsl]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.dsl.yaml :as yaml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- add-issue
  "Append a lint issue to the accumulator."
  [acc level location message rule]
  (update acc (if (= level :error) :errors :warnings)
          conj {:level level :location location :message message :rule rule}))

(defn- stage-location
  "Format a location string for a stage."
  [stage-name]
  (str "Stage '" stage-name "'"))

(defn- step-location
  "Format a location string for a step within a stage."
  [stage-name step-name]
  (str "Stage '" stage-name "', Step '" step-name "'"))

;; ---------------------------------------------------------------------------
;; Structural checks
;; ---------------------------------------------------------------------------

(defn- check-stages-present
  [acc pipeline]
  (cond
    (nil? (:stages pipeline))
    (add-issue acc :error "Pipeline" "Missing required key :stages" :missing-stages)

    (not (sequential? (:stages pipeline)))
    (add-issue acc :error "Pipeline" ":stages must be a list" :invalid-stages-type)

    (empty? (:stages pipeline))
    (add-issue acc :error "Pipeline" ":stages must not be empty" :empty-stages)

    :else acc))

(defn- check-stage-names-unique
  [acc stages]
  (let [names (map :stage-name stages)
        freqs (frequencies names)
        dupes (filter (fn [[_ c]] (> c 1)) freqs)]
    (reduce (fn [a [dname _]]
              (add-issue a :error "Pipeline"
                         (str "Duplicate stage name: '" dname "'")
                         :duplicate-stage-name))
            acc dupes)))

(defn- check-step-names-unique-within-stage
  [acc stages]
  (reduce (fn [a stage]
            (let [step-names (map :step-name (:steps stage))
                  freqs (frequencies step-names)
                  dupes (filter (fn [[_ c]] (> c 1)) freqs)]
              (reduce (fn [a2 [sname _]]
                        (add-issue a2 :error (stage-location (:stage-name stage))
                                   (str "Duplicate step name: '" sname "'")
                                   :duplicate-step-name))
                      a dupes)))
          acc stages))

(defn- check-no-empty-stages
  [acc stages]
  (reduce (fn [a stage]
            (if (or (nil? (:steps stage)) (empty? (:steps stage)))
              (add-issue a :error (stage-location (:stage-name stage))
                         "Stage has no steps" :empty-stage)
              a))
          acc stages))

(defn- check-stage-names-present
  [acc stages]
  (reduce (fn [a [idx stage]]
            (if (str/blank? (:stage-name stage))
              (add-issue a :error (str "Stage " (inc idx))
                         "Stage is missing :stage-name" :missing-stage-name)
              a))
          acc (map-indexed vector stages)))

(defn- check-step-names-present
  [acc stages]
  (reduce (fn [a stage]
            (reduce (fn [a2 [idx step]]
                      (if (str/blank? (:step-name step))
                        (add-issue a2 :error
                                   (str (stage-location (:stage-name stage)) ", Step " (inc idx))
                                   "Step is missing :step-name" :missing-step-name)
                        a2))
                    a (map-indexed vector (:steps stage))))
          acc stages))

(defn- check-valid-step-types
  [acc stages]
  (let [valid-types #{:shell :docker :docker-compose}]
    (reduce (fn [a stage]
              (reduce (fn [a2 step]
                        (if (and (:type step) (not (contains? valid-types (:type step))))
                          (add-issue a2 :error (step-location (:stage-name stage) (:step-name step))
                                     (str "Invalid step type: " (:type step)
                                          ". Must be one of: " (str/join ", " (map name valid-types)))
                                     :invalid-step-type)
                          a2))
                      a (:steps stage)))
            acc stages)))

;; ---------------------------------------------------------------------------
;; Semantic checks
;; ---------------------------------------------------------------------------

(defn- check-dag-references
  [acc stages]
  (let [stage-names (set (map :stage-name stages))]
    (reduce (fn [a stage]
              (if-let [deps (:depends-on stage)]
                (reduce (fn [a2 dep]
                          (if (not (contains? stage-names dep))
                            (add-issue a2 :error (stage-location (:stage-name stage))
                                       (str "DAG dependency '" dep "' does not reference a valid stage")
                                       :invalid-dag-reference)
                            a2))
                        a deps)
                a))
            acc stages)))

(defn- detect-cycle
  [stages]
  (let [deps-map (reduce (fn [m stage]
                           (assoc m (:stage-name stage)
                                  (set (or (:depends-on stage) []))))
                         {} stages)
        stage-names (set (map :stage-name stages))]
    (loop [remaining stage-names
           visited #{}
           in-stack #{}]
      (if (empty? remaining)
        false
        (let [start (first remaining)
              result (loop [stack [start]
                            vis visited
                            stk in-stack]
                       (if (empty? stack)
                         {:cycle? false :visited vis :in-stack #{}}
                         (let [node (peek stack)]
                           (cond
                             (contains? stk node)
                             {:cycle? true}
                             (contains? vis node)
                             (recur (pop stack) vis stk)
                             :else
                             (let [children (get deps-map node #{})
                                   unvisited (remove vis children)]
                               (if (some stk children)
                                 {:cycle? true}
                                 (recur (into (pop stack) unvisited)
                                        (conj vis node)
                                        (conj stk node))))))))]
          (if (:cycle? result)
            true
            (recur (disj remaining start)
                   (conj visited start)
                   #{})))))))

(defn- check-dag-cycles
  [acc stages]
  (let [has-deps? (some :depends-on stages)]
    (if (and has-deps? (detect-cycle stages))
      (add-issue acc :error "Pipeline"
                 "Circular dependency detected in DAG" :circular-dependency)
      acc)))

(defn- check-docker-image
  [acc stages]
  (reduce (fn [a stage]
            (reduce (fn [a2 step]
                      (if (and (= :docker (:type step))
                               (str/blank? (:image step)))
                        (add-issue a2 :error (step-location (:stage-name stage) (:step-name step))
                                   "Docker step is missing :image" :docker-missing-image)
                        a2))
                    a (:steps stage)))
          acc stages))

(defn- check-timeout-values
  [acc stages]
  (reduce (fn [a stage]
            (reduce (fn [a2 step]
                      (if (and (:timeout step)
                               (not (and (integer? (:timeout step))
                                         (pos? (:timeout step)))))
                        (add-issue a2 :error (step-location (:stage-name stage) (:step-name step))
                                   (str "Timeout must be a positive integer, got: " (:timeout step))
                                   :invalid-timeout)
                        a2))
                    a (:steps stage)))
          acc stages))

(defn- check-matrix-config
  [acc pipeline]
  (if-let [matrix (:matrix pipeline)]
    (let [matrix-keys (set (remove #{:exclude} (keys matrix)))
          acc (reduce (fn [a k]
                        (let [v (get matrix k)]
                          (if (and (not= k :exclude) (not (sequential? v)))
                            (add-issue a :error "Pipeline"
                                       (str "Matrix key '" (name k) "' must have a vector of values")
                                       :invalid-matrix-values)
                            a)))
                      acc (keys matrix))
          acc (if-let [excludes (:exclude matrix)]
                (reduce (fn [a exclude-entry]
                          (reduce (fn [a2 [k _]]
                                    (if (not (contains? matrix-keys k))
                                      (add-issue a2 :error "Pipeline"
                                                 (str "Matrix exclude references unknown key: '"
                                                      (name k) "'")
                                                 :invalid-matrix-exclude)
                                      a2))
                                  a exclude-entry))
                        acc excludes)
                acc)]
      acc)
    acc))

(defn- check-parameters
  [acc pipeline]
  (if-let [params (:parameters pipeline)]
    (reduce (fn [a param]
              (let [loc (str "Parameter '" (or (:name param) "?") "'")
                    valid-types #{:text :choice :boolean}
                    a (if (str/blank? (:name param))
                        (add-issue a :error "Pipeline"
                                   "Parameter missing :name" :parameter-missing-name)
                        a)
                    a (if (nil? (:type param))
                        (add-issue a :error loc
                                   "Parameter missing :type" :parameter-missing-type)
                        (if (not (contains? valid-types (:type param)))
                          (add-issue a :error loc
                                     (str "Invalid parameter type: " (:type param)
                                          ". Must be one of: "
                                          (str/join ", " (map name valid-types)))
                                     :invalid-parameter-type)
                          a))
                    a (if (and (= :choice (:type param))
                               (or (nil? (:choices param))
                                   (empty? (:choices param))))
                        (add-issue a :error loc
                                   "Choice parameter must have :choices"
                                   :choice-missing-choices)
                        a)]
                a))
            acc params)
    acc))

(defn- check-artifacts
  [acc pipeline]
  (if-let [arts (:artifacts pipeline)]
    (reduce (fn [a [idx art]]
              (if (not (string? art))
                (add-issue a :error "Pipeline"
                           (str "Artifact pattern at index " idx
                                " must be a string, got: " (type art))
                           :invalid-artifact-pattern)
                a))
            acc (map-indexed vector arts))
    acc))

(defn- check-notify
  [acc pipeline]
  (if-let [notifs (:notify pipeline)]
    (reduce (fn [a [idx n]]
              (if (nil? (:type n))
                (add-issue a :error (str "Notify entry " (inc idx))
                           "Notification entry missing :type"
                           :notify-missing-type)
                a))
            acc (map-indexed vector notifs))
    acc))

(defn- check-post-actions
  [acc pipeline]
  (if-let [post (:post-actions pipeline)]
    (let [valid-keys #{:always :on-success :on-failure}
          invalid-keys (remove valid-keys (keys post))]
      (reduce (fn [a k]
                (add-issue a :error "Pipeline"
                           (str "Invalid post-action key: " (name k)
                                ". Must be one of: "
                                (str/join ", " (map name valid-keys)))
                           :invalid-post-action-key))
              acc invalid-keys))
    acc))

(defn- check-environment
  [acc stages]
  (reduce (fn [a stage]
            (reduce (fn [a2 step]
                      (if-let [env (:env step)]
                        (if (not (map? env))
                          (add-issue a2 :error
                                     (step-location (:stage-name stage) (:step-name step))
                                     ":env must be a map" :invalid-env-type)
                          a2)
                        a2))
                    a (:steps stage)))
          acc stages))

(defn- check-approval-gates
  [acc stages]
  (reduce (fn [a stage]
            (if-let [approval (:approval stage)]
              (if (not (map? approval))
                (add-issue a :error (stage-location (:stage-name stage))
                           "Approval gate must be a map" :invalid-approval-config)
                a)
              a))
          acc stages))

(defn- check-cache-config
  [acc stages]
  (reduce (fn [a stage]
            (if-let [cache (:cache stage)]
              (cond
                (not (map? cache))
                (add-issue a :error (stage-location (:stage-name stage))
                           "Cache config must be a map" :invalid-cache-config)
                (and (map? cache) (nil? (:key cache)))
                (add-issue a :error (stage-location (:stage-name stage))
                           "Cache config missing :key" :cache-missing-key)
                (and (map? cache) (nil? (:paths cache)))
                (add-issue a :error (stage-location (:stage-name stage))
                           "Cache config missing :paths" :cache-missing-paths)
                :else a)
              a))
          acc stages))

;; ---------------------------------------------------------------------------
;; YAML expression checks
;; ---------------------------------------------------------------------------

(defn- extract-expressions
  [s]
  (when (string? s)
    (let [matcher (re-matcher #"\$\{\{\s*(.+?)\s*\}\}" s)]
      (loop [results []]
        (if (.find matcher)
          (recur (conj results (.group matcher 1)))
          results)))))

(defn- check-expression-syntax
  [acc pipeline format-type]
  (if (not= format-type "yaml")
    acc
    (let [valid-namespaces #{"parameters" "secrets" "env"}
          all-strings (atom [])]
      (walk/postwalk
        (fn [x]
          (when (string? x)
            (swap! all-strings conj x))
          x)
        pipeline)
      (reduce (fn [a s]
                (let [exprs (extract-expressions s)]
                  (reduce (fn [a2 expr]
                            (let [parts (str/split expr #"\." 2)]
                              (if (< (count parts) 2)
                                (add-issue a2 :error "Pipeline"
                                           (str "Expression '${{ " expr " }}' has invalid syntax"
                                                " -- expected namespace.name format")
                                           :invalid-expression-syntax)
                                (let [ns-part (first parts)]
                                  (if (not (contains? valid-namespaces ns-part))
                                    (add-issue a2 :error "Pipeline"
                                               (str "Expression '${{ " expr " }}' references"
                                                    " unknown namespace '" ns-part "'."
                                                    " Valid: " (str/join ", " valid-namespaces))
                                               :invalid-expression-namespace)
                                    a2)))))
                          a exprs)))
              acc @all-strings))))

;; ---------------------------------------------------------------------------
;; Warning-level checks
;; ---------------------------------------------------------------------------

(defn- warn-single-step-stages
  [acc stages]
  (reduce (fn [a stage]
            (if (and (:steps stage) (= 1 (count (:steps stage))))
              (add-issue a :warning (stage-location (:stage-name stage))
                         "Stage has only one step -- consider simplifying"
                         :single-step-stage)
              a))
          acc stages))

(defn- warn-long-timeouts
  [acc stages]
  (reduce (fn [a stage]
            (reduce (fn [a2 step]
                      (if (and (:timeout step) (integer? (:timeout step))
                               (> (:timeout step) 3600000))
                        (add-issue a2 :warning
                                   (step-location (:stage-name stage) (:step-name step))
                                   (str "Very long timeout: " (:timeout step)
                                        "ms (>3600000ms / 1 hour)")
                                   :long-timeout)
                        a2))
                    a (:steps stage)))
          acc stages))

(defn- warn-duplicate-env-vars
  [acc stages]
  (reduce (fn [a stage]
            (let [all-env-keys (mapcat (fn [step]
                                         (when (map? (:env step))
                                           (keys (:env step))))
                                       (:steps stage))
                  freqs (frequencies all-env-keys)
                  dupes (filter (fn [[_ c]] (> c 1)) freqs)]
              (reduce (fn [a2 [k _]]
                        (add-issue a2 :warning (stage-location (:stage-name stage))
                                   (str "Environment variable '" k "' is set in multiple steps")
                                   :duplicate-env-var))
                      a dupes)))
          acc stages))

(defn- warn-missing-description
  [acc pipeline]
  (if (str/blank? (:description pipeline))
    (add-issue acc :warning "Pipeline"
               "Pipeline has no description" :missing-description)
    acc))

(defn- warn-no-source
  [acc pipeline]
  (if (and (nil? (:source pipeline))
           (nil? (:triggers pipeline)))
    (add-issue acc :warning "Pipeline"
               "Pipeline has no source or trigger configuration" :missing-source)
    acc))

;; ---------------------------------------------------------------------------
;; Info gathering
;; ---------------------------------------------------------------------------

(defn- gather-info
  [pipeline format-type]
  (let [stages (or (:stages pipeline) [])
        all-steps (mapcat :steps stages)]
    {:stages (count stages)
     :steps (count all-steps)
     :has-dag? (boolean (some :depends-on stages))
     :has-matrix? (boolean (:matrix pipeline))
     :has-docker? (boolean (some #(= :docker (:type %)) all-steps))
     :has-approval? (boolean (some :approval stages))
     :format format-type}))

;; ---------------------------------------------------------------------------
;; Main lint function
;; ---------------------------------------------------------------------------

(defn lint-pipeline
  "Lint a pipeline data map."
  ([pipeline] (lint-pipeline pipeline "unknown"))
  ([pipeline format-type]
   (let [init {:errors [] :warnings []}
         stages (or (:stages pipeline) [])
         result (-> init
                    (check-stages-present pipeline)
                    (as-> acc
                      (if (and (sequential? (:stages pipeline))
                               (seq (:stages pipeline)))
                        (-> acc
                            (check-stage-names-present stages)
                            (check-stage-names-unique stages)
                            (check-step-names-present stages)
                            (check-step-names-unique-within-stage stages)
                            (check-no-empty-stages stages)
                            (check-valid-step-types stages)
                            (check-dag-references stages)
                            (check-dag-cycles stages)
                            (check-docker-image stages)
                            (check-timeout-values stages)
                            (check-matrix-config pipeline)
                            (check-parameters pipeline)
                            (check-artifacts pipeline)
                            (check-notify pipeline)
                            (check-post-actions pipeline)
                            (check-environment stages)
                            (check-approval-gates stages)
                            (check-cache-config stages)
                            (check-expression-syntax pipeline format-type)
                            (warn-single-step-stages stages)
                            (warn-long-timeouts stages)
                            (warn-duplicate-env-vars stages)
                            (warn-missing-description pipeline)
                            (warn-no-source pipeline))
                        acc)))]
     {:valid? (empty? (:errors result))
      :errors (:errors result)
      :warnings (:warnings result)
      :info (gather-info pipeline format-type)})))

;; ---------------------------------------------------------------------------
;; File-based linting
;; ---------------------------------------------------------------------------

(defn- detect-format
  [file-path]
  (cond
    (str/ends-with? file-path ".clj")  "clj"
    (str/ends-with? file-path ".edn")  "edn"
    (str/ends-with? file-path ".yml")  "yaml"
    (str/ends-with? file-path ".yaml") "yaml"
    (str/ends-with? file-path "Chengisfile") "edn"
    :else "unknown"))

(defn lint-file
  "Lint a pipeline file. Detects format from extension, parses, and lints."
  [file-path]
  (let [f (io/file file-path)]
    (if-not (.exists f)
      {:valid? false
       :errors [{:level :error :location "File"
                 :message (str "File not found: " file-path)
                 :rule :file-not-found}]
       :warnings []
       :info {:format (detect-format file-path)}}
      (let [format-type (detect-format file-path)]
        (try
          (case format-type
            "clj"
            (let [pipeline (dsl/load-pipeline-file file-path)]
              (if (nil? pipeline)
                {:valid? false
                 :errors [{:level :error :location "File"
                           :message "No pipeline definition found in file"
                           :rule :no-pipeline}]
                 :warnings []
                 :info {:format "clj"}}
                (lint-pipeline pipeline "clj")))

            "edn"
            (let [result (chengisfile/parse-chengisfile file-path)]
              (if (:error result)
                {:valid? false
                 :errors [{:level :error :location "File"
                           :message (:error result) :rule :parse-error}]
                 :warnings []
                 :info {:format "edn"}}
                (lint-pipeline (:pipeline result) "edn")))

            "yaml"
            (let [result (yaml/parse-yaml-workflow file-path)]
              (if (:error result)
                {:valid? false
                 :errors [{:level :error :location "File"
                           :message (:error result) :rule :parse-error}]
                 :warnings []
                 :info {:format "yaml"}}
                (lint-pipeline (:pipeline result) "yaml")))

            {:valid? false
             :errors [{:level :error :location "File"
                       :message (str "Unknown file format: " file-path)
                       :rule :unknown-format}]
             :warnings []
             :info {:format "unknown"}})
          (catch Exception e
            {:valid? false
             :errors [{:level :error :location "File"
                       :message (str "Failed to parse: " (.getMessage e))
                       :rule :parse-error}]
             :warnings []
             :info {:format format-type}}))))))

;; ---------------------------------------------------------------------------
;; Content-based linting (for web UI)
;; ---------------------------------------------------------------------------

(defn lint-content
  "Lint pipeline content from a string. Used by the web UI."
  [content format-type]
  (try
    (case format-type
      "edn"
      (let [data (edn/read-string {:readers {}} content)
            {:keys [valid? errors]} (chengisfile/validate-chengisfile data)]
        (if-not valid?
          {:valid? false
           :errors [{:level :error :location "Content"
                     :message (str "Validation failed: "
                                   (str/join "; " errors))
                     :rule :parse-error}]
           :warnings []
           :info {:format "edn"}}
          (let [stages (mapv chengisfile/convert-stage (:stages data))
                pipeline (cond-> {:stages stages}
                           (:description data) (assoc :description (:description data))
                           (:container data)   (assoc :container (:container data))
                           (:matrix data)      (assoc :matrix (:matrix data))
                           (:parameters data)  (assoc :parameters (:parameters data))
                           (:artifacts data)   (assoc :artifacts (:artifacts data))
                           (:notify data)      (assoc :notify (:notify data))
                           (:post data)        (assoc :post-actions (:post data))
                           (:source data)      (assoc :source (:source data)))]
            (lint-pipeline pipeline "edn"))))

      "yaml"
      (let [result (yaml/convert-yaml-to-pipeline content)]
        (if (:error result)
          {:valid? false
           :errors [{:level :error :location "Content"
                     :message (:error result) :rule :parse-error}]
           :warnings []
           :info {:format "yaml"}}
          (lint-pipeline (:pipeline result) "yaml")))

      {:valid? false
       :errors [{:level :error :location "Content"
                 :message (str "Unknown format: " format-type)
                 :rule :unknown-format}]
       :warnings []
       :info {:format format-type}})
    (catch Exception e
      {:valid? false
       :errors [{:level :error :location "Content"
                 :message (str "Failed to parse: " (.getMessage e))
                 :rule :parse-error}]
       :warnings []
       :info {:format format-type}})))
