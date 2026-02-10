(ns chengis.web.handlers
  (:require [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.build-event-store :as build-event-store]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.db.notification-store :as notification-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.db.user-store :as user-store]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.cleanup :as cleanup]
            [chengis.engine.events :as events]
            [chengis.web.auth :as auth]
            [chengis.web.views.dashboard :as v-dashboard]
            [chengis.web.views.jobs :as v-jobs]
            [chengis.web.views.admin :as v-admin]
            [chengis.web.views.builds :as v-builds]
            [chengis.web.views.trigger-form :as v-trigger-form]
            [chengis.web.views.agents :as v-agents]
            [chengis.web.views.login :as v-login]
            [chengis.web.views.audit :as v-audit]
            [chengis.web.views.users :as v-users]
            [chengis.web.views.webhooks :as v-webhooks]
            [chengis.web.views.tokens :as v-tokens]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.web.alerts :as alerts]
            [chengis.db.audit-store :as audit-store]
            [chengis.metrics :as metrics]
            [chengis.engine.retention :as retention]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.distributed.build-queue :as bq]
            [chengis.distributed.dispatcher :as dispatcher]
            [chengis.feature-flags :as feature-flags]
            [chengis.web.account-lockout :as lockout]
            [chengis.db.approval-store :as approval-store]
            [chengis.db.template-store :as template-store]
            [chengis.db.org-store :as org-store]
            [chengis.db.backup :as backup]
            [chengis.db.audit-export :as audit-export]
            [chengis.web.views.approvals :as v-approvals]
            [chengis.web.views.templates :as v-templates]
            [chengis.web.views.compliance :as v-compliance]
            [chengis.db.compliance-store :as compliance-store]
            [chengis.db.policy-store :as policy-store]
            [chengis.db.plugin-policy-store :as plugin-policy-store]
            [chengis.db.docker-policy-store :as docker-policy-store]
            [chengis.engine.compliance :as compliance]
            [chengis.web.views.policies :as v-policies]
            [chengis.web.views.plugin-policies :as v-plugin-policies]
            [chengis.web.views.docker-policies :as v-docker-policies]
            [chengis.web.views.traces :as v-traces]
            [chengis.web.views.analytics :as v-analytics]
            [chengis.web.views.cost :as v-cost]
            [chengis.web.views.flaky-tests :as v-flaky]
            [chengis.engine.tracing :as tracing]
            [chengis.engine.analytics :as analytics]
            [chengis.engine.cost :as cost]
            [chengis.db.test-result-store :as test-result-store]
            [chengis.db.cron-store :as cron-store]
            [chengis.db.dependency-store :as dep-store]
            [chengis.db.pr-check-store :as pr-check-store]
            [chengis.engine.cron :as cron]
            [chengis.engine.build-deps :as build-deps]
            [chengis.engine.webhook-replay :as webhook-replay]
            [chengis.web.views.cron :as v-cron]
            [chengis.web.views.dependencies :as v-deps]
            [chengis.web.views.webhook-replay :as v-replay]
            [chengis.web.webhook :as webhook]
            [chengis.web.sse :as sse]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]
            [ring.middleware.anti-forgery :as anti-forgery]
            [ring.util.io]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent RejectedExecutionException]))

