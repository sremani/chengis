(ns chengis.engine.policy
  "Policy evaluation engine. Evaluates org-scoped policies against build context
   before stage execution. Supports 5 policy types:
     - branch-restriction: Allow/deny based on git branch
     - required-approval: Override approval requirements
     - author-restriction: Allow/deny based on git author
     - time-window: Restrict execution to time windows
     - parameter-restriction: Check build parameters"
  (:require [chengis.db.policy-store :as policy-store]
            [chengis.feature-flags :as feature-flags]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.time LocalTime ZoneId ZonedDateTime DayOfWeek]))

;; ---------------------------------------------------------------------------
;; Glob matching helper
;; ---------------------------------------------------------------------------

(defn- glob-matches?
  "Simple glob matching for branch/author patterns.
   Supports * (any chars) and ? (single char)."
  [pattern value]
  (when (and pattern value)
    (let [regex-str (-> pattern
                        (str/replace "." "\\\\.")
                        (str/replace "*" ".*")
                        (str/replace "?" "."))
          re (re-pattern (str "^" regex-str "$"))]
      (boolean (re-matches re value)))))

(defn- any-glob-matches?
  "Check if any pattern in patterns matches value."
  [patterns value]
  (some #(glob-matches? % value) patterns))

;; ---------------------------------------------------------------------------
;; Context assembly
;; ---------------------------------------------------------------------------

(defn build-evaluation-context
  "Assemble the evaluation context from build context and stage definition."
  [build-ctx stage-def]
  (let [now (ZonedDateTime/now (ZoneId/of "UTC"))]
    {:build-id (:build-id build-ctx)
     :job-id (:job-id build-ctx)
     :org-id (:org-id build-ctx)
     :parameters (or (:parameters build-ctx) {})
     :stage-name (:stage-name stage-def)
     :git-branch (or (:branch build-ctx) (:git-branch build-ctx))
     :git-author (or (:author build-ctx) (:git-author build-ctx))
     :git-commit (or (:commit build-ctx) (:git-commit build-ctx))
     :timestamp (str now)
     :day-of-week (str (.getDayOfWeek now))
     :hour-of-day (.getHour now)}))

;; ---------------------------------------------------------------------------
;; Rule evaluators (all return {:result :allow/:deny :reason "..."})
;; ---------------------------------------------------------------------------

(defn- eval-branch-restriction
  "Evaluate branch restriction rule.
   Rule: {:branches [\"main\" \"release/*\"] :action \"allow\"|\"deny\"}"
  [rule-config ctx]
  (let [branches (:branches rule-config)
        action (keyword (or (:action rule-config) "deny"))
        branch (:git-branch ctx)]
    (if (nil? branch)
      {:result :allow :reason "No branch info available, skipping branch check"}
      (let [matches? (any-glob-matches? branches branch)]
        (cond
          (and (= action :deny) matches?)
          {:result :deny :reason (str "Branch '" branch "' matches denied pattern")}

          (and (= action :allow) (not matches?))
          {:result :deny :reason (str "Branch '" branch "' does not match allowed patterns: " (str/join ", " branches))}

          :else
          {:result :allow :reason "Branch check passed"})))))

(defn- eval-required-approval
  "Evaluate required-approval rule (overrides approval requirements).
   Rule: {:stages [\"deploy-*\"] :min_approvals 2 :approver_group [\"u1\" \"u2\"]}"
  [rule-config ctx]
  (let [stage-patterns (:stages rule-config)
        stage-name (:stage-name ctx)]
    (if (and stage-patterns (any-glob-matches? stage-patterns stage-name))
      {:result :override-approval
       :reason (str "Policy requires enhanced approval for stage '" stage-name "'")
       :min-approvals (:min_approvals rule-config)
       :approver-group (:approver_group rule-config)}
      {:result :allow :reason "Stage does not match approval override patterns"})))

(defn- eval-author-restriction
  "Evaluate author restriction rule.
   Rule: {:authors [\"bot-*\"] :action \"deny\"}"
  [rule-config ctx]
  (let [authors (:authors rule-config)
        action (keyword (or (:action rule-config) "deny"))
        author (:git-author ctx)]
    (if (nil? author)
      {:result :allow :reason "No author info available, skipping author check"}
      (let [matches? (any-glob-matches? authors author)]
        (cond
          (and (= action :deny) matches?)
          {:result :deny :reason (str "Author '" author "' matches denied pattern")}

          (and (= action :allow) (not matches?))
          {:result :deny :reason (str "Author '" author "' does not match allowed patterns")}

          :else
          {:result :allow :reason "Author check passed"})))))

(defn- eval-time-window
  "Evaluate time window restriction.
   Rule: {:timezone \"America/New_York\" :days [\"MONDAY\" \"TUESDAY\" ...]
          :start_hour 9 :end_hour 17 :action \"allow-only\"}"
  [rule-config _ctx]
  (try
    (let [tz (ZoneId/of (or (:timezone rule-config) "UTC"))
          now (ZonedDateTime/now tz)
          day-name (str (.getDayOfWeek now))
          hour (.getHour (.toLocalTime now))
          allowed-days (set (map str/upper-case (:days rule-config)))
          start-hour (or (:start_hour rule-config) 0)
          end-hour (or (:end_hour rule-config) 24)
          day-ok? (or (empty? allowed-days) (contains? allowed-days day-name))
          hour-ok? (and (>= hour start-hour) (< hour end-hour))
          in-window? (and day-ok? hour-ok?)
          action (keyword (or (:action rule-config) "allow-only"))]
      (if (= action :allow-only)
        (if in-window?
          {:result :allow :reason "Within allowed time window"}
          {:result :deny :reason (str "Outside allowed time window ("
                                      start-hour ":00-" end-hour ":00 "
                                      (str/join "," (:days rule-config)) " "
                                      (:timezone rule-config) ")")})
        ;; deny-during
        (if in-window?
          {:result :deny :reason "Within denied time window"}
          {:result :allow :reason "Outside denied time window"})))
    (catch Exception e
      {:result :allow :reason (str "Time window evaluation error: " (.getMessage e))})))

(defn- eval-parameter-restriction
  "Evaluate parameter restriction.
   Rule: {:parameter \"force\" :operator \"equals\" :value \"true\" :action \"deny\"}"
  [rule-config ctx]
  (let [param-name (:parameter rule-config)
        operator (keyword (or (:operator rule-config) "equals"))
        expected (:value rule-config)
        action (keyword (or (:action rule-config) "deny"))
        actual (get (:parameters ctx) (keyword param-name)
                    (get (:parameters ctx) param-name))]
    (let [matches? (case operator
                     :equals (= (str actual) (str expected))
                     :not-equals (not= (str actual) (str expected))
                     :contains (and actual (str/includes? (str actual) (str expected)))
                     :exists (some? actual)
                     :not-exists (nil? actual)
                     false)]
      (cond
        (and (= action :deny) matches?)
        {:result :deny :reason (str "Parameter '" param-name "' " (name operator) " '" expected "' → denied")}

        (and (= action :allow) (not matches?))
        {:result :deny :reason (str "Parameter '" param-name "' does not match required condition")}

        :else
        {:result :allow :reason "Parameter check passed"}))))

;; ---------------------------------------------------------------------------
;; Dispatcher
;; ---------------------------------------------------------------------------

(defn- evaluate-single-policy
  "Evaluate one policy against context. Returns {:result :allow/:deny/:override-approval ...}"
  [policy ctx]
  (try
    (let [rules (:rules policy)
          policy-type (:policy-type policy)]
      (case policy-type
        "branch-restriction"   (eval-branch-restriction rules ctx)
        "required-approval"    (eval-required-approval rules ctx)
        "author-restriction"   (eval-author-restriction rules ctx)
        "time-window"          (eval-time-window rules ctx)
        "parameter-restriction" (eval-parameter-restriction rules ctx)
        {:result :allow :reason (str "Unknown policy type: " policy-type)}))
    (catch Exception e
      (log/error "Policy evaluation error" {:policy-id (:id policy)
                                             :error (.getMessage e)})
      {:result :error :reason (str "Evaluation error: " (.getMessage e))})))

;; ---------------------------------------------------------------------------
;; Main integration function
;; ---------------------------------------------------------------------------

(defn check-stage-policies!
  "Evaluate all applicable policies before stage execution.
   Returns {:proceed true/false :reason ... :approval-overrides [...]}.
   Same contract as approval/check-stage-approval!"
  [system build-ctx stage-def]
  (let [config (:config system)]
    ;; If feature flag is disabled, skip policy evaluation
    (if-not (feature-flags/enabled? config :policy-engine)
      {:proceed true}
      (let [ds (:db system)
            org-id (:org-id build-ctx)
            policies (try
                       (policy-store/list-policies ds :org-id org-id :enabled-only true)
                       (catch Exception e
                         (log/warn "Failed to load policies:" (.getMessage e))
                         []))]
        (if (empty? policies)
          {:proceed true}
          (let [ctx (build-evaluation-context build-ctx stage-def)
                results (atom [])
                approval-overrides (atom [])]
            ;; Evaluate each policy in priority order
            (doseq [policy policies]
              (let [eval-result (evaluate-single-policy policy ctx)]
                ;; Log evaluation
                (try
                  (policy-store/log-evaluation! ds
                    {:policy-id (:id policy)
                     :build-id (:build-id ctx)
                     :stage-name (:stage-name ctx)
                     :result (:result eval-result)
                     :reason (:reason eval-result)
                     :context ctx})
                  (catch Exception e
                    (log/warn "Failed to log policy evaluation:" (.getMessage e))))
                (swap! results conj (assoc eval-result :policy policy))
                ;; Collect approval overrides
                (when (= :override-approval (:result eval-result))
                  (swap! approval-overrides conj eval-result))))
            ;; Check for any denials
            (if-let [denial (first (filter #(= :deny (:result %)) @results))]
              {:proceed false
               :reason (str "Policy '" (get-in denial [:policy :name]) "': " (:reason denial))}
              {:proceed true
               :approval-overrides @approval-overrides})))))))

(defn apply-approval-overrides
  "Apply policy-driven approval overrides to a stage definition.
   Increases min-approvals and merges approver groups."
  [stage-def overrides]
  (if (empty? overrides)
    stage-def
    (let [max-approvals (apply max (keep :min-approvals overrides))
          merged-group (vec (distinct (mapcat :approver-group overrides)))]
      (cond-> stage-def
        max-approvals
        (assoc-in [:approval :min-approvals]
                  (max (get-in stage-def [:approval :min-approvals] 1)
                       max-approvals))
        (seq merged-group)
        (assoc-in [:approval :approver-group] merged-group)))))
