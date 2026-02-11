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
            [chengis.db.provenance-store :as provenance-store]
            [chengis.db.sbom-store :as sbom-store]
            [chengis.db.scan-store :as scan-store]
            [chengis.db.opa-store :as opa-store]
            [chengis.db.license-store :as license-store]
            [chengis.db.signature-store :as signature-store]
            [chengis.db.regulatory-store :as regulatory-store]
            [chengis.engine.regulatory :as regulatory-engine]
            [chengis.engine.signing :as signing-engine]
            [chengis.web.views.supply-chain :as v-supply-chain]
            [chengis.web.views.regulatory :as v-regulatory]
            [chengis.web.views.signatures :as v-signatures]
            [chengis.web.webhook :as webhook]
            [chengis.web.saml :as saml]
            [chengis.web.ldap :as ldap]
            [chengis.web.mfa :as mfa]
            [chengis.web.views.mfa :as v-mfa]
            [chengis.web.views.permissions :as v-permissions]
            [chengis.web.views.shared-resources :as v-shared]
            [chengis.web.views.secret-rotation :as v-rotation]
            [chengis.web.views.pipeline-viz :as v-pipeline-viz]
            [chengis.web.views.log-search :as v-log-search]
            [chengis.web.views.build-compare :as v-build-compare]
            [chengis.engine.build-compare :as build-compare]
            [chengis.db.log-search-store :as log-search-store]
            [chengis.web.views.linter :as v-linter]
            [chengis.engine.linter :as linter]
            [chengis.db.permission-store :as permission-store]
            [chengis.db.shared-resource-store :as shared-store]
            [chengis.db.rotation-store :as rotation-store]
            [chengis.db.environment-store :as env-store]
            [chengis.db.release-store :as release-store]
            [chengis.db.promotion-store :as promotion-store]
            [chengis.db.strategy-store :as strategy-store]
            [chengis.db.deployment-store :as deployment-store]
            [chengis.db.health-check-store :as hc-store]
            [chengis.engine.release :as release-engine]
            [chengis.engine.promotion :as promotion-engine]
            [chengis.engine.deployment :as deployment-engine]
            [chengis.web.views.environments :as v-environments]
            [chengis.web.views.releases :as v-releases]
            [chengis.web.views.promotions :as v-promotions]
            [chengis.web.views.strategies :as v-strategies]
            [chengis.web.views.deployments :as v-deployments]
            [chengis.web.views.deploy-dashboard :as v-deploy-dashboard]
            [chengis.db.iac-store :as iac-store]
            [chengis.db.iac-cost-store :as iac-cost-store]
            [chengis.engine.iac-state :as iac-state]
            [chengis.web.views.iac :as v-iac]
            [chengis.web.views.iac-plans :as v-iac-plans]
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

(defn- json-not-found [msg]
  {:status 404
   :headers {"Content-Type" "application/json"}
   :body (json/write-str {:error msg})})

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

(defn pipeline-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          job-name (get-in req [:path-params :name])
          job (job-store/get-job ds job-name :org-id org-id)]
      (if-not job
        (not-found (str "Job not found: " job-name))
        (html-response
          (v-pipeline-viz/render-pipeline-detail-page
            {:job job
             :pipeline (:pipeline job)
             :csrf-token (csrf-token req)
             :user (auth/current-user req)
             :auth-enabled (get-in (:config system) [:auth :enabled] false)}))))))

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
          saml-enabled? (saml/saml-enabled? config)
          saml-name (when saml-enabled?
                      (get-in config [:saml :provider-name]))
          ;; Support ?error= query param from OIDC/SAML callback redirects
          error (or (get (:query-params req) "error")
                    (get (:params req) "error"))]
      (html-response
        (v-login/render {:csrf-token (csrf-token req)
                          :error error
                          :oidc-enabled oidc-enabled?
                          :oidc-provider-name oidc-name
                          :saml-enabled saml-enabled?
                          :saml-provider-name saml-name})))))

(defn login-submit [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          lockout-config (get-in config [:auth :lockout])
          params (:form-params req)
          username (get params "username")
          password (get params "password")
          ip-address (or (:remote-addr req) "unknown")
          ;; Try LDAP first when enabled, then fall back to local
          result (if (ldap/ldap-enabled? config)
                   (let [ldap-result (ldap/ldap-login! ds config username password (:metrics system) lockout-config)]
                     (if (:success ldap-result)
                       ldap-result
                       ;; Fallback to local auth
                       (auth/login! ds username password (:metrics system) lockout-config)))
                   (auth/login! ds username password (:metrics system) lockout-config))]
      ;; Log the login attempt for forensics
      (lockout/log-login-attempt! ds username ip-address (:success result))
      (if (:success result)
        ;; Check MFA enrollment
        (let [user (:user result)]
          (if (and (feature-flags/enabled? config :mfa-totp)
                   (mfa/totp-enrolled? ds (:id user)))
            ;; MFA required — set pending session
            {:status 303
             :headers {"Location" "/auth/mfa/challenge"}
             :session {:mfa-pending true :mfa-user-id (:id user)}}
            ;; No MFA — normal login
            {:status 303
             :headers {"Location" "/"}
             :session {:user user}}))
        ;; Re-render login with error
        (let [oidc-enabled? (get-in config [:oidc :enabled] false)
              saml-enabled? (saml/saml-enabled? config)]
          (html-response
            (v-login/render {:error (:error result)
                              :csrf-token (csrf-token req)
                              :oidc-enabled oidc-enabled?
                              :oidc-provider-name (when oidc-enabled?
                                                    ((resolve 'chengis.web.oidc/oidc-provider-name) config))
                              :saml-enabled saml-enabled?
                              :saml-provider-name (when saml-enabled?
                                                    (get-in config [:saml :provider-name]))})))))))

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

;; ---------------------------------------------------------------------------
;; Phase 7: Supply Chain Security Handlers
;; ---------------------------------------------------------------------------

(defn supply-chain-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          attestations (provenance-store/list-attestations ds :org-id org-id :limit 20)
          sboms (sbom-store/list-sboms ds :org-id org-id :limit 20)
          scans (scan-store/list-scans ds :org-id org-id :limit 20)
          license-reports (license-store/list-reports ds :org-id org-id :limit 20)
          signatures (signature-store/list-signatures ds :org-id org-id :limit 20)]
      (html-response
        (str (h/html
          (v-supply-chain/supply-chain-dashboard
            {:attestations attestations
             :sboms sboms
             :scans scans
             :license-reports license-reports
             :signatures signatures
             :csrf-token (csrf-token req)})))))))

