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
;; Analytics
;; ---------------------------------------------------------------------------

(defn insert-build-analytics!
  "Insert a build analytics aggregation row."
  [ds {:keys [org-id job-id period-type period-start period-end
              total-builds success-count failure-count aborted-count
              success-rate avg-duration-s p50-duration-s p90-duration-s
              p99-duration-s max-duration-s computed-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO build_analytics (id, org_id, job_id, period_type, period_start, period_end,
                                     total_builds, success_count, failure_count, aborted_count,
                                     success_rate, avg_duration_s, p50_duration_s, p90_duration_s,
                                     p99_duration_s, max_duration_s, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id org-id job-id period-type period-start period-end
       total-builds success-count failure-count (or aborted-count 0)
       success-rate avg-duration-s p50-duration-s p90-duration-s
       p99-duration-s max-duration-s computed-at])
    id))

(defn insert-stage-analytics!
  "Insert a stage analytics aggregation row."
  [ds {:keys [org-id job-id stage-name period-type period-start period-end
              total-runs success-count failure-count
              avg-duration-s p90-duration-s max-duration-s
              flakiness-score computed-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO stage_analytics (id, org_id, job_id, stage_name, period_type, period_start, period_end,
                                     total_runs, success_count, failure_count,
                                     avg_duration_s, p90_duration_s, max_duration_s,
                                     flakiness_score, computed_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id org-id job-id stage-name period-type period-start period-end
       total-runs success-count failure-count
       avg-duration-s p90-duration-s max-duration-s
       (or flakiness-score 0.0) computed-at])
    id))

;; ---------------------------------------------------------------------------
;; Environments
;; ---------------------------------------------------------------------------

(defn insert-environment!
  "Insert an environment (dev/staging/prod). Returns the environment ID."
  [ds {:keys [org-id name slug env-order description
              requires-approval auto-promote created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO environments (id, org_id, name, slug, env_order, description,
                                   requires_approval, auto_promote, locked, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)"
       id org-id name slug env-order description
       (if requires-approval 1 0) (if auto-promote 1 0) created-at created-at])
    id))

;; ---------------------------------------------------------------------------
;; Deployments
;; ---------------------------------------------------------------------------

(defn insert-deployment!
  "Insert a deployment record. Returns the deployment ID."
  [ds {:keys [org-id environment-id build-id status initiated-by
              started-at completed-at created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO deployments (id, org_id, environment_id, build_id, status,
                                  initiated_by, started_at, completed_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id org-id environment-id build-id status
       initiated-by started-at completed-at created-at])
    id))

(defn insert-deployment-step!
  "Insert a deployment step. Returns the step ID."
  [ds {:keys [deployment-id step-name step-order status
              started-at completed-at output created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO deployment_steps (id, deployment_id, step_name, step_order, status,
                                       started_at, completed_at, output, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id deployment-id step-name step-order status
       started-at completed-at output (or created-at started-at)])
    id))

;; ---------------------------------------------------------------------------
;; Promotions
;; ---------------------------------------------------------------------------

(defn insert-promotion!
  "Insert an artifact promotion. Returns the promotion ID."
  [ds {:keys [org-id build-id from-environment-id to-environment-id
              status promoted-by promoted-at created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO artifact_promotions (id, org_id, build_id, from_environment_id, to_environment_id,
                                          status, promoted_by, promoted_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id org-id build-id from-environment-id to-environment-id
       status promoted-by promoted-at created-at])
    id))

(defn insert-environment-artifact!
  "Insert an environment artifact (current deployed build). Returns the ID."
  [ds {:keys [org-id environment-id build-id status deployed-at created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO environment_artifacts (id, org_id, environment_id, build_id,
                                            status, deployed_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
       id org-id environment-id build-id
       (or status "active") deployed-at (or created-at deployed-at)])
    id))

;; ---------------------------------------------------------------------------
;; IaC projects and plans
;; ---------------------------------------------------------------------------

(defn insert-iac-project!
  "Insert an IaC project. Returns the project ID."
  [ds {:keys [org-id job-id tool-type working-dir created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO iac_projects (id, org_id, job_id, tool_type, working_dir, auto_detect, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 1, ?, ?)"
       id org-id job-id tool-type (or working-dir ".") created-at created-at])
    id))

(defn insert-iac-plan!
  "Insert an IaC plan. Returns the plan ID."
  [ds {:keys [org-id project-id build-id action status
              resources-add resources-change resources-destroy
              output duration-ms initiated-by created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO iac_plans (id, org_id, project_id, build_id, action, status,
                                resources_add, resources_change, resources_destroy,
                                output, duration_ms, initiated_by, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
       id org-id project-id build-id action status
       (or resources-add 0) (or resources-change 0) (or resources-destroy 0)
       output duration-ms initiated-by created-at])
    id))

(defn insert-iac-cost-estimate!
  "Insert an IaC cost estimate. Returns the estimate ID."
  [ds {:keys [org-id plan-id total-monthly total-hourly currency created-at]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      ["INSERT INTO iac_cost_estimates (id, org_id, plan_id, total_monthly, total_hourly, currency, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)"
       id org-id plan-id total-monthly total-hourly (or currency "USD") created-at])
    id))

;; ---------------------------------------------------------------------------
;; Table cleanup (for re-seeding)
;; ---------------------------------------------------------------------------

(defn clear-all-tables!
  "Delete all data from simulation-relevant tables in correct FK order."
  [ds]
  (doseq [table ["iac_cost_estimates" "iac_plans" "iac_states" "iac_state_locks" "iac_projects"
                  "deployment_steps" "deployments" "environment_artifacts" "artifact_promotions" "environments"
                  "build_analytics" "stage_analytics"
                  "approval_gates" "build_logs" "build_steps" "build_stages"
                  "webhook_events" "audit_logs" "login_attempts"
                  "api_tokens" "builds" "jobs" "users"]]
    (jdbc/execute-one! ds [(str "DELETE FROM " table)])))
