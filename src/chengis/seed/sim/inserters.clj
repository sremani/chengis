(ns chengis.seed.sim.inserters
  "Raw SQL insert functions for seeding simulation data with explicit timestamps.
   Bypasses store functions to allow backdated created_at values."
  (:require [next.jdbc :as jdbc]
            [chengis.util :as util]
            [chengis.db.user-store :as user-store]))

;; ---------------------------------------------------------------------------
;; Users
;; ---------------------------------------------------------------------------

(defn insert-user!
  "Insert a user with explicit timestamps. Returns the user ID."
  [ds {:keys [username password role created-at]}]
  (let [id   (util/generate-id)
        hash (user-store/hash-password password)]
    (jdbc/execute-one! ds
      ["INSERT INTO users (id, username, password_hash, role, active, session_version, failed_attempts, created_at, updated_at)
        VALUES (?, ?, ?, ?, 1, 1, 0, ?, ?)"
       id username hash role created-at created-at])
    id))

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(defn insert-job!
  "Insert a job with pipeline definition. Returns the job ID."
  [ds {:keys [name pipeline triggers created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO jobs (id, name, pipeline, triggers, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)"
       id name
       (util/serialize-edn pipeline)
       (util/serialize-edn triggers)
       created-at created-at])
    id))

;; ---------------------------------------------------------------------------
;; Builds
;; ---------------------------------------------------------------------------

(defn insert-build!
  "Insert a build with explicit timestamps. Returns the build ID."
  [ds {:keys [job-id build-number status trigger-type
              started-at completed-at created-at
              git-branch git-commit git-commit-short git-author git-message]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO builds (id, job_id, build_number, status, trigger_type,
                            started_at, completed_at, created_at,
                            git_branch, git_commit, git_commit_short, git_author, git_message,
                            pipeline_source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'server')"
       id job-id build-number status trigger-type
       started-at completed-at created-at
       git-branch git-commit git-commit-short git-author git-message])
    id))

;; ---------------------------------------------------------------------------
;; Build stages
;; ---------------------------------------------------------------------------

(defn insert-stage!
  "Insert a build stage with explicit timestamps. Returns the stage ID."
  [ds {:keys [build-id stage-name status started-at completed-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO build_stages (id, build_id, stage_name, status, started_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?)"
       id build-id stage-name status started-at completed-at])
    id))

;; ---------------------------------------------------------------------------
;; Build steps
;; ---------------------------------------------------------------------------

(defn insert-step!
  "Insert a build step. Returns the step ID."
  [ds {:keys [build-id stage-name step-name status exit-code stdout stderr started-at completed-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO build_steps (id, build_id, stage_name, step_name, status, exit_code, stdout, stderr, started_at, completed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id build-id stage-name step-name status exit-code stdout stderr started-at completed-at])
    id))

;; ---------------------------------------------------------------------------
;; Build logs
;; ---------------------------------------------------------------------------

(defn insert-log!
  "Insert a build log entry with explicit timestamp."
  [ds {:keys [build-id timestamp level source message]}]
  (jdbc/execute-one! ds
    ["INSERT INTO build_logs (build_id, timestamp, level, source, message)
      VALUES (?, ?, ?, ?, ?)"
     build-id timestamp level source message]))

;; ---------------------------------------------------------------------------
;; Webhook events
;; ---------------------------------------------------------------------------

(defn insert-webhook-event!
  "Insert a webhook event with explicit timestamp. Returns the event ID."
  [ds {:keys [provider event-type repo-url repo-name branch commit-sha
              signature-valid status matched-jobs triggered-builds
              payload-size processing-ms created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO webhook_events (id, provider, event_type, repo_url, repo_name, branch, commit_sha,
                                    signature_valid, status, matched_jobs, triggered_builds,
                                    payload_size, processing_ms, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id provider event-type repo-url repo-name branch commit-sha
       signature-valid status matched-jobs triggered-builds
       payload-size processing-ms created-at])
    id))

;; ---------------------------------------------------------------------------
;; Audit logs
;; ---------------------------------------------------------------------------

(defn insert-audit!
  "Insert an audit log entry with explicit timestamp. Returns the audit ID."
  [ds {:keys [user-id username action resource-type resource-id detail
              ip-address timestamp]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO audit_logs (id, timestamp, user_id, username, action, resource_type, resource_id, detail, ip_address)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id timestamp user-id username action resource-type resource-id
       (util/serialize-edn detail) ip-address])
    id))

;; ---------------------------------------------------------------------------
;; Approval gates
;; ---------------------------------------------------------------------------

(defn insert-approval-gate!
  "Insert an approval gate with explicit timestamps. Returns the gate ID."
  [ds {:keys [build-id stage-name status required-role message
              approved-by approved-at rejected-by rejected-at
              timeout-minutes created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO approval_gates (id, build_id, stage_name, status, required_role, message,
                                    timeout_minutes, approved_by, approved_at, rejected_by, rejected_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id build-id stage-name status required-role message
       (or timeout-minutes 1440) approved-by approved-at rejected-by rejected-at created-at])
    id))

;; ---------------------------------------------------------------------------
;; Table cleanup (for re-seeding)
;; ---------------------------------------------------------------------------

(defn clear-all-tables!
  "Delete all data from simulation-relevant tables in correct FK order."
  [ds]
  (doseq [table ["approval_gates" "build_logs" "build_steps" "build_stages"
                  "webhook_events" "audit_logs" "login_attempts"
                  "api_tokens" "builds" "jobs" "users"]]
    (jdbc/execute-one! ds [(str "DELETE FROM " table)])))