(defn supply-chain-build-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          attestation (provenance-store/get-attestation ds build-id :org-id org-id)
          sboms (sbom-store/get-build-sboms ds build-id :org-id org-id)
          scans (scan-store/get-build-scans ds build-id :org-id org-id)
          license-report (license-store/get-build-report ds build-id :org-id org-id)
          signatures (signature-store/get-build-signatures ds build-id :org-id org-id)]
      (html-response
        (str (h/html
          [:div {:class "space-y-6"}
           [:h2 {:class "text-xl font-bold text-gray-900"}
            (str "Supply Chain — Build " (subs build-id 0 (min 8 (count build-id))))]
           (when attestation
             (v-supply-chain/supply-chain-dashboard
               {:attestations [attestation]
                :sboms sboms
                :scans scans
                :license-reports (if license-report [license-report] [])
                :signatures signatures
                :csrf-token (csrf-token req)}))
           (when (and (nil? attestation) (empty? sboms) (empty? scans))
             [:div {:class "bg-white rounded-lg shadow p-8 text-center"}
              [:p {:class "text-gray-500"} "No supply chain data for this build."]])]))))))

(defn regulatory-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          framework-names (regulatory-store/list-frameworks ds :org-id org-id)
          frameworks (mapv (fn [fw]
                             (let [checks (regulatory-store/get-framework-checks ds fw :org-id org-id)
                                   passing (count (filter #(= "passing" (:status %)) checks))
                                   total (count checks)]
                               {:framework fw
                                :checks checks
                                :score {:passing passing
                                        :total total
                                        :percentage (if (pos? total)
                                                      (* 100.0 (/ (double passing) (double total)))
                                                      0.0)}}))
                           framework-names)]
      (html-response
        (str (h/html
          (v-regulatory/regulatory-dashboard
            {:frameworks frameworks
             :csrf-token (csrf-token req)})))))))

(defn opa-policies-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policies (opa-store/list-policies ds :org-id org-id)]
      (html-response
        (str (h/html
          [:div {:class "space-y-6"}
           [:div {:class "flex items-center justify-between"}
            [:h2 {:class "text-xl font-bold text-gray-900"} "OPA Policies"]
            [:form {:method "POST" :action "/api/supply-chain/opa" :class "inline"}
             [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf-token req)}]
             [:details {:class "inline relative"}
              [:summary {:class "px-4 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700 cursor-pointer"}
               "Add Policy"]
              [:div {:class "absolute right-0 mt-2 bg-white shadow-lg rounded-lg p-4 z-10 w-96 border"}
               [:div {:class "space-y-3"}
                [:input {:type "text" :name "name" :placeholder "Policy name"
                         :class "w-full border rounded px-3 py-2 text-sm" :required true}]
                [:input {:type "text" :name "package-name" :placeholder "Package (e.g. chengis.build)"
                         :class "w-full border rounded px-3 py-2 text-sm" :required true}]
                [:textarea {:name "rego-source" :placeholder "Rego source code..."
                            :class "w-full border rounded px-3 py-2 text-sm font-mono h-32" :required true}]
                [:input {:type "text" :name "description" :placeholder "Description (optional)"
                         :class "w-full border rounded px-3 py-2 text-sm"}]
                [:button {:type "submit"
                          :class "w-full px-4 py-2 bg-green-600 text-white rounded text-sm hover:bg-green-700"}
                 "Create Policy"]]]]]]
           (if (empty? policies)
             [:div {:class "bg-white rounded-lg shadow p-8 text-center"}
              [:p {:class "text-gray-500"} "No OPA policies configured."]]
             [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
              [:table {:class "w-full text-sm"}
               [:thead
                [:tr {:class "text-left text-gray-500 border-b"}
                 [:th {:class "py-2 font-medium"} "Name"]
                 [:th {:class "py-2 font-medium"} "Package"]
                 [:th {:class "py-2 font-medium"} "Enabled"]
                 [:th {:class "py-2 font-medium"} "Actions"]]]
               [:tbody
                (for [p policies]
                  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
                   [:td {:class "py-2 font-semibold"} (:name p)]
                   [:td {:class "py-2 font-mono text-xs"} (:package-name p)]
                   [:td {:class "py-2"}
                    (if (= 1 (:enabled p))
                      [:span {:class "text-green-600"} "Yes"]
                      [:span {:class "text-gray-400"} "No"])]
                   [:td {:class "py-2"}
                    [:form {:method "POST"
                            :action (str "/api/supply-chain/opa/" (:id p) "/delete")
                            :class "inline"}
                     [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf-token req)}]
                     [:button {:type "submit"
                               :class "text-red-600 hover:text-red-800 text-xs"}
                      "Delete"]]]])]]])]))))))

