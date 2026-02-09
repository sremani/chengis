(ns chengis.web.handlers
  (:require [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
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
            [chengis.web.account-lockout :as lockout]
            [chengis.db.approval-store :as approval-store]
            [chengis.db.template-store :as template-store]
            [chengis.db.org-store :as org-store]
            [chengis.db.backup :as backup]
            [chengis.db.audit-export :as audit-export]
            [chengis.web.views.approvals :as v-approvals]
            [chengis.web.views.templates :as v-templates]
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
          current-alerts (try (alerts/check-alerts system)
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
          ;; Run build on bounded thread pool
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
            ;; Redirect to build page
            {:status 303
             :headers {"Location" (str "/builds/" build-id)}}
            (catch RejectedExecutionException _
              {:status 503
               :headers {"Content-Type" "text/html; charset=utf-8"
                         "Retry-After" "30"}
               :body "<h1>503</h1><p>Build queue full. Try again shortly.</p>"})))))))

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
              notifications (notification-store/list-notifications ds build-id)]
          (html-response
            (v-builds/render-detail {:build build
                                     :stages stages
                                     :steps steps
                                     :job job
                                     :artifacts artifacts
                                     :notifications notifications
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
          (try
            (.submit build-runner/build-executor
              ^Runnable (fn []
                (try
                  (build-runner/execute-build-for-record!
                    system job new-record
                    {:event-fn events/publish!
                     :parameters (:parameters build)})
                  (catch Exception e
                    (log/error e "Retry build failed:" new-id)
                    (build-store/update-build-status! ds new-id :failure
                      :completed-at (str (java.time.Instant/now)))
                    (events/publish! {:build-id new-id
                                      :event-type :build-completed
                                      :timestamp (str (java.time.Instant/now))
                                      :data {:build-status :failure}})))))
            {:status 303
             :headers {"Location" (str "/builds/" new-id)}}
            (catch RejectedExecutionException _
              {:status 503
               :headers {"Content-Type" "text/html; charset=utf-8"
                         "Retry-After" "30"}
               :body "<h1>503</h1><p>Build queue full. Try again shortly.</p>"})))))))

(defn build-events-sse [system]
  (fn [req]
    (let [build-id (get-in req [:path-params :id])]
      ((sse/sse-handler system build-id) req))))

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
                 :headers {"Content-Type" (or (:content-type artifact) "application/octet-stream")
                           "Content-Disposition" (str "attachment; filename=\"" filename "\"")}
                 :body file}
                (not-found (str "Artifact file missing from disk: " filename))))))))))

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
          backup-dir (or (get-in system [:config :backup :directory]) "/tmp")
          output-path (backup/generate-backup-path backup-dir)
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

(defn health-check [_system]
  (fn [_req]
    (let [uptime-ms (- (System/currentTimeMillis) start-time)
          uptime-s (quot uptime-ms 1000)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:status "ok"
                              :version "0.2.0"
                              :uptime-seconds uptime-s})})))

(defn readiness-check [system]
  (fn [_req]
    (try
      (let [ds (:db system)]
        ;; Try a simple query to verify DB is accessible
        (chengis.db.user-store/count-users ds)
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:status "ready"
                                :database "connected"})})
      (catch Exception e
        {:status 503
         :headers {"Content-Type" "application/json"}
         :body (json/write-str {:status "not-ready"
                                :database "unavailable"
                                :error (.getMessage e)})}))))

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

        ;; All valid — create user
        :else
        (try
          (user-store/create-user! ds {:username username :password password :role role})
          (log/info "User created:" username "role:" role "by:" (:username (auth/current-user req)))
          (render-users-page system req :message (str "User '" (escape-html username) "' created"))
          (catch Exception e
            (render-users-page system req :error (str "Failed to create user: " (.getMessage e)))))))))

(defn update-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          user-id (get-in req [:path-params :id])
          params (:form-params req)
          new-role (get params "role")]
      (cond
        (nil? new-role)
        (render-users-page system req :error "No role specified")

        (not (auth/valid-role? new-role))
        (render-users-page system req :error (str "Invalid role: " (escape-html new-role)))

        :else
        (do
          (user-store/update-user! ds user-id {:role new-role})
          (log/info "User" user-id "role changed to" new-role "by:" (:username (auth/current-user req)))
          (render-users-page system req :message "User updated"))))))

(defn toggle-user-handler [system]
  (fn [req]
    (let [ds (:db system)
          user-id (get-in req [:path-params :id])
          target-user (user-store/get-user ds user-id)
          currently-active? (pos? (or (:active target-user) 1))]
      (if currently-active?
        (do (user-store/delete-user! ds user-id)
            (log/info "User" user-id "deactivated by:" (:username (auth/current-user req)))
            (render-users-page system req :message (str "User '" (:username target-user) "' deactivated")))
        (do (user-store/update-user! ds user-id {:active true})
            (log/info "User" user-id "reactivated by:" (:username (auth/current-user req)))
            (render-users-page system req :message (str "User '" (:username target-user) "' reactivated")))))))

(defn reset-password-handler [system]
  (fn [req]
    (let [ds (:db system)
          user-id (get-in req [:path-params :id])
          params (:form-params req)
          new-password (get params "new-password")]
      (cond
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
          user-id (get-in req [:path-params :id])
          target-user (user-store/get-user ds user-id)]
      (if (nil? target-user)
        (render-users-page system req :error "User not found")
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
