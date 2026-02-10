(ns chengis.db.pr-check-store
  "Persistence for PR/MR status checks and their results.
   Tracks required checks per job and per-build check outcomes
   for enforcing PR merge requirements."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- normalize-check [row]
  (when row
    (-> row
        (update :required #(if (number? %) (pos? %) (boolean %))))))

(defn- normalize-result [row]
  (when row
    (update row :status util/ensure-keyword)))

;; ---------------------------------------------------------------------------
;; PR Status Checks (per-job configuration)
;; ---------------------------------------------------------------------------

(defn create-check!
  "Register a required status check for a job.
   check-name is the unique identifier (e.g., 'chengis/build', 'chengis/test').
   Returns the check map."
  [ds {:keys [job-id org-id check-name description required]
       :or {required true org-id "default-org"}}]
  (let [id (util/generate-id)
        row {:id id
             :job-id job-id
             :org-id org-id
             :check-name check-name
             :description description
             :required (if required 1 0)}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :pr-status-checks
                   :values [row]}))
    (normalize-check row)))

(defn list-checks
  "List status checks for a job. When org-id is provided, scopes to that org."
  [ds job-id & {:keys [org-id]}]
  (mapv normalize-check
    (jdbc/execute! ds
      (sql/format (cond-> {:select :*
                           :from :pr-status-checks
                           :where [:= :job-id job-id]
                           :order-by [[:created-at :asc]]}
                    org-id (assoc :where [:and [:= :job-id job-id] [:= :org-id org-id]])))
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-check
  "Get a single check configuration by ID."
  [ds check-id & {:keys [org-id]}]
  (normalize-check
    (jdbc/execute-one! ds
      (sql/format (cond-> {:select :*
                           :from :pr-status-checks
                           :where [:= :id check-id]}
                    org-id (assoc :where [:and [:= :id check-id] [:= :org-id org-id]])))
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-check!
  "Remove a check configuration. When org-id is provided, verifies ownership."
  [ds check-id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :pr-status-checks
                              :where (if org-id
                                       [:and [:= :id check-id] [:= :org-id org-id]]
                                       [:= :id check-id])}))]
    (pos? (:next.jdbc/update-count result 0))))

(defn update-check!
  "Update a check's description or required status."
  [ds check-id {:keys [description required]} & {:keys [org-id]}]
  (let [updates (cond-> {}
                  (some? description) (assoc :description description)
                  (some? required)    (assoc :required (if required 1 0)))]
    (when (seq updates)
      (jdbc/execute-one! ds
        (sql/format {:update :pr-status-checks
                     :set updates
                     :where (if org-id
                              [:and [:= :id check-id] [:= :org-id org-id]]
                              [:= :id check-id])})))))

;; ---------------------------------------------------------------------------
;; PR Check Results (per-build outcomes)
;; ---------------------------------------------------------------------------

(defn record-check-result!
  "Record a check result for a build.
   Uses upsert: if (build-id, check-name) exists, updates status.
   Returns the result map."
  [ds {:keys [build-id job-id org-id check-name status target-url
              description commit-sha pr-number repo-url]
       :or {status "pending" org-id "default-org"}}]
  (let [id (util/generate-id)
        now (str (java.time.Instant/now))
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id org-id
             :check-name check-name
             :status (name status)
             :target-url target-url
             :description description
             :commit-sha commit-sha
             :pr-number pr-number
             :repo-url repo-url
             :started-at (when (= (name status) "pending") now)
             :completed-at (when (#{"success" "failure" "error"} (name status)) now)}]
    ;; Try insert, on conflict update
    (jdbc/execute-one! ds
      [(str "INSERT INTO pr_check_results (id, build_id, job_id, org_id, check_name, status, "
            "target_url, description, commit_sha, pr_number, repo_url, started_at, completed_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT (build_id, check_name) DO UPDATE SET "
            "status = excluded.status, "
            "target_url = COALESCE(excluded.target_url, pr_check_results.target_url), "
            "description = COALESCE(excluded.description, pr_check_results.description), "
            "completed_at = excluded.completed_at")
       id build-id job-id org-id check-name (name status)
       target-url description commit-sha pr-number repo-url
       (:started-at row) (:completed-at row)])
    (normalize-result row)))

(defn get-build-check-results
  "Get all check results for a build."
  [ds build-id]
  (mapv normalize-result
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :pr-check-results
                   :where [:= :build-id build-id]
                   :order-by [[:check-name :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-commit-check-results
  "Get all check results for a commit SHA across all builds."
  [ds commit-sha & {:keys [org-id]}]
  (mapv normalize-result
    (jdbc/execute! ds
      (sql/format (cond-> {:select :*
                           :from :pr-check-results
                           :where [:= :commit-sha commit-sha]
                           :order-by [[:created-at :desc]]}
                    org-id (assoc :where [:and [:= :commit-sha commit-sha]
                                              [:= :org-id org-id]])))
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn all-required-checks-passing?
  "Check if all required checks for a job are passing for a given build.
   Returns {:passing? bool :total int :passed int :checks [...]}."
  [ds job-id build-id]
  (let [required-checks (filter :required (list-checks ds job-id))
        results (get-build-check-results ds build-id)
        result-map (into {} (map (juxt :check-name identity) results))
        check-statuses (mapv (fn [check]
                               (let [result (get result-map (:check-name check))]
                                 {:check-name (:check-name check)
                                  :required true
                                  :status (or (:status result) :missing)
                                  :passing? (= :success (:status result))}))
                             required-checks)
        passed (count (filter :passing? check-statuses))]
    {:passing? (and (pos? (count required-checks))
                    (= passed (count required-checks)))
     :total (count required-checks)
     :passed passed
     :checks check-statuses}))

(defn cleanup-old-results!
  "Delete check results older than retention-days."
  [ds retention-days]
  (let [cutoff (str (.minus (java.time.Instant/now)
                            (java.time.Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :pr-check-results
                              :where [:< :created-at cutoff]}))]
    (:next.jdbc/update-count result 0)))