(defn license-policies-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policies (license-store/list-license-policies ds :org-id org-id)]
      (html-response
        (str (h/html
          [:div {:class "space-y-6"}
           [:div {:class "flex items-center justify-between"}
            [:h2 {:class "text-xl font-bold text-gray-900"} "License Policies"]
            [:form {:method "POST" :action "/api/supply-chain/licenses/policy" :class "inline"}
             [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf-token req)}]
             [:div {:class "flex items-center gap-2"}
              [:input {:type "text" :name "license-id" :placeholder "SPDX ID (e.g. MIT)"
                       :class "border rounded px-3 py-2 text-sm" :required true}]
              [:select {:name "action" :class "border rounded px-3 py-2 text-sm"}
               [:option {:value "allow"} "Allow"]
               [:option {:value "deny"} "Deny"]]
              [:button {:type "submit"
                        :class "px-4 py-2 bg-blue-600 text-white rounded text-sm hover:bg-blue-700"}
               "Add Rule"]]]]
           (if (empty? policies)
             [:div {:class "bg-white rounded-lg shadow p-8 text-center"}
              [:p {:class "text-gray-500"} "No license policies configured. All licenses allowed by default."]]
             [:div {:class "bg-white rounded-lg shadow-sm border p-5"}
              [:table {:class "w-full text-sm"}
               [:thead
                [:tr {:class "text-left text-gray-500 border-b"}
                 [:th {:class "py-2 font-medium"} "License"]
                 [:th {:class "py-2 font-medium"} "Action"]
                 [:th {:class "py-2 font-medium"} "Actions"]]]
               [:tbody
                (for [p policies]
                  [:tr {:class "border-b border-gray-100 hover:bg-gray-50"}
                   [:td {:class "py-2 font-mono"} (:license-id p)]
                   [:td {:class "py-2"}
                    (if (= "allow" (:action p))
                      [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"} "Allow"]
                      [:span {:class "px-2 py-0.5 rounded text-xs font-medium bg-red-100 text-red-800"} "Deny"])]
                   [:td {:class "py-2"}
                    [:form {:method "POST"
                            :action (str "/api/supply-chain/licenses/policy/" (:id p) "/delete")
                            :class "inline"}
                     [:input {:type "hidden" :name "__anti-forgery-token" :value (csrf-token req)}]
                     [:button {:type "submit"
                               :class "text-red-600 hover:text-red-800 text-xs"}
                      "Delete"]]]])]]])]))))))

;; --- Phase 7: Supply Chain API Handlers ---

(defn api-provenance [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          att (provenance-store/get-attestation ds build-id :org-id org-id)]
      (if att
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str att)}
        (json-not-found (str "No provenance for build " build-id))))))

(defn api-sbom [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          fmt (get-in req [:path-params :format])
          sboms (sbom-store/get-build-sboms ds build-id :org-id org-id)
          sbom (first (filter #(= fmt (:sbom-format %)) sboms))]
      (if sbom
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (or (:sbom-content sbom) (json/write-str sbom))}
        (json-not-found (str "No SBOM (" fmt ") for build " build-id))))))

(defn api-build-scans [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          scans (scan-store/get-build-scans ds build-id :org-id org-id)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str scans)})))

(defn api-build-licenses [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          report (license-store/get-build-report ds build-id :org-id org-id)]
      (if report
        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-str report)}
        (json-not-found (str "No license report for build " build-id))))))

(defn api-create-opa-policy [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (or (:params req) {})
          policy-name (:name params)
          package-name (:package-name params)
          rego-source (:rego-source params)
          description (:description params)]
      (try
        (opa-store/create-policy! ds
          {:org-id org-id
           :name policy-name
           :package-name package-name
           :rego-source rego-source
           :description description})
        {:status 303
         :headers {"Location" "/admin/supply-chain/opa"}}
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})})))))

(defn api-delete-opa-policy [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])]
      (opa-store/delete-policy! ds policy-id :org-id org-id)
      {:status 303
       :headers {"Location" "/admin/supply-chain/opa"}})))

(defn api-create-license-policy [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (or (:params req) {})
          license-id (:license-id params)
          action (or (:action params) "allow")]
      (try
        (license-store/create-license-policy! ds
          {:org-id org-id
           :license-id license-id
           :action action})
        {:status 303
         :headers {"Location" "/admin/supply-chain/licenses"}}
        (catch Exception e
          {:status 400
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})})))))

(defn api-delete-license-policy [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          policy-id (get-in req [:path-params :id])]
      (license-store/delete-license-policy! ds policy-id :org-id org-id)
      {:status 303
       :headers {"Location" "/admin/supply-chain/licenses"}})))

(defn api-verify-signatures [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          build-id (get-in req [:path-params :build-id])
          sigs (signature-store/get-build-signatures ds build-id :org-id org-id)
          results (mapv (fn [sig]
                          (try
                            (let [vresult (signing-engine/verify-signature! system sig)]
                              {:id (:id sig) :verified (:verified? vresult)})
                            (catch Exception _
                              {:id (:id sig) :verified false})))
                        sigs)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:results results})})))

(defn api-regulatory-framework [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          framework (get-in req [:path-params :framework])
          checks (regulatory-store/get-framework-checks ds framework :org-id org-id)
          passing (count (filter #(= "passing" (:status %)) checks))
          total (count checks)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:framework framework
                              :checks checks
                              :passing passing
                              :total total
                              :percentage (if (pos? total)
                                            (* 100.0 (/ (double passing) (double total)))
                                            0.0)})})))

(defn api-regulatory-assess [system]
  (fn [req]
    (let [org-id (auth/current-org-id req)]
      (try
        (regulatory-engine/assess-and-store! system org-id)
        {:status 303
         :headers {"Location" "/admin/regulatory"}}
        (catch Exception e
          {:status 500
           :headers {"Content-Type" "application/json"}
           :body (json/write-str {:error (.getMessage e)})})))))