(defn- csrf-token
  "Get the current CSRF token from the Ring request."
  [_req]
  (force anti-forgery/*anti-forgery-token*))

(defn- html-response [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- not-found [msg]
  {:status 404
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<h1>404</h1><p>" (escape-html msg) "</p>")})

(defn dashboard-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          jobs (job-store/list-jobs ds :org-id org-id)
          builds (build-store/list-builds ds {:org-id org-id})
          running (count (filter #(= :running (:status %)) builds))
          queued (count (filter #(= :queued (:status %)) builds))
          stats (build-store/get-build-stats ds {:org-id org-id})
          recent-history (build-store/get-recent-build-history ds {:org-id org-id} 30)
          current-alerts (try (alerts/check-alerts system :org-id org-id)
                              (catch Exception e
                                (log/warn "Failed to check alerts for dashboard:" (.getMessage e))
                                []))]
      (html-response
        (v-dashboard/render {:jobs jobs
                             :builds builds
                             :running-count running
                             :queued-count queued
                             :stats stats
                             :recent-history recent-history
                             :alerts current-alerts
                             :csrf-token (csrf-token req)})))))

(defn jobs-list-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          jobs (job-store/list-jobs ds :org-id org-id)]
      (html-response (v-jobs/render-list {:jobs jobs
                                          :csrf-token (csrf-token req)})))))

(defn job-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name :org-id org-id)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [builds (build-store/list-builds ds {:job-id (:id job) :org-id org-id})
              stats (build-store/get-build-stats ds {:job-id (:id job) :org-id org-id})
              recent-history (build-store/get-recent-build-history ds {:job-id (:id job) :org-id org-id} 30)
              secret-names (concat
                             (secret-store/list-secret-names ds :scope "global" :org-id org-id)
                             (secret-store/list-secret-names ds :scope (:id job) :org-id org-id))]
          (html-response
            (v-jobs/render-detail {:job job :builds builds
                                   :stats stats
                                   :recent-history recent-history
                                   :secret-names (distinct secret-names)
                                   :csrf-token (csrf-token req)})))))))

(defn- extract-params-from-form
  "Extract build parameters from form submission.
   Form fields named 'param_<name>' are extracted as {:name value}."
  [form-params]
  (when (seq form-params)
    (reduce-kv (fn [acc k v]
                 (if (str/starts-with? k "param_")
                   (let [param-name (keyword (subs k 6))]
                     (assoc acc param-name v))
                   acc))
               {}
               form-params)))

(defn trigger-form [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name :org-id org-id)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [parameters (or (:parameters job)
                              (get-in job [:pipeline :parameters]))]
          (html-response
            (str (h/html
                   (v-trigger-form/render-trigger-form
                     job-name parameters (csrf-token req))))))))))

(defn- dispatch-or-execute!
  "Try distributed dispatch first (if feature flag enabled), otherwise execute locally.
   When dispatch returns :failed (no agents and fallback-local=false), marks the build
   as failed rather than silently running locally — respecting the operator's intent.
   Returns the HTTP response (303 redirect to build page, or 503 if queue full)."
  [system ds build-id job build-record build-params]
  (let [dispatch-result (when (feature-flags/enabled? (:config system) :distributed-dispatch)
                          (try
                            (dispatcher/dispatch-build! system
                              {:build-id build-id
                               :job-id (:id job)
                               :pipeline (:pipeline job)
                               :org-id (:org-id build-record)
                               :parameters build-params}
                              (:labels job))
                            (catch Exception e
                              (log/warn "Dispatcher error, falling back to local:" (.getMessage e))
                              nil)))]
    (cond
      ;; Dispatched remotely or queued — don't execute locally
      (and dispatch-result
           (#{:remote :queued} (:mode dispatch-result)))
      (do (log/info "Build" build-id "dispatched:" (:mode dispatch-result))
          {:status 303
           :headers {"Location" (str "/builds/" build-id)}})

      ;; Dispatch explicitly failed (no agents, fallback-local=false) — mark build failed
      (and dispatch-result
           (= :failed (:mode dispatch-result)))
      (do (log/warn "Build" build-id "dispatch failed:" (:error dispatch-result))
          (build-store/update-build-status! ds build-id :failure
            :completed-at (str (java.time.Instant/now)))
          (events/publish! {:build-id build-id
                            :event-type :build-completed
                            :timestamp (str (java.time.Instant/now))
                            :data {:build-status :failure
                                   :error (str "Dispatch failed: " (:error dispatch-result))}})
          {:status 303
           :headers {"Location" (str "/builds/" build-id)}})

      ;; Local execution (flag disabled, dispatcher returned :local, or nil)
      :else
      (try
        (.submit build-runner/build-executor
          ^Runnable (fn []
            (try
              (build-runner/execute-build-for-record!
                system job build-record
                {:event-fn events/publish!
                 :parameters build-params})
              (catch Exception e
                (log/error e "Build failed:" build-id)
                (build-store/update-build-status! ds build-id :failure
                  :completed-at (str (java.time.Instant/now)))
                (events/publish! {:build-id build-id
                                  :event-type :build-completed
                                  :timestamp (str (java.time.Instant/now))
                                  :data {:build-status :failure}})))))
        {:status 303
         :headers {"Location" (str "/builds/" build-id)}}
        (catch RejectedExecutionException _
          {:status 503
           :headers {"Content-Type" "text/html; charset=utf-8"
                     "Retry-After" "30"}
           :body "<h1>503</h1><p>Build queue full. Try again shortly.</p>"})))))

(defn trigger-build [system]
  (fn [req]
    (let [ds (:db system)
          job-name (get-in req [:path-params :name])
          org-id (auth/current-org-id req)
          job (job-store/get-job ds job-name :org-id org-id)
          form-params (:form-params req)
          build-params (extract-params-from-form form-params)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (let [build-record (build-store/create-build! ds
                             {:job-id (:id job)
                              :trigger-type :manual
                              :parameters build-params
                              :org-id org-id})
              build-id (:id build-record)]
          (log/info "Web trigger: build #" (:build-number build-record) "for" job-name
                    "(id:" build-id ")" (when (seq build-params) (str "params:" build-params)))
          (dispatch-or-execute! system ds build-id job build-record build-params))))))

(defn build-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [stages (build-store/get-build-stages ds build-id)
              steps (build-store/get-build-steps ds build-id)
              job (job-store/get-job-by-id ds (:job-id build) :org-id org-id)
              artifacts (artifact-store/list-artifacts ds build-id)
              notifications (notification-store/list-notifications ds build-id)
              ;; Fetch retry attempt history when this build is part of a retry chain
              root-id (or (:root-build-id build)
                          (when (:parent-build-id build) build-id))
              attempts (when root-id
                         (try (build-store/list-attempts ds root-id)
                              (catch Exception _ nil)))]
          (html-response
            (v-builds/render-detail {:build build
                                     :stages stages
                                     :steps steps
                                     :job job
                                     :artifacts artifacts
                                     :notifications notifications
                                     :attempts attempts
                                     :csrf-token (csrf-token req)})))))))

(defn build-log-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [steps (build-store/get-build-steps ds build-id)]
          (html-response
            (v-builds/render-log {:build build :steps steps
                                  :csrf-token (csrf-token req)})))))))

(defn cancel-build [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        {:status 404
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Build not found or already completed.</p>"}
        (let [cancelled? (build-runner/cancel-build! build-id)]
          (if cancelled?
            (do
              (log/info "Build cancelled via web:" build-id)
              {:status 303
               :headers {"Location" (str "/builds/" build-id)}})
            {:status 404
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body "<p>Build not found or already completed.</p>"}))))))

(defn retry-build [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [job (job-store/get-job-by-id ds (:job-id build) :org-id org-id)
              new-record (build-store/create-build! ds
                           {:job-id (:job-id build)
                            :trigger-type :retry
                            :parameters (:parameters build)
                            :parent-build-id build-id
                            :org-id org-id})
              new-id (:id new-record)]
          (log/info "Retry build #" (:build-number new-record) "from" build-id)
          (dispatch-or-execute! system ds new-id job new-record (:parameters build)))))))

(defn build-events-sse [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        {:status 404
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Build not found.</p>"}
        ((sse/sse-handler system build-id) req)))))

;; --- Build Events API (replay) ---

(defn build-events-handler
  "GET /api/builds/:id/events/replay — JSON array of persisted build events.
   Supports ?after=<event-id> for cursor pagination and ?type=<event-type> filtering."
  [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Build not found\"}"}
        (let [after-id (get-in req [:params :after])
              event-type (get-in req [:params :type])
              limit (try (some-> (get-in req [:params :limit]) Integer/parseInt)
                         (catch Exception _ nil))
              events (build-event-store/list-events ds build-id
                       :after-id after-id
                       :event-type event-type
                       :limit limit)]
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str
                   {:build-id build-id
                    :count (count events)
                    :events (mapv (fn [e]
                                    {:id (:id e)
                                     :event-type (name (:event-type e))
                                     :stage-name (:stage-name e)
                                     :step-name (:step-name e)
                                     :data (:data e)
                                     :created-at (:created-at e)})
                                  events)})})))))

;; --- Secrets ---

(defn create-secret [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name :org-id org-id)
          params (:form-params req)
          secret-name (get params "secret-name")
          secret-value (get params "secret-value")
          scope (get params "scope" "global")]
      (cond
        (or (str/blank? secret-name) (str/blank? secret-value))
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Secret name and value are required.</p>"}

        ;; Validate that job exists when creating a job-scoped secret
        (and (= scope "job") (nil? job))
        (not-found (str "Job not found: " job-name))

        :else
        (let [effective-scope (if (= scope "job")
                                (:id job)
                                "global")]
          (secret-store/set-secret! ds config secret-name secret-value
                                    :scope effective-scope
                                    :org-id org-id)
          (log/info "Secret created:" secret-name "scope:" effective-scope)
          {:status 303
           :headers {"Location" (str "/jobs/" job-name)}})))))

(defn delete-secret [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-name (get-in req [:path-params :name])
          secret-key (get-in req [:path-params :key])
          scope (get-in req [:query-params "scope"] "global")
          job (when (not= scope "global")
                (job-store/get-job ds job-name :org-id org-id))
          effective-scope (if (and job (not= scope "global"))
                            (:id job)
                            scope)]
      (secret-store/delete-secret! ds secret-key :scope effective-scope
                                    :org-id org-id)
      (log/info "Secret deleted:" secret-key "scope:" effective-scope)
      {:status 303
       :headers {"Location" (str "/jobs/" job-name)}})))

;; --- Artifacts ---

(defn download-artifact [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          ;; Verify build belongs to current org before serving artifact
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [filename (get-in req [:path-params :filename])
              artifact (artifact-store/get-artifact ds build-id filename)]
          (if-not artifact
            (not-found (str "Artifact not found: " filename))
            (let [file (io/file (:path artifact))]
              (if (.exists file)
                {:status 200
                 :headers (cond-> {"Content-Type" (or (:content-type artifact) "application/octet-stream")
                                   "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
                            (:sha256-hash artifact)
                            (assoc "X-Artifact-SHA256" (:sha256-hash artifact)))
                 :body file}
                (not-found (str "Artifact file missing from disk: " filename))))))))))

(defn verify-artifact-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :id])
          build (build-store/get-build ds build-id :org-id org-id)]
      (if-not build
        (not-found (str "Build not found: " build-id))
        (let [filename (get-in req [:path-params :filename])
              result (artifact-store/verify-artifact-hash ds build-id filename)]
          (when (:metrics system)
            (metrics/record-artifact-checksum! (:metrics system)
              (cond
                (nil? (:valid result)) :skipped
                (:valid result) :match
                :else :mismatch)))
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/write-str
                   {:filename filename
                    :sha256 (:expected result)
                    :valid (:valid result)
                    :computed (:computed result)
                    :reason (:reason result)})})))))

;; --- Admin ---

(defn admin-page [system]
  (fn [req]
    (let [config (:config system)
          system-info (cleanup/get-system-info)
          disk-usage (cleanup/calculate-disk-usage config)
          db-size (cleanup/get-db-size config)]
      (html-response
        (v-admin/render {:system-info system-info
                         :disk-usage disk-usage
                         :db-size db-size
                         :csrf-token (csrf-token req)
                         :user (auth/current-user req)
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn admin-cleanup [system]
  (fn [req]
    (let [config (:config system)
          result (cleanup/cleanup-workspaces! config)]
      (log/info "Admin cleanup:" result)
      (let [system-info (cleanup/get-system-info)
            disk-usage (cleanup/calculate-disk-usage config)
            db-size (cleanup/get-db-size config)]
        (html-response
          (v-admin/render {:system-info system-info
                           :disk-usage disk-usage
                           :db-size db-size
                           :cleanup-result result
                           :csrf-token (csrf-token req)
                           :user (auth/current-user req)
                           :auth-enabled (get-in config [:auth :enabled] false)}))))))

(defn admin-retention [system]
  (fn [req]
    (let [result (retention/run-retention! system)]
      (log/info "Manual retention run:" result)
      {:status 303
       :headers {"Location" "/admin"}})))

(defn admin-backup [system]
  (fn [req]
    (let [ds (:db system)
          user (auth/current-user req)
          backup-dir (or (get-in system [:config :backup :directory]) "/tmp")
          output-path (backup/generate-backup-path backup-dir)
          _ (log/warn "Database backup requested by" (:username user)
                      "— backup includes ALL organizations' data")
          result (backup/backup! ds output-path)
          backup-file (io/file (:path result))]
      (log/info "Admin backup created:" (:path result))
      {:status 200
       :headers {"Content-Type" "application/octet-stream"
                 "Content-Disposition" (str "attachment; filename=\"" (.getName backup-file) "\"")
                 "Content-Length" (str (:size-bytes result))}
       :body backup-file})))

;; ---------------------------------------------------------------------------
;; Agents page
;; ---------------------------------------------------------------------------

(defn agents-page [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          agents (agent-reg/list-agents :org-id org-id)
          summary (agent-reg/registry-summary :org-id org-id)
          cb-states (cb/get-all-states)
          queue-enabled? (get-in system [:config :distributed :dispatch :queue-enabled] false)
          queue-depth (when (and queue-enabled? (:db system))
                        (try (bq/get-queue-depth (:db system))
                             (catch Exception _ nil)))]
      (html-response
        (v-agents/render {:agents agents
                          :summary summary
                          :circuit-breakers cb-states
                          :queue-depth queue-depth
                          :csrf-token (csrf-token req)})))))

;; ---------------------------------------------------------------------------
;; Auth handlers
;; ---------------------------------------------------------------------------

(defn login-page [system]
  (fn [req]
    (let [config (:config system)
          oidc-enabled? (get-in config [:oidc :enabled] false)
          oidc-name (when oidc-enabled?
                      ((resolve 'chengis.web.oidc/oidc-provider-name) config))
          ;; Support ?error= query param from OIDC callback redirects
          error (or (get (:query-params req) "error")
                    (get (:params req) "error"))]
      (html-response
        (v-login/render {:csrf-token (csrf-token req)
                          :error error
                          :oidc-enabled oidc-enabled?
                          :oidc-provider-name oidc-name})))))

(defn login-submit [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          lockout-config (get-in config [:auth :lockout])
          params (:form-params req)
          username (get params "username")
          password (get params "password")
          ip-address (or (:remote-addr req) "unknown")
          result (auth/login! ds username password (:metrics system) lockout-config)]
      ;; Log the login attempt for forensics
      (lockout/log-login-attempt! ds username ip-address (:success result))
      (if (:success result)
        ;; Set session and redirect to dashboard
        {:status 303
         :headers {"Location" "/"}
         :session {:user (:user result)}}
        ;; Re-render login with error
        (let [oidc-enabled? (get-in config [:oidc :enabled] false)]
          (html-response
            (v-login/render {:error (:error result)
                              :csrf-token (csrf-token req)
                              :oidc-enabled oidc-enabled?
                              :oidc-provider-name (when oidc-enabled?
                                                    ((resolve 'chengis.web.oidc/oidc-provider-name) config))})))))))

(defn logout-submit [_system]
  (fn [_req]
    {:status 303
     :headers {"Location" "/login"}
     :session nil}))

;; ---------------------------------------------------------------------------
;; Health & Readiness endpoints
;; ---------------------------------------------------------------------------

(def ^:private start-time (System/currentTimeMillis))

(defonce ^:private startup-complete? (atom false))

(defn mark-startup-complete!
  "Mark the server as fully initialized. Called at the end of server startup."
  []
  (reset! startup-complete? true))

(defn reset-startup-state!
  "Reset startup state. For testing."
  []
  (reset! startup-complete? false))

(defn health-check [system]
  (fn [_req]
    (let [uptime-ms (- (System/currentTimeMillis) start-time)
          uptime-s (quot uptime-ms 1000)
          instance-id (or (get-in (:config system) [:ha :instance-id])
                          "standalone")]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:status "ok"
                              :version "1.0.0"
                              :uptime-seconds uptime-s
                              :instance-id instance-id})})))

(defn readiness-check [system]
  (fn [_req]
    (try
      (let [ds (:db system)
            cfg (:config system)
            ;; Try a simple query to verify DB is accessible
            _ (chengis.db.user-store/count-users ds)
            ;; Queue depth when distributed + queue enabled
            queue-depth (when (and (get-in cfg [:distributed :enabled])
                                   (get-in cfg [:distributed :dispatch :queue-enabled]))
                          (try
                            (let [depth-fn (requiring-resolve
                                             'chengis.distributed.build-queue/get-queue-depth)]
                              (depth-fn ds))
                            (catch Exception _ nil)))
            ;; Agent registry summary
            agent-summary (try
                            (let [summary-fn (requiring-resolve
                                               'chengis.distributed.agent-registry/registry-summary)]
                              (summary-fn))
                            (catch Exception _ nil))
            body (cond-> {:status "ready"
                          :database "connected"}
                   queue-depth (assoc :queue-depth queue-depth)
                   agent-summary (assoc :agents agent-summary))]
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str body)})
      (catch Exception e
        (log/warn "Readiness check failed:" (.getMessage e))
        {:status 503
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:status "not-ready"
                                :database "unavailable"})}))))

(defn startup-check
  "Kubernetes startup probe handler.
   Returns 503 until server initialization is complete, then 200.
   Used by K8s to determine when the pod is ready to receive liveness probes."
  [_system]
  (fn [_req]
    (if @startup-complete?
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:status "started"})}
      {:status 503
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:status "starting"})})))

;; ---------------------------------------------------------------------------
;; API Auth token generation
;; ---------------------------------------------------------------------------

(defn api-generate-token [system]
  (fn [req]
    (let [ds (:db system)
          user (auth/current-user req)
          params (or (:body-params req) (:form-params req) {})
          token-name (or (get params "name") (get params :name) "API Token")]
      (if-not user
        {:status 401
         :headers {"Content-Type" "application/json"}
         :body "{\"error\":\"Authentication required\"}"}
        (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                       :name token-name})]
          {:status 201
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:token (:token result)
                                  :name (:name result)
                                  :id (:id result)
                                  :message "Save this token — it won't be shown again"})})))))

;; ---------------------------------------------------------------------------
;; Audit log page (admin only)
;; ---------------------------------------------------------------------------

(defn audit-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          page (max 1 (try (Integer/parseInt (or (get params "page") "1"))
                          (catch NumberFormatException _ 1)))
          page-size 50
          ;; Build filter map: resolve username to user-id for proper filtering
          username-filter (get params "username")
          user-for-filter (when (seq username-filter)
                            (user-store/get-user-by-username ds username-filter))
          filters (cond-> {}
                    org-id                        (assoc :org-id org-id)
                    (seq (get params "action"))   (assoc :action (get params "action"))
                    user-for-filter               (assoc :user-id (:id user-for-filter))
                    ;; If username given but user not found, use impossible filter to return no results
                    (and (seq username-filter) (nil? user-for-filter)) (assoc :user-id "__nonexistent__"))
          query-opts (merge filters {:limit page-size :offset (* (dec page) page-size)})
          audits (audit-store/query-audits ds query-opts)
          total (audit-store/count-audits ds filters)]
      (html-response
        (v-audit/render {:audits audits
                         :total-count total
                         :page page
                         :page-size page-size
                         :filters params
                         :csrf-token (csrf-token req)
                         :user (auth/current-user req)
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn audit-export-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          format (get params "format" "csv")
          ;; Build filters from query params
          username-filter (get params "username")
          user-for-filter (when (seq username-filter)
                            (user-store/get-user-by-username ds username-filter))
          filters (cond-> {}
                    org-id                        (assoc :org-id org-id)
                    (seq (get params "action"))   (assoc :action (get params "action"))
                    user-for-filter               (assoc :user-id (:id user-for-filter))
                    (and (seq username-filter) (nil? user-for-filter)) (assoc :user-id "__nonexistent__")
                    (seq (get params "from"))      (assoc :from-date (get params "from"))
                    (seq (get params "to"))        (assoc :to-date (get params "to")))
          today (str (java.time.LocalDate/now))
          [content-type ext export-fn]
          (if (= format "json")
            ["application/json" "json" audit-export/export-json]
            ["text/csv" "csv" audit-export/export-csv])]
      {:status 200
       :headers {"Content-Type" content-type
                 "Content-Disposition" (str "attachment; filename=\"audit-export-" today "." ext "\"")}
       :body (ring.util.io/piped-input-stream
               (fn [out]
                 (let [writer (java.io.OutputStreamWriter. out "UTF-8")]
                   (export-fn ds filters writer)
                   (.close writer))))})))

;; ---------------------------------------------------------------------------
;; Webhook events (admin only)
;; ---------------------------------------------------------------------------

(defn webhooks-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          page (max 1 (try (Integer/parseInt (or (get params "page") "1"))
                          (catch NumberFormatException _ 1)))
          page-size 50
          events (webhook-log/list-webhook-events ds
                   :org-id org-id
                   :limit page-size
                   :offset (* (dec page) page-size))
          total (webhook-log/count-webhook-events ds :org-id org-id)]
      (html-response
        (v-webhooks/render {:events events
                            :total total
                            :page page
                            :page-size page-size
                            :csrf-token (csrf-token req)
                            :user (auth/current-user req)
                            :auth-enabled (get-in config [:auth :enabled] false)})))))

;; ---------------------------------------------------------------------------
;; User management (admin only)
;; ---------------------------------------------------------------------------

(defn- render-users-page [system req & {:keys [message error]}]
  (let [ds (:db system)
        config (:config system)
        users (user-store/list-users ds)]
    (html-response
      (v-users/render {:users users
                       :message message
                       :error error
                       :csrf-token (csrf-token req)
                       :user (auth/current-user req)
                       :auth-enabled (get-in config [:auth :enabled] false)}))))

(defn users-page [system]
  (fn [req]
    (render-users-page system req)))

(defn create-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          username (get params "username")
          password (get params "password")
          role (get params "role" "viewer")]
      (cond
        ;; Validate required fields
        (or (str/blank? username) (str/blank? password))
        (render-users-page system req :error "Username and password are required")

        ;; Validate username format
        (not (auth/valid-username? username))
        (render-users-page system req :error "Username must be 2-64 chars: letters, numbers, hyphens, underscores")

        ;; Validate password strength
        (not (auth/valid-password? password))
        (render-users-page system req :error (str "Password must be at least " auth/min-password-length " characters"))

        ;; Validate role is allowed
        (not (auth/valid-role? role))
        (render-users-page system req :error (str "Invalid role: " (escape-html role) ". Must be admin, developer, or viewer"))

        ;; All valid — create user and add to current org
        :else
        (try
          (let [new-user (user-store/create-user! ds {:username username :password password :role role})]
            ;; Add new user to current org
            (when org-id
              (try
                (org-store/add-member! ds {:org-id org-id :user-id (:id new-user) :role role})
                (catch Exception e
                  (log/warn "Failed to add new user to org" org-id ":" (.getMessage e)))))
            (log/info "User created:" username "role:" role "by:" (:username (auth/current-user req))
                      "in org:" org-id)
            (render-users-page system req :message (str "User '" (escape-html username) "' created")))
          (catch Exception e
            (render-users-page system req :error (str "Failed to create user: " (.getMessage e)))))))))

(defn update-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user-id (get-in req [:path-params :id])
          params (:form-params req)
          new-role (get params "role")
          ;; Verify target user is in current org
          membership (when org-id (org-store/get-membership ds org-id user-id))]
      (cond
        ;; Org-scoped access check
        (and org-id (nil? membership))
        (render-users-page system req :error "User not found in this organization")

        (nil? new-role)
        (render-users-page system req :error "No role specified")

        (not (auth/valid-role? new-role))
        (render-users-page system req :error (str "Invalid role: " (escape-html new-role)))

        :else
        (do
          ;; Update org membership role (not global user role)
          (when org-id
            (org-store/update-member-role! ds org-id user-id new-role))
          ;; Also update global role for backward compat
          (user-store/update-user! ds user-id {:role new-role})
          (log/info "User" user-id "role changed to" new-role "by:" (:username (auth/current-user req))
                    "in org:" org-id)
          (render-users-page system req :message "User updated"))))))

(defn toggle-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user-id (get-in req [:path-params :id])
          ;; Verify target user is in current org
          membership (when org-id (org-store/get-membership ds org-id user-id))]
      (if (and org-id (nil? membership))
        (render-users-page system req :error "User not found in this organization")
        (let [target-user (user-store/get-user ds user-id)
              currently-active? (pos? (or (:active target-user) 1))]
          (if currently-active?
            (do (user-store/delete-user! ds user-id)
                (log/info "User" user-id "deactivated by:" (:username (auth/current-user req)))
                (render-users-page system req :message (str "User '" (:username target-user) "' deactivated")))
            (do (user-store/update-user! ds user-id {:active true})
                (log/info "User" user-id "reactivated by:" (:username (auth/current-user req)))
                (render-users-page system req :message (str "User '" (:username target-user) "' reactivated")))))))))

(defn reset-password-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user-id (get-in req [:path-params :id])
          ;; Verify target user is in current org
          membership (when org-id (org-store/get-membership ds org-id user-id))
          params (:form-params req)
          new-password (get params "new-password")]
      (cond
        ;; Org-scoped access check
        (and org-id (nil? membership))
        (render-users-page system req :error "User not found in this organization")

        (str/blank? new-password)
        (render-users-page system req :error "Password cannot be empty")

        (not (auth/valid-password? new-password))
        (render-users-page system req :error (str "Password must be at least " auth/min-password-length " characters"))

        :else
        (do
          (user-store/update-password! ds user-id new-password)
          (log/info "Password reset for user" user-id "by:" (:username (auth/current-user req)))
          (render-users-page system req :message "Password updated"))))))

(defn unlock-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user-id (get-in req [:path-params :id])
          ;; Verify target user is in current org
          membership (when org-id (org-store/get-membership ds org-id user-id))
          target-user (user-store/get-user ds user-id)]
      (cond
        ;; Org-scoped access check
        (and org-id (nil? membership))
        (render-users-page system req :error "User not found in this organization")

        (nil? target-user)
        (render-users-page system req :error "User not found")

        :else
        (do
          (lockout/unlock-account! ds user-id)
          (log/info "Account unlocked:" (:username target-user) "by:" (:username (auth/current-user req)))
          (render-users-page system req :message (str "Account '" (:username target-user) "' unlocked")))))))

;; ---------------------------------------------------------------------------
;; API Token management (all authenticated users)
;; ---------------------------------------------------------------------------

(defn tokens-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          user (auth/current-user req)
          tokens (user-store/list-api-tokens ds (:id user))
          ;; Get new token from session flash (one-time display, never in URL)
          new-token (get-in req [:session :flash-new-token])
          ;; Clear flash from session after reading
          response (html-response
                     (v-tokens/render {:tokens tokens
                                       :new-token new-token
                                       :csrf-token (csrf-token req)
                                       :user user
                                       :auth-enabled (get-in config [:auth :enabled] false)}))]
      ;; Remove flash token from session so it's only shown once
      (if new-token
        (assoc response :session (dissoc (:session req) :flash-new-token))
        response))))

(defn generate-token-handler [system]
  (fn [req]
    (let [ds (:db system)
          registry (:metrics system)
          user (auth/current-user req)
          params (:form-params req)
          token-name (get params "name")
          expires-in (get params "expires-in")]
      (if (str/blank? token-name)
        (let [tokens (user-store/list-api-tokens ds (:id user))]
          (html-response
            (v-tokens/render {:tokens tokens
                              :error "Token name cannot be empty"
                              :csrf-token (csrf-token req)
                              :user user
                              :auth-enabled (get-in (:config system) [:auth :enabled] false)})))
        (let [expires-at (when (and expires-in (not (str/blank? expires-in)))
                           (try
                             (str (.plus (java.time.Instant/now)
                                         (java.time.Duration/ofDays (Integer/parseInt expires-in))))
                             (catch NumberFormatException _ nil)))
              ;; Parse scopes from form (multi-select checkboxes)
              scopes-raw (let [v (get params "scopes")]
                           (cond
                             (nil? v) nil
                             (string? v) (when-not (str/blank? v) [v])
                             (sequential? v) (seq (remove str/blank? v))
                             :else nil))
              ;; CR-08: Validate scopes against known valid scopes (reject unknown)
              ;; P1 fix: if user submitted scopes but ALL are invalid, reject rather
              ;; than silently creating a full-access token (empty vec → nil → NULL scopes)
              scopes-validated (when scopes-raw
                                 (filterv #(contains? auth/valid-scopes %) scopes-raw))]
          ;; P1: If scopes were submitted but all were invalid, show error
          (if (and scopes-raw (empty? scopes-validated))
            (do
              (log/warn "Token creation rejected: all submitted scopes invalid"
                        {:scopes-raw scopes-raw :user (:username user)})
              (let [tokens (user-store/list-api-tokens ds (:id user))]
                (html-response
                  (v-tokens/render
                    {:tokens tokens
                     :error "None of the selected scopes are valid"
                     :csrf-token (csrf-token req)
                     :user user
                     :auth-enabled (get-in (:config system) [:auth :enabled] false)}))))
            (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                           :name token-name
                                                           :expires-at expires-at
                                                           :scopes scopes-validated})]
              (try (metrics/record-token-generated! registry) (catch Exception _))
              (log/info "API token generated:" token-name "for user:" (:username user))
              ;; Store new token in session flash (never in URL — prevents token leaking via referer/logs)
              {:status 303
               :headers {"Location" "/settings/tokens"}
               :session (assoc (:session req) :flash-new-token (:token result))})))))))

(defn revoke-token-handler [system]
  (fn [req]
    (let [ds (:db system)
          registry (:metrics system)
          user (auth/current-user req)
          token-id (get-in req [:path-params :id])
          ;; Verify token belongs to the current user (ownership check)
          user-tokens (user-store/list-api-tokens ds (:id user))
          owns-token? (some #(= (:id %) token-id) user-tokens)]
      (if-not owns-token?
        {:status 403
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<h1>403 Forbidden</h1><p>You can only revoke your own tokens.</p>"}
        (do
          (user-store/revoke-api-token! ds token-id)
          (try (metrics/record-token-revoked! registry) (catch Exception _))
          (log/info "API token revoked:" token-id "by:" (:username user))
          {:status 303
           :headers {"Location" "/settings/tokens"}})))))

;; ---------------------------------------------------------------------------
;; Approval Gates
;; ---------------------------------------------------------------------------

(defn approvals-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          gates (try (approval-store/list-pending-gates ds :org-id org-id) (catch Exception _ []))
          pending-count (try (approval-store/count-pending-gates ds :org-id org-id) (catch Exception _ 0))
          ;; Enrich gates with response data for multi-approver display
          gates-with-responses (mapv (fn [gate]
                                       (if (and (:min-approvals gate) (> (:min-approvals gate) 1))
                                         (assoc gate :responses
                                                (try (approval-store/get-gate-responses ds (:id gate))
                                                     (catch Exception _ [])))
                                         gate))
                                     gates)]
      (html-response
        (v-approvals/render {:gates gates-with-responses
                             :pending-count pending-count
                             :csrf-token (csrf-token req)
                             :user (auth/current-user req)
                             :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn approve-gate-handler [system]
  (fn [req]
    (let [ds (:db system)
          gate-id (get-in req [:path-params :id])
          user (auth/current-user req)
          org-id (auth/current-org-id req)
          gate (approval-store/get-gate ds gate-id :org-id org-id)]
      (cond
        (nil? gate)
        (not-found "Approval gate not found")

        (not= "pending" (:status gate))
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Gate is no longer pending.</p>"}

        ;; Check if user has the required role
        (not (auth/role-sufficient? (:role user) (keyword (:required-role gate))))
        {:status 403
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str "<p>Insufficient role. Required: " (:required-role gate) "</p>")}

        :else
        (do
          (approval-store/approve-gate! ds gate-id (:username user))
          (log/info "Approval gate" gate-id "approved by" (:username user))
          {:status 303
           :headers {"Location" "/approvals"}})))))

(defn reject-gate-handler [system]
  (fn [req]
    (let [ds (:db system)
          gate-id (get-in req [:path-params :id])
          user (auth/current-user req)
          org-id (auth/current-org-id req)
          gate (approval-store/get-gate ds gate-id :org-id org-id)]
      (cond
        (nil? gate)
        (not-found "Approval gate not found")

        (not= "pending" (:status gate))
        {:status 400
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body "<p>Gate is no longer pending.</p>"}

        ;; Check if user has the required role (same check as approve)
        (not (auth/role-sufficient? (:role user) (keyword (:required-role gate))))
        {:status 403
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (str "<p>Insufficient role. Required: " (:required-role gate) "</p>")}

        :else
        (do
          (approval-store/reject-gate! ds gate-id (:username user))
          (log/info "Approval gate" gate-id "rejected by" (:username user))
          {:status 303
           :headers {"Location" "/approvals"}})))))

(defn api-pending-approvals [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          gates (try (approval-store/list-pending-gates ds :org-id org-id) (catch Exception _ []))]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str gates)})))

;; ---------------------------------------------------------------------------
;; Pipeline Templates (admin only)
;; ---------------------------------------------------------------------------

(defn templates-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          templates (try (template-store/list-templates ds :org-id org-id) (catch Exception _ []))]
      (html-response
        (v-templates/render {:templates templates
                             :csrf-token (csrf-token req)
                             :user (auth/current-user req)
                             :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn template-new-page [system]
  (fn [req]
    (let [config (:config system)]
      (html-response
        (v-templates/render-form {:csrf-token (csrf-token req)
                                  :user (auth/current-user req)
                                  :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-template-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          tname (get params "name")
          description (get params "description")
          format (get params "format" "edn")
          content (get params "content")]
      (cond
        (str/blank? tname)
        (html-response
          (v-templates/render-form {:error "Template name is required"
                                    :template {:description description :format format :content content}
                                    :csrf-token (csrf-token req)
                                    :user (auth/current-user req)
                                    :auth-enabled (get-in (:config system) [:auth :enabled] false)}))

        (str/blank? content)
        (html-response
          (v-templates/render-form {:error "Template content is required"
                                    :template {:name tname :description description :format format}
                                    :csrf-token (csrf-token req)
                                    :user (auth/current-user req)
                                    :auth-enabled (get-in (:config system) [:auth :enabled] false)}))

        :else
        (let [result (template-store/create-template! ds
                       {:name tname
                        :description description
                        :format format
                        :content content
                        :created-by (:username (auth/current-user req))
                        :org-id org-id})]
          (if result
            (do
              (log/info "Template created:" tname "by:" (:username (auth/current-user req)))
              {:status 303 :headers {"Location" "/admin/templates"}})
            (html-response
              (v-templates/render-form {:error "Template name already exists"
                                        :template {:name tname :description description :format format :content content}
                                        :csrf-token (csrf-token req)
                                        :user (auth/current-user req)
                                        :auth-enabled (get-in (:config system) [:auth :enabled] false)}))))))))

(defn template-edit-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          tname (get-in req [:path-params :name])
          template (template-store/get-template-by-name ds tname :org-id org-id)]
      (if (nil? template)
        (not-found "Template not found")
        (html-response
          (v-templates/render-form {:template template
                                    :editing? true
                                    :csrf-token (csrf-token req)
                                    :user (auth/current-user req)
                                    :auth-enabled (get-in config [:auth :enabled] false)}))))))

(defn update-template-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          tname (get-in req [:path-params :name])
          template (template-store/get-template-by-name ds tname :org-id org-id)
          params (:form-params req)]
      (if (nil? template)
        (not-found "Template not found")
        (do
          (template-store/update-template! ds (:id template)
            {:description (get params "description")
             :format (get params "format" "edn")
             :content (get params "content")}
            :org-id org-id)
          (log/info "Template updated:" tname "by:" (:username (auth/current-user req)))
          {:status 303 :headers {"Location" "/admin/templates"}})))))

(defn delete-template-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          template-id (get-in req [:path-params :id])]
      (template-store/delete-template! ds template-id :org-id org-id)
      (log/info "Template deleted:" template-id "by:" (:username (auth/current-user req)))
      {:status 303 :headers {"Location" "/admin/templates"}})))

;; ---------------------------------------------------------------------------
;; Organization switching
;; ---------------------------------------------------------------------------

(defn switch-org-handler
  "Handle POST /orgs/:slug/switch — switch the user's current org context.
   Stores the selected org ID in the session."
  [system]
  (fn [req]
    (let [ds (:db system)
          slug (get-in req [:path-params :slug])
          user (auth/current-user req)
          org (org-store/get-org-by-slug ds slug)]
      (if (and org user)
        (let [membership (org-store/get-membership ds (:id org) (:id user))]
          (if membership
            ;; Valid org + membership — store in session and redirect
            (let [session (assoc (:session req) :current-org-id (:id org))]
              {:status 303
               :headers {"Location" "/"}
               :session session})
            ;; User is not a member of this org
            {:status 403
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body "<h1>403 Forbidden</h1><p>You are not a member of this organization.</p>"}))
        ;; Org not found
        (not-found "Organization not found")))))

;; ---------------------------------------------------------------------------
;; Compliance Reports (admin only)
;; ---------------------------------------------------------------------------

(defn compliance-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          templates (try (compliance-store/list-report-templates ds :org-id org-id)
                         (catch Exception _ []))
          runs (try (compliance-store/list-report-runs ds :org-id org-id :limit 20)
                    (catch Exception _ []))]
      (html-response
        (v-compliance/render {:templates templates
                              :runs runs
                              :csrf-token (csrf-token req)
                              :user (auth/current-user req)
                              :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn generate-compliance-report [system]
  (fn [req]
    (let [ds (:db system)
          registry (:metrics system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          report-id (get params "report-id")
          period-start (get params "period-start")
          period-end (get params "period-end")
          template (when report-id
                     (compliance-store/get-report-template ds report-id :org-id org-id))]
      (if-not template
        {:status 303 :headers {"Location" "/admin/compliance"}}
        (let [run (compliance-store/create-report-run! ds
                    {:report-id report-id
                     :org-id org-id
                     :generated-by (:username (auth/current-user req))
                     :period-start period-start
                     :period-end period-end})
              result (compliance/generate-report! ds template (:id run)
                       {:org-id org-id
                        :period-start period-start
                        :period-end period-end})]
          (try (metrics/record-compliance-report! registry (:report-type template))
               (catch Exception _))
          {:status 303
           :headers {"Location" (str "/admin/compliance/runs/" (:id run))}})))))

(defn compliance-run-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          run-id (get-in req [:path-params :id])
          run (compliance-store/get-report-run ds run-id :org-id org-id)
          template (when run
                     (compliance-store/get-report-template ds (:report-id run)))]
      (if-not run
        (not-found "Report run not found")
        (html-response
          (v-compliance/render-run-detail
            {:run run
             :report-template template
             :csrf-token (csrf-token req)
             :user (auth/current-user req)
             :auth-enabled (get-in config [:auth :enabled] false)}))))))

(defn compliance-run-export [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          run-id (get-in req [:path-params :id])
          run (compliance-store/get-report-run ds run-id :org-id org-id)]
      (if-not run
        (not-found "Report run not found")
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Content-Disposition" (str "attachment; filename=\"compliance-report-" run-id ".json\"")}
         :body (or (:summary run) "{}")}))))

(defn hash-chain-verify-handler [system]
  (fn [req]
    (let [ds (:db system)
          registry (:metrics system)
          org-id (auth/current-org-id req)
          result (compliance/verify-hash-chain ds {:org-id org-id})]
      (try (metrics/record-hash-chain-verification! registry
             (if (:valid result) :valid :invalid))
           (catch Exception _))
      {:status 303
       :headers {"Location" "/admin/compliance"}})))

;; ---------------------------------------------------------------------------
;; Policy Management (admin only)
;; ---------------------------------------------------------------------------

(defn policies-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          policies (try (policy-store/list-policies ds :org-id org-id)
                        (catch Exception _ []))]
      (html-response
        (v-policies/render {:policies policies
                            :csrf-token (csrf-token req)
                            :user (auth/current-user req)
                            :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn policy-new-page [system]
  (fn [req]
    (let [config (:config system)]
      (html-response
        (v-policies/render-form {:csrf-token (csrf-token req)
                                 :user (auth/current-user req)
                                 :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          pname (get params "name")
          rules-str (get params "rules")]
      (cond
        (str/blank? pname)
        (html-response
          (v-policies/render-form {:error "Policy name is required"
                                   :policy {:description (get params "description")
                                            :policy-type (get params "policy-type")
                                            :rules rules-str
                                            :priority (get params "priority")}
                                   :csrf-token (csrf-token req)
                                   :user (auth/current-user req)
                                   :auth-enabled (get-in (:config system) [:auth :enabled] false)}))

        :else
        (let [rules (try (json/read-str rules-str :key-fn keyword) (catch Exception _ nil))
              enabled? (= "true" (get params "enabled"))]
          (if (nil? rules)
            (html-response
              (v-policies/render-form {:error "Invalid JSON in rules field"
                                       :policy {:name pname
                                                :description (get params "description")
                                                :policy-type (get params "policy-type")
                                                :rules rules-str
                                                :priority (get params "priority")}
                                       :csrf-token (csrf-token req)
                                       :user (auth/current-user req)
                                       :auth-enabled (get-in (:config system) [:auth :enabled] false)}))
            (do
              (try
                (policy-store/create-policy! ds
                  {:org-id org-id
                   :name pname
                   :description (get params "description")
                   :policy-type (get params "policy-type")
                   :rules rules
                   :priority (try (Integer/parseInt (get params "priority" "100"))
                                  (catch NumberFormatException _ 100))
                   :enabled enabled?
                   :created-by (:username (auth/current-user req))})
                (log/info "Policy created:" pname "by:" (:username (auth/current-user req)))
                {:status 303 :headers {"Location" "/admin/policies"}}
                (catch Exception e
                  (html-response
                    (v-policies/render-form {:error (str "Failed to create policy: " (.getMessage e))
                                             :policy {:name pname
                                                      :description (get params "description")
                                                      :policy-type (get params "policy-type")
                                                      :rules rules-str
                                                      :priority (get params "priority")}
                                             :csrf-token (csrf-token req)
                                             :user (auth/current-user req)
                                             :auth-enabled (get-in (:config system) [:auth :enabled] false)})))))))))))

(defn policy-edit-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])
          policy (policy-store/get-policy ds policy-id :org-id org-id)]
      (if (nil? policy)
        (not-found "Policy not found")
        (html-response
          (v-policies/render-form {:policy policy
                                   :editing? true
                                   :csrf-token (csrf-token req)
                                   :user (auth/current-user req)
                                   :auth-enabled (get-in config [:auth :enabled] false)}))))))

(defn update-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])
          policy (policy-store/get-policy ds policy-id :org-id org-id)
          params (:form-params req)]
      (if (nil? policy)
        (not-found "Policy not found")
        (let [rules (try (json/read-str (get params "rules" "{}") :key-fn keyword)
                         (catch Exception _ nil))]
          (if (nil? rules)
            (html-response
              (v-policies/render-form {:error "Invalid JSON in rules field"
                                       :policy policy :editing? true
                                       :csrf-token (csrf-token req)
                                       :user (auth/current-user req)
                                       :auth-enabled (get-in (:config system) [:auth :enabled] false)}))
            (do
              (policy-store/update-policy! ds policy-id
                {:name (get params "name")
                 :description (get params "description")
                 :policy-type (get params "policy-type")
                 :rules rules
                 :priority (try (Integer/parseInt (get params "priority" "100"))
                                (catch NumberFormatException _ 100))}
                :org-id org-id)
              (log/info "Policy updated:" policy-id "by:" (:username (auth/current-user req)))
              {:status 303 :headers {"Location" "/admin/policies"}})))))))

(defn delete-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])]
      (try
        (policy-store/delete-policy! ds policy-id :org-id org-id)
        (log/info "Policy deleted:" policy-id "by:" (:username (auth/current-user req)))
        {:status 303 :headers {"Location" "/admin/policies"}}
        (catch clojure.lang.ExceptionInfo _
          (not-found "Policy not found"))))))

(defn toggle-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])
          policy (policy-store/get-policy ds policy-id :org-id org-id)]
      (when policy
        (policy-store/toggle-policy! ds policy-id (not (:enabled policy)) :org-id org-id)
        (log/info "Policy toggled:" policy-id "enabled=" (not (:enabled policy))
                  "by:" (:username (auth/current-user req))))
      {:status 303 :headers {"Location" "/admin/policies"}})))

(defn policy-evaluations-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          evaluations (try (policy-store/list-evaluations ds :org-id org-id :limit 100)
                           (catch Exception _ []))]
      (html-response
        (v-policies/render-evaluations
          {:evaluations evaluations
           :csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn api-evaluate-policies [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policies (try (policy-store/list-policies ds :org-id org-id :enabled-only true)
                        (catch Exception _ []))]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:policy-count (count policies)
                              :policies (mapv #(select-keys % [:id :name :policy-type :priority])
                                              policies)})})))

;; --- Plugin Policies (admin) ---

(defn plugin-policies-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          policies (try (plugin-policy-store/list-plugin-policies ds :org-id org-id)
                        (catch Exception _ []))
          flash (get-in req [:params :flash])]
      (html-response
        (v-plugin-policies/render
          {:policies policies
           :flash flash
           :csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn set-plugin-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plugin-name (get-in req [:params :plugin-name])
          trust-level (get-in req [:params :trust-level] "trusted")]
      (when (and plugin-name (not (str/blank? plugin-name)))
        (plugin-policy-store/set-plugin-policy! ds
          {:org-id org-id
           :plugin-name (str/trim plugin-name)
           :trust-level trust-level
           :allowed true
           :created-by (:username (auth/current-user req))}))
      {:status 303
       :headers {"Location" "/admin/plugins/policies"}})))

(defn delete-plugin-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plugin-name (get-in req [:path-params :name])]
      (plugin-policy-store/delete-plugin-policy! ds plugin-name :org-id org-id)
      {:status 303
       :headers {"Location" "/admin/plugins/policies"}})))

;; --- Docker Policies (admin) ---

(defn docker-policies-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          policies (try (docker-policy-store/list-docker-policies ds :org-id org-id)
                        (catch Exception _ []))
          flash (get-in req [:params :flash])]
      (html-response
        (v-docker-policies/render
          {:policies policies
           :flash flash
           :csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-docker-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-type (get-in req [:params :policy-type])
          pattern (get-in req [:params :pattern])
          action (get-in req [:params :action] "allow")
          priority (try (Integer/parseInt (str (get-in req [:params :priority])))
                        (catch Exception _ 100))]
      (when (and policy-type pattern (not (str/blank? pattern)))
        (docker-policy-store/create-docker-policy! ds
          {:org-id org-id
           :policy-type policy-type
           :pattern (str/trim pattern)
           :action action
           :priority priority
           :created-by (:username (auth/current-user req))}))
      {:status 303
       :headers {"Location" "/admin/docker/policies"}})))

(defn delete-docker-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])]
      (docker-policy-store/delete-docker-policy! ds policy-id :org-id org-id)
      {:status 303
       :headers {"Location" "/admin/docker/policies"}})))

;; ---------------------------------------------------------------------------
;; Trace handlers (Phase 5: Observability)
;; ---------------------------------------------------------------------------

(defn traces-page [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          traces (or (tracing/list-traces system :org-id org-id :limit 100) [])]
      (html-response
        (str (h/html (v-traces/trace-list
                       {:traces traces
                        :csrf-token (csrf-token req)})))))))

(defn trace-detail-page [system]
  (fn [req]
    (let [trace-id (get-in req [:path-params :trace-id])
          spans (or (tracing/get-trace system trace-id) [])]
      (if (empty? spans)
        (not-found "Trace not found")
        (html-response
          (str (h/html (v-traces/trace-detail
                         {:trace-id trace-id
                          :spans spans
                          :csrf-token (csrf-token req)}))))))))

(defn trace-otlp-export [system]
  (fn [req]
    (let [trace-id (get-in req [:path-params :trace-id])
          otlp (tracing/export-otlp system trace-id)]
      (if otlp
        {:status 200
         :headers {"Content-Type" "application/json"
                   "Content-Disposition" (str "attachment; filename=\"trace-" trace-id ".json\"")}
         :body (json/write-str otlp)}
        (not-found "Trace not found")))))

;; ---------------------------------------------------------------------------
;; Global SSE handler (Phase 5: Browser Notifications)
;; ---------------------------------------------------------------------------

(defn global-events-sse [system]
  (fn [req]
    ((sse/global-sse-handler system) req)))

;; ---------------------------------------------------------------------------
;; Analytics handlers (Phase 5: Observability)
;; ---------------------------------------------------------------------------

(defn analytics-page [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          period-type (get params "period" "daily")
          trends (analytics/get-build-trends system
                   :org-id org-id :period-type period-type :limit 30)
          slowest (analytics/get-slowest-stages system
                    :org-id org-id :period-type period-type :limit 10)
          flaky (analytics/get-flaky-stages system
                  :org-id org-id :period-type period-type)]
      (html-response
        (str (h/html (v-analytics/analytics-page
                       {:trends trends
                        :slowest-stages slowest
                        :flaky-stages flaky
                        :period-type period-type
                        :csrf-token (csrf-token req)})))))))

(defn api-analytics-trends [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          period-type (get params "period" "daily")
          job-id (get params "job-id")
          limit (try (some-> (get params "limit") Integer/parseInt)
                     (catch Exception _ 30))
          trends (analytics/get-build-trends system
                   :org-id org-id :job-id job-id
                   :period-type period-type :limit limit)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:period-type period-type
                              :count (count trends)
                              :trends trends})})))

(defn api-analytics-stages [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          params (or (:query-params req) (:params req) {})
          period-type (get params "period" "daily")
          slowest (analytics/get-slowest-stages system
                    :org-id org-id :period-type period-type :limit 20)
          flaky (analytics/get-flaky-stages system
                  :org-id org-id :period-type period-type)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:slowest-stages slowest
                              :flaky-stages flaky})})))

;; ---------------------------------------------------------------------------
;; Cost attribution handlers (Phase 5)
;; ---------------------------------------------------------------------------

(defn cost-page [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          summary (cost/get-org-cost-summary system :org-id org-id)
          total (cost/get-total-cost system :org-id org-id)]
      (html-response
        (str (h/html (v-cost/cost-page
                       {:cost-summary summary
                        :total-cost total
                        :csrf-token (csrf-token req)})))))))

(defn api-cost-summary [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          summary (cost/get-org-cost-summary system :org-id org-id)
          total (cost/get-total-cost system :org-id org-id)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:total-cost total
                              :jobs summary})})))

;; ---------------------------------------------------------------------------
;; Flaky test detection handlers (Phase 5)
;; ---------------------------------------------------------------------------

(defn flaky-tests-page [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          flaky (test-result-store/list-flaky-tests (:db system) :org-id org-id)]
      (html-response
        (str (h/html (v-flaky/flaky-tests-page
                       {:flaky-tests flaky
                        :csrf-token (csrf-token req)})))))))

(defn api-flaky-tests [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)
          flaky (test-result-store/list-flaky-tests (:db system) :org-id org-id)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:count (count flaky)
                              :flaky-tests flaky})})))

;; ---------------------------------------------------------------------------
;; Phase 6: Advanced SCM & Workflow handlers
;; ---------------------------------------------------------------------------

(defn cron-schedules-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          schedules (cron-store/list-schedules ds :org-id org-id)
          jobs (job-store/list-jobs ds :org-id org-id)]
      (html-response
        (str (h/html
               (v-cron/schedules-panel
                 {:schedules schedules :jobs jobs :csrf-token (csrf-token req)})))))))

(defn webhook-replay-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          events (webhook-log/list-replayable-events ds :org-id org-id)]
      (html-response
        (str (h/html
               (v-replay/replayable-events-panel
                 {:events events :csrf-token (csrf-token req)})))))))

(defn api-replay-webhook [system]
  (fn [req]
    (let [event-id (get-in req [:path-params :id])
          result (webhook-replay/replay-webhook!
                   system event-id
                   (webhook/webhook-handler system build-runner/build-executor))]
      {:status (if (= :replayed (:status result)) 200 400)
       :headers {"Content-Type" "application/json"}
       :body (json/write-str result)})))

(defn api-create-cron-schedule [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (or (:params req) {})
          job-id (:job-id params)
          cron-expr (:cron-expression params)
          timezone (or (:timezone params) "UTC")
          validation (cron/validate-cron-expression cron-expr)]
      (if-not (:valid? validation)
        {:status 400
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:error "Invalid cron expression"
                                :details (:error validation)})}
        (let [next-run (cron/next-run-time cron-expr (str (java.time.Instant/now)) timezone)
              _sched (cron-store/create-schedule! ds
                       {:job-id job-id :org-id org-id
                        :cron-expression cron-expr
                        :timezone timezone
                        :next-run-at next-run})]
          {:status 303
           :headers {"Location" "/admin/cron"}})))))

(defn api-delete-cron-schedule [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          sched-id (get-in req [:path-params :id])]
      (cron-store/delete-schedule! ds sched-id :org-id org-id)
      {:status 303
       :headers {"Location" "/admin/cron"}})))

(defn api-create-dependency [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (or (:params req) {})
          job-id (get-in req [:path-params :job-id])
          upstream-job-id (:upstream-job-id params)
          trigger-on (or (:trigger-on params) "success")]
      (try
        (build-deps/add-dependency!
          ds
          {:job-id job-id
           :depends-on-job-id upstream-job-id
           :trigger-on trigger-on
           :org-id org-id})
        {:status 303
         :headers {"Location" (str "/jobs/" job-id)}}
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})})))))

(defn api-delete-dependency [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          dep-id (get-in req [:path-params :id])]
      (dep-store/delete-dependency! ds dep-id :org-id org-id)
      {:status 303
       :headers {"Location" "/"}})))

(defn api-create-pr-check [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-id (get-in req [:path-params :job-id])
          params (or (:params req) {})
          check-name (:check-name params)
          description (:description params)
          required (= "true" (:required params))]
      (pr-check-store/create-check! ds
        {:job-id job-id :org-id org-id
         :check-name check-name :description description
         :required required})
      {:status 303
       :headers {"Location" (str "/jobs/" job-id)}})))

(defn api-delete-pr-check [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-id (get-in req [:path-params :job-id])
          check-id (get-in req [:path-params :check-id])]
      (pr-check-store/delete-check! ds check-id :org-id org-id)
      {:status 303
       :headers {"Location" (str "/jobs/" job-id)}})))
