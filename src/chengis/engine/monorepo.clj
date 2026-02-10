(ns chengis.engine.monorepo
  "Monorepo support — path-based trigger filtering.
   When a job has path filters configured, it will only be triggered
   by webhooks when the changed files match at least one include pattern
   and do not match any exclude pattern.

   Path filter format (stored as EDN on jobs.path_filters):
   {:include [\"src/backend/**\" \"shared/**\"]
    :exclude [\"docs/**\" \"*.md\"]}

   Uses GitHub/GitLab webhook payloads to extract changed files.
   Falls back to git diff when file list is not in the webhook payload."
  (:require [clojure.string :as str]
            [chengis.engine.process :as process]
            [chengis.feature-flags :as feature-flags]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Glob matching (reuses pattern from branch_overrides)
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

(defn path-matches?
  "Check if a file path matches a glob pattern."
  [pattern path]
  (when (and pattern path)
    (try
      (boolean (re-matches (re-pattern (str "^" (glob->regex pattern) "$")) path))
      (catch Exception _ false))))

;; ---------------------------------------------------------------------------
;; Changed file extraction from webhook payloads
;; ---------------------------------------------------------------------------

(defn extract-changed-files-github
  "Extract changed file paths from a GitHub push webhook payload.
   GitHub includes added/removed/modified files per commit."
  [payload]
  (when payload
    (let [commits (get payload "commits" [])]
      (->> commits
           (mapcat (fn [commit]
                     (concat (get commit "added" [])
                             (get commit "removed" [])
                             (get commit "modified" []))))
           (distinct)
           (vec)))))

(defn extract-changed-files-gitlab
  "Extract changed file paths from a GitLab push webhook payload.
   GitLab includes added/removed/modified files per commit."
  [payload]
  (when payload
    (let [commits (get payload "commits" [])]
      (->> commits
           (mapcat (fn [commit]
                     (concat (get commit "added" [])
                             (get commit "removed" [])
                             (get commit "modified" []))))
           (distinct)
           (vec)))))

(defn extract-changed-files
  "Extract changed files from a webhook payload, auto-detecting provider.
   Always returns a vector (never nil)."
  [provider payload]
  (or (case provider
        :github (extract-changed-files-github payload)
        :gitlab (extract-changed-files-gitlab payload)
        nil)
      []))

;; ---------------------------------------------------------------------------
;; Git diff fallback
;; ---------------------------------------------------------------------------

(defn get-changed-files-git
  "Get changed files using git diff when webhook payload doesn't include them.
   Compares commit against its parent."
  [workspace-dir commit-sha]
  (when (and workspace-dir commit-sha)
    (try
      (let [result (process/execute-command
                     {:command (str "git diff --name-only " commit-sha "~1 " commit-sha)
                      :dir workspace-dir
                      :timeout 30000})]
        (when (zero? (:exit-code result))
          (->> (str/split-lines (str/trim (:stdout result)))
               (remove str/blank?)
               (vec))))
      (catch Exception e
        (log/debug "git diff failed:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; Path filter evaluation
;; ---------------------------------------------------------------------------

(defn should-trigger?
  "Evaluate whether a job should be triggered based on path filters and changed files.
   Returns true if:
   - No path filters configured (all changes trigger)
   - Any changed file matches an include pattern AND no exclude pattern
   - Changed files list is empty/nil (assume trigger — fail-open for safety)

   path-filters: {:include [\"src/**\"] :exclude [\"docs/**\"]}
   changed-files: [\"src/main.clj\" \"docs/README.md\"]"
  [path-filters changed-files]
  (cond
    ;; No filters = always trigger
    (or (nil? path-filters) (empty? path-filters))
    true

    ;; No changed files info = trigger (fail-open)
    (nil? changed-files)
    true

    ;; Empty changed files list = don't trigger (no changes)
    (empty? changed-files)
    false

    :else
    (let [includes (or (:include path-filters) ["**"])
          excludes (or (:exclude path-filters) [])
          ;; A file triggers if it matches any include AND no exclude
          file-triggers? (fn [path]
                           (and (some #(path-matches? % path) includes)
                                (not-any? #(path-matches? % path) excludes)))]
      (boolean (some file-triggers? changed-files)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn filter-jobs-by-paths
  "Filter a list of jobs to only those that should be triggered
   based on their path filters and the changed files.
   When monorepo-filtering flag is disabled, returns all jobs."
  [config jobs changed-files]
  (if-not (feature-flags/enabled? config :monorepo-filtering)
    jobs
    (filter (fn [job]
              (let [filters (:path-filters job)]
                (if (should-trigger? filters changed-files)
                  (do (log/debug "Job" (:name job) "triggered by path filter match")
                      true)
                  (do (log/info "Job" (:name job) "skipped — no path filter match")
                      false))))
            jobs)))

(defn validate-path-filters
  "Validate path filter configuration. Returns {:valid? bool :errors [...]}."
  [path-filters]
  (let [errors (atom [])]
    (when (and (:include path-filters) (not (sequential? (:include path-filters))))
      (swap! errors conj ":include must be a sequence of glob patterns"))
    (when (and (:exclude path-filters) (not (sequential? (:exclude path-filters))))
      (swap! errors conj ":exclude must be a sequence of glob patterns"))
    (doseq [p (concat (:include path-filters) (:exclude path-filters))]
      (when-not (string? p)
        (swap! errors conj (str "Pattern must be a string, got: " (type p)))))
    {:valid? (empty? @errors)
     :errors @errors}))