;; ---------------------------------------------------------------------------
;; Phase 8: MFA Handlers
;; ---------------------------------------------------------------------------

(defn mfa-challenge-page [_system]
  (fn [req]
    (let [error (get (:query-params req) "error")]
      (html-response (v-mfa/render-challenge-page {:csrf-token (csrf-token req) :error error})))))

(defn mfa-challenge-submit [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          session (:session req)
          user-id (:mfa-user-id session)
          code (get (:form-params req) "code")]
      (if (and user-id (mfa/check-totp! ds user-id code config))
        (let [user (user-store/get-user ds user-id)]
          {:status 303
           :headers {"Location" "/"}
           :session {:user {:id (:id user)
                            :username (:username user)
                            :role (keyword (:role user))
                            :session-version (or (:session-version user) 0)}}})
        {:status 303
         :headers {"Location" "/auth/mfa/challenge?error=Invalid+code"}}))))

(defn mfa-recovery-page [_system]
  (fn [req]
    (let [error (get (:query-params req) "error")]
      (html-response (v-mfa/render-recovery-page {:csrf-token (csrf-token req) :error error})))))

(defn mfa-recovery-submit [system]
  (fn [req]
    (let [ds (:db system)
          session (:session req)
          user-id (:mfa-user-id session)
          code (get (:form-params req) "recovery-code")]
      (if (and user-id (mfa/use-recovery-code! ds user-id code))
        (let [user (user-store/get-user ds user-id)]
          {:status 303
           :headers {"Location" "/"}
           :session {:user {:id (:id user)
                            :username (:username user)
                            :role (keyword (:role user))
                            :session-version (or (:session-version user) 0)}}})
        {:status 303
         :headers {"Location" "/auth/mfa/recovery?error=Invalid+recovery+code"}}))))

(defn mfa-settings-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          user (auth/current-user req)
          enrolled? (mfa/totp-enrolled? ds (:id user))
          message (get (:query-params req) "success")
          error (get (:query-params req) "error")]
      (html-response (v-mfa/render-settings-page {:csrf-token (csrf-token req)
                                                    :mfa-enabled enrolled?
                                                    :user user
                                                    :auth-enabled (get-in config [:auth :enabled] false)
                                                    :message message
                                                    :error error})))))

(defn mfa-setup-submit [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          user (auth/current-user req)
          result (mfa/enroll-totp! ds (:id user) config)]
      (html-response (v-mfa/render-setup-page {:csrf-token (csrf-token req)
                                                :secret-b32 (:secret-b32 result)
                                                :totp-uri (:uri result)
                                                :recovery-codes (:recovery-codes result)
                                                :user user
                                                :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn mfa-confirm-submit [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          user (auth/current-user req)
          code (get (:form-params req) "code")]
      (if (mfa/confirm-totp! ds (:id user) code config)
        {:status 303 :headers {"Location" "/settings/mfa?success=MFA+enabled"}}
        {:status 303 :headers {"Location" "/settings/mfa?error=Invalid+code"}}))))

(defn mfa-disable-submit [system]
  (fn [req]
    (let [ds (:db system)
          user (auth/current-user req)]
      (mfa/disable-totp! ds (:id user))
      {:status 303 :headers {"Location" "/settings/mfa?success=MFA+disabled"}})))

;; ---------------------------------------------------------------------------
;; Phase 8: Permissions Handlers
;; ---------------------------------------------------------------------------

(defn permissions-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          permissions (permission-store/list-org-permissions ds org-id)
          groups (permission-store/list-groups ds :org-id org-id)
          users (user-store/list-users ds)]
      (html-response (v-permissions/render-permissions-page
                        {:csrf-token (csrf-token req)
                         :permissions permissions
                         :groups groups
                         :users users
                         :user user
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn grant-permission-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          params (:form-params req)]
      (permission-store/grant-permission! ds
        {:org-id org-id
         :user-id (get params "user-id")
         :resource-type (get params "resource-type")
         :resource-id (get params "resource-id")
         :action (get params "action")
         :granted-by (:id user)})
      {:status 303 :headers {"Location" "/admin/permissions"}})))

(defn revoke-permission-handler [system]
  (fn [req]
    (let [ds (:db system)
          perm-id (get-in req [:path-params :id])]
      (permission-store/revoke-permission! ds perm-id)
      {:status 303 :headers {"Location" "/admin/permissions"}})))

(defn permission-groups-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          groups (permission-store/list-groups ds :org-id org-id)]
      (html-response (v-permissions/render-groups-page
                        {:csrf-token (csrf-token req)
                         :groups groups
                         :user user
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-permission-group-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          params (:form-params req)]
      (permission-store/create-group! ds
        {:org-id org-id
         :name (get params "name")
         :description (get params "description")
         :created-by (:id user)})
      {:status 303 :headers {"Location" "/admin/permissions/groups"}})))

