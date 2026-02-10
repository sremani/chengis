(ns chengis.engine.branch-overrides
  "Branch-based pipeline overrides.
   Allows different pipeline behavior per branch pattern, e.g.:
   - release/* triggers deploy stages
   - main runs full pipeline
   - feature/* skips deploy

   Branch overrides are stored as EDN on the job record (branch_overrides column).
   Format:
   [{:pattern \"release/*\"
     :stages [{:stage-name \"Deploy\" :steps [...]}]
     :skip-stages [\"Performance Test\"]
     :parameters {:env \"production\"}}
    {:pattern \"feature/*\"
     :skip-stages [\"Deploy\"]}]

   Pattern matching supports:
   - Exact match: \"main\"
   - Glob: \"release/*\", \"feature/**\"
   - Regex (prefix with ~): \"~release/v\\\\d+\""
  (:require [clojure.string :as str]
            [chengis.feature-flags :as feature-flags]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Pattern matching
;; ---------------------------------------------------------------------------

(defn- glob->regex
  "Convert a glob pattern to a regex pattern.
   * matches anything except /
   ** matches anything including /"
  [glob]
  (-> glob
      (str/replace "." "\\.")
      (str/replace "**" "⭐⭐")
      (str/replace "*" "[^/]*")
      (str/replace "⭐⭐" ".*")))

(defn branch-matches?
  "Check if a branch name matches a pattern.
   Supports exact, glob (*/**), and regex (~pattern) matching."
  [pattern branch]
  (when (and pattern branch)
    (cond
      ;; Regex pattern (prefixed with ~)
      (str/starts-with? pattern "~")
      (try
        (boolean (re-matches (re-pattern (subs pattern 1)) branch))
        (catch Exception _ false))

      ;; Glob pattern (contains * or ?)
      (or (str/includes? pattern "*") (str/includes? pattern "?"))
      (try
        (boolean (re-matches (re-pattern (str "^" (glob->regex pattern) "$")) branch))
        (catch Exception _ false))

      ;; Exact match
      :else
      (= pattern branch))))

;; ---------------------------------------------------------------------------
;; Override resolution
;; ---------------------------------------------------------------------------

(defn find-matching-override
  "Find the first matching branch override for a given branch.
   Returns the override map or nil if no match."
  [overrides branch]
  (when (and (seq overrides) (seq branch))
    (first (filter #(branch-matches? (:pattern %) branch) overrides))))

(defn apply-override
  "Apply a branch override to a pipeline definition.
   Supported override actions:
   - :stages — additional stages to append
   - :skip-stages — stage names to remove
   - :parameters — parameter overrides (merged with build params)
   - :environment — env var overrides
   Returns the modified pipeline def."
  [pipeline-def override]
  (if-not override
    pipeline-def
    (cond-> pipeline-def
      ;; Skip stages
      (seq (:skip-stages override))
      (update :stages (fn [stages]
                        (let [skip-set (set (:skip-stages override))]
                          (vec (remove #(contains? skip-set (:stage-name %)) stages)))))

      ;; Append additional stages
      (seq (:stages override))
      (update :stages (fn [stages]
                        (vec (concat stages (:stages override)))))

      ;; Merge parameters
      (seq (:parameters override))
      (update :parameters merge (:parameters override))

      ;; Merge environment
      (seq (:environment override))
      (update :environment merge (:environment override)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn resolve-pipeline
  "Resolve the effective pipeline for a branch, applying any matching overrides.
   When the branch-overrides feature flag is disabled, returns the pipeline unchanged.

   Arguments:
     config       - system config (for feature flag check)
     pipeline-def - the base pipeline definition map
     branch       - the current branch name
     overrides    - seq of override maps (from job's branch_overrides)

   Returns the resolved pipeline definition."
  [config pipeline-def branch overrides]
  (if-not (feature-flags/enabled? config :branch-overrides)
    pipeline-def
    (if-let [override (find-matching-override overrides branch)]
      (do
        (log/info "Applying branch override for pattern" (:pattern override)
                  "on branch" branch)
        (apply-override pipeline-def override))
      pipeline-def)))

(defn parse-overrides
  "Parse branch overrides from a serialized string (EDN).
   Returns a seq of override maps, or empty vec on error."
  [overrides-str]
  (if (str/blank? overrides-str)
    []
    (try
      (let [parsed (read-string overrides-str)]
        (if (sequential? parsed) (vec parsed) []))
      (catch Exception e
        (log/warn "Failed to parse branch overrides:" (.getMessage e))
        []))))

(defn validate-overrides
  "Validate a seq of override maps. Returns {:valid? bool :errors [...]}."
  [overrides]
  (let [errors (atom [])]
    (doseq [[idx override] (map-indexed vector overrides)]
      (when-not (:pattern override)
        (swap! errors conj (str "Override " idx ": missing :pattern")))
      (when (and (:skip-stages override)
                 (not (sequential? (:skip-stages override))))
        (swap! errors conj (str "Override " idx ": :skip-stages must be a sequence")))
      (when (and (:stages override)
                 (not (sequential? (:stages override))))
        (swap! errors conj (str "Override " idx ": :stages must be a sequence")))
      (when (and (:parameters override)
                 (not (map? (:parameters override))))
        (swap! errors conj (str "Override " idx ": :parameters must be a map"))))
    {:valid? (empty? @errors)
     :errors @errors}))