(defn permission-group-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          user (auth/current-user req)
          group-id (get-in req [:path-params :id])
          group (permission-store/get-group ds group-id)
          entries (permission-store/list-group-entries ds group-id)
          members (permission-store/list-group-members ds group-id)
          users (user-store/list-users ds)]
      (html-response (v-permissions/render-group-detail-page
                        {:csrf-token (csrf-token req)
                         :group group
                         :entries entries
                         :members members
                         :users users
                         :user user
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn delete-permission-group-handler [system]
  (fn [req]
    (let [ds (:db system)
          group-id (get-in req [:path-params :id])]
      (permission-store/delete-group! ds group-id)
      {:status 303 :headers {"Location" "/admin/permissions/groups"}})))

(defn add-group-entry-handler [system]
  (fn [req]
    (let [ds (:db system)
          group-id (get-in req [:path-params :id])
          params (:form-params req)]
      (permission-store/add-group-entry! ds
        {:group-id group-id
         :resource-type (get params "resource-type")
         :resource-id (get params "resource-id")
         :action (get params "action")})
      {:status 303 :headers {"Location" (str "/admin/permissions/groups/" group-id)}})))

(defn remove-group-entry-handler [system]
  (fn [req]
    (let [ds (:db system)
          group-id (get-in req [:path-params :id])
          entry-id (get-in req [:path-params :entry-id])]
      (permission-store/remove-group-entry! ds entry-id)
      {:status 303 :headers {"Location" (str "/admin/permissions/groups/" group-id)}})))

(defn add-group-member-handler [system]
  (fn [req]
    (let [ds (:db system)
          group-id (get-in req [:path-params :id])
          user (auth/current-user req)
          user-id (get (:form-params req) "user-id")]
      (permission-store/add-group-member! ds {:group-id group-id :user-id user-id :assigned-by (:id user)})
      {:status 303 :headers {"Location" (str "/admin/permissions/groups/" group-id)}})))

(defn remove-group-member-handler [system]
  (fn [req]
    (let [ds (:db system)
          group-id (get-in req [:path-params :id])
          user-id (get-in req [:path-params :uid])]
      (permission-store/remove-group-member! ds group-id user-id)
      {:status 303 :headers {"Location" (str "/admin/permissions/groups/" group-id)}})))

;; ---------------------------------------------------------------------------
;; Phase 8: Shared Resources Handlers
;; ---------------------------------------------------------------------------

(defn shared-resources-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          grants-from (shared-store/list-grants-from ds org-id)
          grants-to (shared-store/list-grants-to ds org-id)
          orgs (org-store/list-orgs ds)]
      (html-response (v-shared/render-shared-resources-page
                        {:csrf-token (csrf-token req)
                         :grants-from grants-from
                         :grants-to grants-to
                         :organizations orgs
                         :user user
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-shared-grant-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          params (:form-params req)]
      (shared-store/create-grant! ds
        {:source-org-id org-id
         :target-org-id (get params "target-org-id")
         :resource-type (get params "resource-type")
         :resource-id (get params "resource-id")
         :granted-by (:id user)
         :expires-at (let [v (get params "expires-at")] (when-not (str/blank? v) v))})
      {:status 303 :headers {"Location" "/admin/shared-resources"}})))

(defn revoke-shared-grant-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          grant-id (get-in req [:path-params :id])]
      (shared-store/revoke-grant! ds grant-id :source-org-id org-id)
      {:status 303 :headers {"Location" "/admin/shared-resources"}})))

;; ---------------------------------------------------------------------------
;; Phase 8: Secret Rotation Handlers
;; ---------------------------------------------------------------------------

(defn rotation-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          policies (rotation-store/list-policies ds :org-id org-id)
          ;; Collect recent version history across all policies
          versions (when (seq policies)
                     (mapcat (fn [p]
                               (rotation-store/list-versions ds org-id (:secret-name p)
                                 :secret-scope (or (:secret-scope p) "global")
                                 :limit 5))
                             policies))]
      (html-response (v-rotation/render-rotation-page
                        {:csrf-token (csrf-token req)
                         :policies policies
                         :versions (vec (take 20 (sort-by :rotated-at #(compare %2 %1) (or versions []))))
                         :user user
                         :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn create-rotation-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          user (auth/current-user req)
          params (:form-params req)]
      (rotation-store/create-policy! ds
        {:org-id org-id
         :secret-name (get params "secret-name")
         :secret-scope (or (get params "secret-scope") "global")
         :rotation-interval-days (Integer/parseInt (or (get params "rotation-interval-days") "90"))
         :max-versions (Integer/parseInt (or (get params "max-versions") "3"))
         :notify-days-before (Integer/parseInt (or (get params "notify-days-before") "7"))
         :created-by (:id user)})
      {:status 303 :headers {"Location" "/admin/rotation"}})))

(defn delete-rotation-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          policy-id (get-in req [:path-params :id])]
      (rotation-store/delete-policy! ds policy-id)
      {:status 303 :headers {"Location" "/admin/rotation"}})))

(defn toggle-rotation-policy-handler [system]
  (fn [req]
    (let [ds (:db system)
          policy-id (get-in req [:path-params :id])
          policy (rotation-store/get-policy ds policy-id)
          new-enabled (if (and (:enabled policy) (not (zero? (:enabled policy)))) 0 1)]
      (rotation-store/update-policy! ds policy-id {:enabled new-enabled})
      {:status 303 :headers {"Location" "/admin/rotation"}})))

;; ---------------------------------------------------------------------------
;; Log Search (Phase 9c)
;; ---------------------------------------------------------------------------

(defn log-search-page [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          org-id (auth/current-org-id req)
          params (merge (:params req) (:query-params req))
          query (get params "q")
          job-filter (get params "job")
          status-filter (get params "status")
          page (try (some-> (get params "page") Integer/parseInt)
                    (catch Exception _ 1))
          page (max 1 (or page 1))
          per-page 20
          jobs (job-store/list-jobs ds :org-id org-id)
          status-kw (when (and status-filter (not (str/blank? status-filter)))
                      (keyword status-filter))
          job-name (when (not (str/blank? job-filter)) job-filter)
          results (when (not (str/blank? query))
                    (log-search-store/search-build-logs ds query
                      :org-id org-id
                      :job-name job-name
                      :status status-kw
                      :limit per-page
                      :offset (* (dec page) per-page)))
          total-count (when (not (str/blank? query))
                        (log-search-store/count-search-results ds query
                          :org-id org-id
                          :job-name job-name
                          :status status-kw))]
      (html-response
        (v-log-search/render-log-search-page
          {:csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in config [:auth :enabled])
           :notifications-enabled (feature-flags/enabled? config :notifications)
           :query query
           :results (or results [])
           :total-count (or total-count 0)
           :job-filter job-filter
           :status-filter status-filter
           :jobs jobs
           :page page})))))

(defn log-search-results-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (merge (:params req) (:form-params req))
          query (get params "q")
          job-filter (get params "job")
          status-filter (get params "status")
          page (try (some-> (get params "page") Integer/parseInt)
                    (catch Exception _ 1))
          page (max 1 (or page 1))
          per-page 20
          status-kw (when (and status-filter (not (str/blank? status-filter)))
                      (keyword status-filter))
          job-name (when (not (str/blank? job-filter)) job-filter)
          results (when (not (str/blank? query))
                    (log-search-store/search-build-logs ds query
                      :org-id org-id
                      :job-name job-name
                      :status status-kw
                      :limit per-page
                      :offset (* (dec page) per-page)))
          total-count (when (not (str/blank? query))
                        (log-search-store/count-search-results ds query
                          :org-id org-id
                          :job-name job-name
                          :status status-kw))]
      (html-response
        (str (h/html
               (v-log-search/render-search-results
                 {:results (or results [])
                  :query query
                  :total-count (or total-count 0)
                  :page page
                  :per-page per-page
                  :job-filter job-filter
                  :status-filter status-filter})))))))

;; --- Build Comparison ---

(defn build-compare-page [system]
  (fn [req]
    (let [params (merge (:params req) (:query-params req))
          ds (:db system)
          org-id (auth/current-org-id req)
          build-a-id (get params "a")
          build-b-id (get params "b")
          job-name (get params "job")
          ;; Get list of recent builds for selection
          builds-list (if (and job-name (not (str/blank? job-name)))
                        (let [job (job-store/get-job ds job-name :org-id org-id)]
                          (if job
                            (build-store/list-builds ds {:job-id (:id job) :org-id org-id})
                            []))
                        (build-store/list-builds ds {:org-id org-id}))
          ;; If both builds selected, compute comparison
          comparison (when (and build-a-id build-b-id
                                (not (str/blank? build-a-id))
                                (not (str/blank? build-b-id)))
                      (let [build-a (build-store/get-build ds build-a-id :org-id org-id)
                            build-b (build-store/get-build ds build-b-id :org-id org-id)]
                        (when (and build-a build-b)
                          (let [stages-a (build-store/get-build-stages ds build-a-id)
                                stages-b (build-store/get-build-stages ds build-b-id)
                                steps-a (build-store/get-build-steps ds build-a-id)
                                steps-b (build-store/get-build-steps ds build-b-id)
                                artifacts-a (artifact-store/list-artifacts ds build-a-id)
                                artifacts-b (artifact-store/list-artifacts ds build-b-id)]
                            (build-compare/compare-builds
                              build-a stages-a steps-a artifacts-a
                              build-b stages-b steps-b artifacts-b)))))]
      (html-response
        (v-build-compare/render-compare-page
          {:csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in (:config system) [:auth :enabled] false)
           :comparison comparison
           :builds-list (or builds-list [])
           :job-name job-name
           :build-a-id build-a-id
           :build-b-id build-b-id})))))

;; ---------------------------------------------------------------------------
;; Pipeline Linter (developer+)
;; ---------------------------------------------------------------------------

(defn linter-page [system]
  (fn [req]
    (let [config (:config system)]
      (html-response
        (v-linter/render-linter-page
          {:csrf-token (csrf-token req)
           :user (auth/current-user req)
           :auth-enabled (get-in config [:auth :enabled] false)})))))

(defn linter-check-handler [system]
  (fn [req]
    (let [params (:form-params req)
          content (get params "content")
          format-type (get params "format" "edn")
          result (linter/lint-content content format-type)]
      (html-response
        (v-linter/render-lint-results {:results result})))))

;; ---------------------------------------------------------------------------
;; Phase 11: Deployment & Release Orchestration
;; ---------------------------------------------------------------------------

;; Deploy Dashboard
(defn deploy-dashboard-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          environments (env-store/list-environments ds :org-id org-id)
          env-artifacts (into {}
                          (for [env environments]
                            [(:id env)
                             (promotion-store/get-current-artifact ds (:id env))]))
          recent-deployments (deployment-store/list-deployments ds :org-id org-id :limit 20)
          pending-promotions (promotion-store/list-promotions ds :org-id org-id :status "pending")
          deployment-stats (deployment-store/count-deployments-by-status ds org-id)]
      (html-response
        (v-deploy-dashboard/render
          {:environments environments
           :env-artifacts env-artifacts
           :recent-deployments recent-deployments
           :pending-promotions pending-promotions
           :deployment-stats deployment-stats
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

;; Environments
(defn environments-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          environments (env-store/list-environments ds :org-id org-id)]
      (html-response
        (v-environments/render
          {:environments environments
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn create-environment-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)]
      (env-store/create-environment! ds
        {:org-id org-id
         :name (get params "name")
         :slug (get params "slug")
         :env-order (Integer/parseInt (or (get params "env_order") "0"))
         :description (get params "description")
         :requires-approval (= "true" (get params "requires_approval"))
         :auto-promote (= "true" (get params "auto_promote"))})
      {:status 302 :headers {"Location" "/admin/environments"}})))

(defn environment-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          env-id (get-in req [:path-params :id])
          env (env-store/get-environment ds env-id :org-id org-id)]
      (if env
        (let [current (promotion-store/get-current-artifact ds (:id env))
              deployments (deployment-store/list-deployments ds :environment-id (:id env) :limit 10)]
          (html-response
            (v-environments/render-detail
              {:environment env
               :current-artifact current
               :deployments deployments
               :csrf-token (csrf-token req)
               :user (auth/current-user req)})))
        (not-found "Environment not found")))))

(defn update-environment-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          env-id (get-in req [:path-params :id])
          params (:form-params req)]
      (env-store/update-environment! ds env-id
        {:name (get params "name")
         :description (get params "description")
         :requires-approval (= "true" (get params "requires_approval"))
         :auto-promote (= "true" (get params "auto_promote"))}
        :org-id org-id)
      {:status 302 :headers {"Location" (str "/admin/environments/" env-id)}})))

(defn delete-environment-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          env-id (get-in req [:path-params :id])]
      (env-store/delete-environment! ds env-id :org-id org-id)
      {:status 302 :headers {"Location" "/admin/environments"}})))

(defn lock-environment-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          env-id (get-in req [:path-params :id])
          user (auth/current-user req)]
      (env-store/lock-environment! ds env-id (or (:username user) "system") :org-id org-id)
      {:status 302 :headers {"Location" "/admin/environments"}})))

(defn unlock-environment-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          env-id (get-in req [:path-params :id])]
      (env-store/unlock-environment! ds env-id :org-id org-id)
      {:status 302 :headers {"Location" "/admin/environments"}})))

;; Releases
(defn releases-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          releases (release-store/list-releases ds :org-id org-id)
          all-builds (build-store/list-builds ds {:org-id org-id :limit 50})
          builds (filterv #(= :success (:status %)) all-builds)]
      (html-response
        (v-releases/render
          {:releases releases
           :builds (take 20 builds)
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn create-release-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          user (auth/current-user req)
          result (release-engine/create-release-from-build! ds
                   {:org-id org-id
                    :build-id (get params "build_id")
                    :version (let [v (get params "version")] (when-not (str/blank? v) v))
                    :title (get params "title")
                    :notes (get params "notes")
                    :created-by (:username user)})]
      {:status 302 :headers {"Location" "/deploy/releases"}})))

(defn release-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          release-id (get-in req [:path-params :id])
          release (release-store/get-release ds release-id :org-id org-id)]
      (if release
        (html-response
          (v-releases/render-detail
            {:release release
             :csrf-token (csrf-token req)
             :user (auth/current-user req)}))
        (not-found "Release not found")))))

(defn publish-release-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          release-id (get-in req [:path-params :id])]
      (release-store/publish-release! ds release-id :org-id org-id)
      {:status 302 :headers {"Location" (str "/deploy/releases/" release-id)}})))

(defn deprecate-release-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          release-id (get-in req [:path-params :id])]
      (release-store/deprecate-release! ds release-id :org-id org-id)
      {:status 302 :headers {"Location" (str "/deploy/releases/" release-id)}})))

;; Promotions
(defn promotions-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          environments (env-store/list-environments ds :org-id org-id)
          env-artifacts (into {}
                          (for [env environments]
                            [(:id env)
                             (promotion-store/get-current-artifact ds (:id env))]))
          promotions (promotion-store/list-promotions ds :org-id org-id)
          all-builds (build-store/list-builds ds {:org-id org-id :limit 50})
          builds (filterv #(= :success (:status %)) all-builds)]
      (html-response
        (v-promotions/render
          {:environments environments
           :env-artifacts env-artifacts
           :promotions promotions
           :builds (take 20 builds)
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn create-promotion-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          user (auth/current-user req)
          from-env (let [v (get params "from_environment_id")] (when-not (str/blank? v) v))]
      (promotion-engine/execute-promotion! system
        {:org-id org-id
         :build-id (get params "build_id")
         :from-environment-id from-env
         :to-environment-id (get params "to_environment_id")
         :user-id (:username user)})
      {:status 302 :headers {"Location" "/deploy/promotions"}})))

(defn approve-promotion-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          promo-id (get-in req [:path-params :id])
          user (auth/current-user req)]
      (promotion-store/approve-promotion! ds promo-id (:username user) :org-id org-id)
      (promotion-store/complete-promotion! ds promo-id)
      {:status 302 :headers {"Location" "/deploy"}})))

(defn reject-promotion-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          promo-id (get-in req [:path-params :id])
          params (:form-params req)]
      (promotion-store/reject-promotion! ds promo-id
        (or (get params "reason") "Rejected") :org-id org-id)
      {:status 302 :headers {"Location" "/deploy"}})))

;; Strategies
(defn strategies-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          strategies (strategy-store/list-strategies ds :org-id org-id)]
      (html-response
        (v-strategies/render
          {:strategies strategies
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn create-strategy-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)]
      (strategy-store/create-strategy! ds
        {:org-id org-id
         :name (get params "name")
         :strategy-type (get params "strategy_type")
         :description (get params "description")})
      {:status 302 :headers {"Location" "/deploy/strategies"}})))

(defn seed-strategies-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)]
      (strategy-store/seed-default-strategies! ds org-id)
      {:status 302 :headers {"Location" "/deploy/strategies"}})))

;; Deployments
(defn deployments-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          deployments (deployment-store/list-deployments ds :org-id org-id)]
      (html-response
        (v-deployments/render
          {:deployments deployments
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn deployment-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          dep-id (get-in req [:path-params :id])
          deployment (deployment-store/get-deployment ds dep-id :org-id org-id)]
      (if deployment
        (let [steps (deployment-store/get-deployment-steps ds (:id deployment))
              env (env-store/get-environment ds (:environment-id deployment))]
          (html-response
            (v-deployments/render-detail
              {:deployment deployment
               :steps steps
               :environment env
               :csrf-token (csrf-token req)
               :user (auth/current-user req)})))
        (not-found "Deployment not found")))))

(defn execute-deployment-handler [system]
  (fn [req]
    (let [ds (:db system)
          dep-id (get-in req [:path-params :id])]
      (deployment-engine/execute-deployment! system dep-id)
      {:status 302 :headers {"Location" (str "/deploy/deployments/" dep-id)}})))

(defn cancel-deployment-handler [system]
  (fn [req]
    (let [ds (:db system)
          dep-id (get-in req [:path-params :id])]
      (deployment-engine/cancel-deployment! ds dep-id)
      {:status 302 :headers {"Location" (str "/deploy/deployments/" dep-id)}})))

(defn rollback-deployment-handler [system]
  (fn [req]
    (let [dep-id (get-in req [:path-params :id])]
      (deployment-engine/rollback-deployment! system dep-id)
      {:status 302 :headers {"Location" "/deploy/deployments"}})))

;; ---------------------------------------------------------------------------
;; Phase 12: Infrastructure as Code
;; ---------------------------------------------------------------------------

;; IaC Dashboard
(defn iac-dashboard-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          projects (iac-store/list-projects ds :org-id org-id)
          latest-plans (iac-store/list-plans ds :org-id org-id :limit 10)
          stats (iac-store/count-plans-by-status ds org-id)]
      (html-response
        (v-iac/render-dashboard
          {:projects projects
           :latest-plans latest-plans
           :plan-stats stats
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

;; IaC Projects
(defn iac-project-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          project-id (get-in req [:path-params :id])
          project (iac-store/get-project ds project-id :org-id org-id)]
      (if project
        (let [plans (iac-store/list-plans ds :project-id project-id :org-id org-id)
              states (iac-state/list-state-versions ds project-id)
              cost-estimates (iac-cost-store/list-estimates ds :org-id org-id)]
          (html-response
            (v-iac/render-project-detail
              {:project project
               :plans plans
               :states states
               :cost-estimates cost-estimates
               :csrf-token (csrf-token req)
               :user (auth/current-user req)})))
        (not-found "IaC project not found")))))

(defn iac-create-project-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          params (:form-params req)
          user (auth/current-user req)]
      (iac-store/create-project! ds
        {:org-id org-id
         :job-id (get params "job_id")
         :tool-type (or (get params "tool_type") "terraform")
         :working-dir (or (get params "working_dir") ".")
         :auto-detect true})
      {:status 302 :headers {"Location" "/iac"}})))

(defn iac-update-project-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          project-id (get-in req [:path-params :id])
          params (:form-params req)]
      (iac-store/update-project! ds project-id
        {:tool-type (get params "tool_type")
         :working-dir (get params "working_dir")}
        :org-id org-id)
      {:status 302 :headers {"Location" (str "/iac/projects/" project-id)}})))

(defn iac-delete-project-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          project-id (get-in req [:path-params :id])]
      (iac-store/delete-project! ds project-id :org-id org-id)
      {:status 302 :headers {"Location" "/iac"}})))

;; IaC Plans
(defn iac-plans-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plans (iac-store/list-plans ds :org-id org-id)]
      (html-response
        (v-iac/render-dashboard
          {:projects []
           :latest-plans plans
           :stats {}
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn iac-plan-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plan-id (get-in req [:path-params :id])
          plan (iac-store/get-plan ds plan-id :org-id org-id)]
      (if plan
        (let [cost-estimate (iac-cost-store/get-estimate ds plan-id)]
          (html-response
            (v-iac/render-plan-detail
              {:plan plan
               :cost-estimate cost-estimate
               :csrf-token (csrf-token req)
               :user (auth/current-user req)})))
        (not-found "IaC plan not found")))))

(defn iac-approve-plan-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plan-id (get-in req [:path-params :id])
          user (auth/current-user req)]
      (iac-store/approve-plan! ds plan-id (:username user) :org-id org-id)
      {:status 302 :headers {"Location" (str "/iac/plans/" plan-id)}})))

(defn iac-reject-plan-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          plan-id (get-in req [:path-params :id])
          params (:form-params req)]
      (iac-store/update-plan! ds plan-id
        {:status "rejected"
         :error-output (or (get params "reason") "Rejected")})
      {:status 302 :headers {"Location" (str "/iac/plans/" plan-id)}})))

;; IaC States
(defn iac-states-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          project-id (get-in req [:query-params "project_id"])
          states (when project-id (iac-state/list-state-versions ds project-id))]
      (html-response
        (v-iac-plans/render-states-page
          {:states (or states [])
           :project (when project-id
                      (iac-store/get-project ds project-id :org-id org-id))
           :csrf-token (csrf-token req)
           :user (auth/current-user req)})))))

(defn iac-state-detail-page [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          state-id (get-in req [:path-params :id])
          ;; State ID lookup — try finding by querying project states
          state nil]  ;; State detail requires project-id+version, stub for now
      (if state
        (html-response
          (v-iac-plans/render-state-detail
            {:state state
             :csrf-token (csrf-token req)
             :user (auth/current-user req)}))
        (not-found "IaC state not found")))))

(defn iac-force-unlock-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          state-id (get-in req [:path-params :id])
          user (auth/current-user req)]
      (iac-state/force-unlock! ds state-id)
      {:status 302 :headers {"Location" "/iac"}})))

;; IaC Execute (trigger plan/apply)
(defn iac-execute-handler [system]
  (fn [req]
    (let [ds (:db system)
          org-id (auth/current-org-id req)
          project-id (get-in req [:path-params :id])
          params (:form-params req)
          user (auth/current-user req)
          action (get params "action" "plan")]
      (iac-store/create-plan! ds
        {:org-id org-id
         :project-id project-id
         :action action
         :status "pending"
         :initiated-by (:username user)})
      {:status 302 :headers {"Location" (str "/iac/projects/" project-id)}})))
