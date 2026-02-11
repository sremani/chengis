(ns chengis.web.routes
  (:require [chengis.web.handlers :as h]
            [chengis.web.auth :as auth]
            [chengis.web.audit :as audit]
            [chengis.web.webhook :as webhook]
            [chengis.web.oidc :as oidc]
            [chengis.web.saml :as saml]
            [chengis.web.rate-limit :as rate-limit]
            [chengis.metrics :as metrics]
            [chengis.web.alerts :as alerts]
            [chengis.web.metrics-middleware :as metrics-mw]
            [chengis.distributed.master-api :as master-api]
            [chengis.distributed.artifact-transfer :as artifact-transfer]
            [chengis.engine.build-runner :as build-runner]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defn- build-csp-header
  "Build a Content-Security-Policy header string from config directives map."
  [directives]
  (when (seq directives)
    (str/join "; "
      (map (fn [[k v]]
             (str (name k) " " v))
           directives))))

(defn- wrap-security-headers
  "Add security headers to every response: HSTS, CSP, X-Frame-Options, etc.
   CSP directives come from :security :csp :directives config."
  [handler system]
  (let [config (:config system)
        hsts? (and (get-in config [:https :enabled] false)
                   (get-in config [:https :hsts] true))
        csp-enabled? (get-in config [:security :csp :enabled] true)
        csp-header (when csp-enabled?
                     (build-csp-header (get-in config [:security :csp :directives])))]
    (fn [req]
      (let [resp (handler req)]
        (when resp
          (update resp :headers merge
            (cond-> {"X-Content-Type-Options" "nosniff"
                     "X-Frame-Options" "DENY"
                     "Referrer-Policy" "strict-origin-when-cross-origin"
                     "X-XSS-Protection" "1; mode=block"}
              hsts?       (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains")
              csp-header  (assoc "Content-Security-Policy" csp-header))))))))

(defn- wrap-cors
  "CORS middleware: handle preflight OPTIONS requests and add CORS headers.
   Only active when :security :cors :enabled is true."
  [handler system]
  (let [config (:config system)
        cors-enabled? (get-in config [:security :cors :enabled] false)]
    (if-not cors-enabled?
      handler
      (let [allowed-origins (set (get-in config [:security :cors :allowed-origins] ["*"]))
            allowed-methods (get-in config [:security :cors :allowed-methods] ["GET" "POST" "PUT" "DELETE"])
            max-age (get-in config [:security :cors :max-age] 3600)
            methods-str (str/join ", " allowed-methods)
            max-age-str (str max-age)]
        (fn [req]
          (let [origin (get-in req [:headers "origin"])
                allowed? (or (contains? allowed-origins "*")
                             (contains? allowed-origins origin))
                cors-headers (when (and origin allowed?)
                               {"Access-Control-Allow-Origin" (if (contains? allowed-origins "*") "*" origin)
                                "Access-Control-Allow-Methods" methods-str
                                "Access-Control-Allow-Headers" "Content-Type, Authorization, X-CSRF-Token"
                                "Access-Control-Max-Age" max-age-str})]
            (if (= :options (:request-method req))
              ;; Preflight response
              {:status 204
               :headers (merge {"Content-Length" "0"} cors-headers)
               :body ""}
              ;; Regular request — add CORS headers to response
              (let [resp (handler req)]
                (if cors-headers
                  (update resp :headers merge cors-headers)
                  resp)))))))))

(def ^:private csrf-exempt-paths
  "Exact API paths that should skip CSRF validation.
   These are machine-to-machine endpoints (webhooks, agent communication)."
  #{"/api/webhook"
    "/api/agents/register"
    "/api/auth/token"})

(def ^:private csrf-exempt-prefixes
  "API path prefixes for CSRF exemption (parameterized routes)."
  ["/api/agents/"          ;; heartbeat: /api/agents/:id/heartbeat
   "/api/builds/"          ;; agent events/results: /api/builds/:id/agent-events, /api/builds/:id/result
   "/auth/saml/"])         ;; SAML ACS POST from IdP

(defn- wrap-skip-csrf-for-api
  "Middleware that skips CSRF validation for API endpoints used by agents/webhooks.
   Uses exact matches and specific prefixes to avoid over-broad exemptions."
  [handler]
  (fn [req]
    (let [uri (or (:uri req) "")]
      (if (and (= :post (:request-method req))
               (or (contains? csrf-exempt-paths uri)
                   (some #(str/starts-with? uri %) csrf-exempt-prefixes)))
        ;; Temporarily mark as GET so anti-forgery skips it, then restore
        (handler (assoc req :request-method :get
                            :original-method :post))
        (handler req)))))

(defn- wrap-restore-method
  "Restore the original method after CSRF check for webhook requests."
  [handler]
  (fn [req]
    (if (:original-method req)
      (handler (assoc req :request-method (:original-method req)))
      (handler req))))

(defn- metrics-route
  "Build the metrics route if metrics are enabled, otherwise nil.
   Uses :metrics :path from config (default \"/metrics\")."
  [system]
  (when-let [registry (:metrics system)]
    (let [path (get-in (:config system) [:metrics :path] "/metrics")]
      [path {:get {:handler (if (get-in (:config system) [:metrics :auth-required])
                              (auth/wrap-require-role :viewer (metrics/metrics-handler registry))
                              (metrics/metrics-handler registry))}}])))

(defn app-routes
  "Build the Ring handler with all routes."
  [system]
  (ring/ring-handler
    (ring/router
      (filterv some?
      [;; Public routes (no auth required)
       ["/login" {:get {:handler (h/login-page system)}
                  :post {:handler (h/login-submit system)}}]
       ["/logout" {:post {:handler (h/logout-submit system)}}]
       ;; OIDC routes (public — handle IdP redirects)
       ["/auth/oidc"
        ["/login" {:get {:handler (oidc/oidc-login-handler system)}}]
        ["/callback" {:get {:handler (oidc/oidc-callback-handler system)}}]]
       ;; SAML routes (public — handle IdP redirects)
       ["/auth/saml"
        ["/login" {:get {:handler (saml/saml-login-handler system)}}]
        ["/acs" {:post {:handler (saml/saml-acs-handler system)}}]
        ["/metadata" {:get {:handler (saml/saml-metadata-handler system)}}]]
       ;; MFA challenge routes (public — pre-auth)
       ["/auth/mfa"
        ["/challenge" {:get {:handler (h/mfa-challenge-page system)}
                       :post {:handler (h/mfa-challenge-submit system)}}]
        ["/recovery" {:get {:handler (h/mfa-recovery-page system)}
                      :post {:handler (h/mfa-recovery-submit system)}}]]
       ["/health" {:get {:handler (h/health-check system)}}]
       ["/ready" {:get {:handler (h/readiness-check system)}}]
       ["/startup" {:get {:handler (h/startup-check system)}}]
       ;; Prometheus metrics endpoint (only when metrics enabled)
       (metrics-route system)

       ;; Viewer+ routes (any authenticated user)
       ["/" {:get {:handler (h/dashboard-page system)}}]
       ["/jobs"
        ["" {:get {:handler (h/jobs-list-page system)}}]
        ["/:name" {:get {:handler (h/job-detail-page system)}}]
        ["/:name/pipeline" {:get {:handler (h/pipeline-detail-page system)}}]
        ;; Developer+ actions
        ["/:name/trigger" {:post {:handler (auth/wrap-require-role :developer (h/trigger-build system))}}]
        ["/:name/trigger-form" {:get {:handler (auth/wrap-require-role :developer (h/trigger-form system))}}]
        ["/:name/secrets" {:post {:handler (auth/wrap-require-role :developer (h/create-secret system))}}]
        ["/:name/secrets/:key" {:post {:handler (auth/wrap-require-role :developer (h/delete-secret system))}}]]
       ["/builds"
        ["/:id" {:get {:handler (h/build-detail-page system)}}]
        ["/:id/log" {:get {:handler (h/build-log-page system)}}]
        ;; Developer+ actions
        ["/:id/cancel" {:post {:handler (auth/wrap-require-role :developer (h/cancel-build system))}}]
        ["/:id/retry" {:post {:handler (auth/wrap-require-role :developer (h/retry-build system))}}]
        ["/:id/artifacts/:filename" {:get {:handler (h/download-artifact system)}}]
        ["/:id/artifacts/:filename/verify" {:get {:handler (h/verify-artifact-handler system)}}]]
       ;; Build comparison (viewer+)
       ["/compare" {:get {:handler (h/build-compare-page system)}}]
       ["/agents"
        ["" {:get {:handler (h/agents-page system)}}]]
       ;; Approval gates (developer+)
       ["/approvals"
        ["" {:get {:handler (auth/wrap-require-role :developer (h/approvals-page system))}}]
        ["/:id/approve" {:post {:handler (auth/wrap-require-role :developer (h/approve-gate-handler system))}}]
        ["/:id/reject" {:post {:handler (auth/wrap-require-role :developer (h/reject-gate-handler system))}}]]
       ;; Organization switching
       ["/orgs"
        ["/:slug/switch" {:post {:handler (h/switch-org-handler system)}}]]
       ;; Settings routes (any authenticated user)
       ["/settings"
        ["/tokens" {:get {:handler (auth/wrap-require-role :viewer (h/tokens-page system))}
                    :post {:handler (auth/wrap-require-role :viewer (h/generate-token-handler system))}}]
        ["/tokens/:id/revoke" {:post {:handler (auth/wrap-require-role :viewer (h/revoke-token-handler system))}}]
        ["/mfa" {:get {:handler (auth/wrap-require-role :viewer (h/mfa-settings-page system))}}]
        ["/mfa/setup" {:post {:handler (auth/wrap-require-role :viewer (h/mfa-setup-submit system))}}]
        ["/mfa/confirm" {:post {:handler (auth/wrap-require-role :viewer (h/mfa-confirm-submit system))}}]
        ["/mfa/disable" {:post {:handler (auth/wrap-require-role :viewer (h/mfa-disable-submit system))}}]]
       ;; Admin-only routes
       ["/admin"
        ["" {:get {:handler (auth/wrap-require-role :admin (h/admin-page system))}}]
        ["/cleanup" {:post {:handler (auth/wrap-require-role :admin (h/admin-cleanup system))}}]
        ["/retention" {:post {:handler (auth/wrap-require-role :admin (h/admin-retention system))}}]
        ["/backup" {:post {:handler (auth/wrap-require-role :admin (h/admin-backup system))}}]
        ["/audit" {:get {:handler (auth/wrap-require-role :admin (h/audit-page system))}}]
        ["/audit/export" {:get {:handler (auth/wrap-require-role :admin (h/audit-export-handler system))}}]
        ["/webhooks" {:get {:handler (auth/wrap-require-role :admin (h/webhooks-page system))}}]
        ["/users" {:get {:handler (auth/wrap-require-role :admin (h/users-page system))}
                   :post {:handler (auth/wrap-require-role :admin (h/create-user-handler system))}}]
        ["/users/:id" {:post {:handler (auth/wrap-require-role :admin (h/update-user-handler system))}}]
        ["/users/:id/password" {:post {:handler (auth/wrap-require-role :admin (h/reset-password-handler system))}}]
        ["/users/:id/toggle" {:post {:handler (auth/wrap-require-role :admin (h/toggle-user-handler system))}}]
        ["/users/:id/unlock" {:post {:handler (auth/wrap-require-role :admin (h/unlock-user-handler system))}}]
        ["/templates"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/templates-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/create-template-handler system))}}]
         ["/new" {:get {:handler (auth/wrap-require-role :admin (h/template-new-page system))}}]
         ["/:name/edit" {:get {:handler (auth/wrap-require-role :admin (h/template-edit-page system))}
                         :post {:handler (auth/wrap-require-role :admin (h/update-template-handler system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-template-handler system))}}]]
        ["/compliance"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/compliance-page system))}}]
         ["/generate" {:post {:handler (auth/wrap-require-role :admin (h/generate-compliance-report system))}}]
         ["/verify-chain" {:post {:handler (auth/wrap-require-role :admin (h/hash-chain-verify-handler system))}}]
         ["/runs/:id" {:get {:handler (auth/wrap-require-role :admin (h/compliance-run-page system))}}]
         ["/runs/:id/export" {:get {:handler (auth/wrap-require-role :admin (h/compliance-run-export system))}}]]
        ["/policies"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/policies-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/create-policy-handler system))}}]
         ["/new" {:get {:handler (auth/wrap-require-role :admin (h/policy-new-page system))}}]
         ["/evaluations" {:get {:handler (auth/wrap-require-role :admin (h/policy-evaluations-page system))}}]
         ["/:id/edit" {:get {:handler (auth/wrap-require-role :admin (h/policy-edit-page system))}
                       :post {:handler (auth/wrap-require-role :admin (h/update-policy-handler system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-policy-handler system))}}]
         ["/:id/toggle" {:post {:handler (auth/wrap-require-role :admin (h/toggle-policy-handler system))}}]]
        ["/plugins/policies"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/plugin-policies-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/set-plugin-policy-handler system))}}]
         ["/:name/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-plugin-policy-handler system))}}]]
        ["/docker/policies"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/docker-policies-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/create-docker-policy-handler system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-docker-policy-handler system))}}]]
        ["/traces"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/traces-page system))}}]
         ["/:trace-id" {:get {:handler (auth/wrap-require-role :admin (h/trace-detail-page system))}}]]
        ["/costs" {:get {:handler (auth/wrap-require-role :admin (h/cost-page system))}}]
        ;; Phase 6: Cron schedules
        ["/cron" {:get {:handler (auth/wrap-require-role :admin (h/cron-schedules-page system))}}]
        ;; Phase 6: Webhook replay
        ["/webhook-replay" {:get {:handler (auth/wrap-require-role :admin (h/webhook-replay-page system))}}]
        ;; Phase 7: Supply Chain Security
        ["/supply-chain"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/supply-chain-page system))}}]
         ["/opa" {:get {:handler (auth/wrap-require-role :admin (h/opa-policies-page system))}}]
         ["/licenses" {:get {:handler (auth/wrap-require-role :admin (h/license-policies-page system))}}]
         ["/builds/:build-id" {:get {:handler (auth/wrap-require-role :admin (h/supply-chain-build-page system))}}]]
        ;; Phase 7: Regulatory readiness
        ["/regulatory" {:get {:handler (auth/wrap-require-role :admin (h/regulatory-page system))}}]
        ;; Phase 8: Permissions
        ["/permissions"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/permissions-page system))}}]
         ["/grant" {:post {:handler (auth/wrap-require-role :admin (h/grant-permission-handler system))}}]
         ["/revoke/:id" {:post {:handler (auth/wrap-require-role :admin (h/revoke-permission-handler system))}}]
         ["/groups"
          ["" {:get {:handler (auth/wrap-require-role :admin (h/permission-groups-page system))}
               :post {:handler (auth/wrap-require-role :admin (h/create-permission-group-handler system))}}]
          ["/:id" {:get {:handler (auth/wrap-require-role :admin (h/permission-group-detail-page system))}}]
          ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-permission-group-handler system))}}]
          ["/:id/entries" {:post {:handler (auth/wrap-require-role :admin (h/add-group-entry-handler system))}}]
          ["/:id/entries/:entry-id/remove" {:post {:handler (auth/wrap-require-role :admin (h/remove-group-entry-handler system))}}]
          ["/:id/members" {:post {:handler (auth/wrap-require-role :admin (h/add-group-member-handler system))}}]
          ["/:id/members/:uid/remove" {:post {:handler (auth/wrap-require-role :admin (h/remove-group-member-handler system))}}]]]
        ;; Phase 8: Shared Resources
        ["/shared-resources"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/shared-resources-page system))}}]
         ["/grant" {:post {:handler (auth/wrap-require-role :admin (h/create-shared-grant-handler system))}}]
         ["/revoke/:id" {:post {:handler (auth/wrap-require-role :admin (h/revoke-shared-grant-handler system))}}]]
        ;; Phase 8: Secret Rotation
        ["/rotation"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/rotation-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/create-rotation-policy-handler system))}}]
         ["/delete/:id" {:post {:handler (auth/wrap-require-role :admin (h/delete-rotation-policy-handler system))}}]
         ["/toggle/:id" {:post {:handler (auth/wrap-require-role :admin (h/toggle-rotation-policy-handler system))}}]]
        ;; Phase 9: Pipeline Linter (developer+)
        ["/linter" {:get {:handler (auth/wrap-require-role :developer (h/linter-page system))}}]
        ["/linter/check" {:post {:handler (auth/wrap-require-role :developer (h/linter-check-handler system))}}]
        ;; Phase 11: Environment management (admin)
        ["/environments"
         ["" {:get {:handler (auth/wrap-require-role :admin (h/environments-page system))}
              :post {:handler (auth/wrap-require-role :admin (h/create-environment-handler system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :admin (h/environment-detail-page system))}
                  :post {:handler (auth/wrap-require-role :admin (h/update-environment-handler system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-environment-handler system))}}]
         ["/:id/lock" {:post {:handler (auth/wrap-require-role :admin (h/lock-environment-handler system))}}]
         ["/:id/unlock" {:post {:handler (auth/wrap-require-role :admin (h/unlock-environment-handler system))}}]]]
       ;; Phase 11: Deployment routes (developer+)
       ["/deploy"
        ["" {:get {:handler (auth/wrap-require-role :developer (h/deploy-dashboard-page system))}}]
        ["/releases"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/releases-page system))}
              :post {:handler (auth/wrap-require-role :developer (h/create-release-handler system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :developer (h/release-detail-page system))}}]
         ["/:id/publish" {:post {:handler (auth/wrap-require-role :developer (h/publish-release-handler system))}}]
         ["/:id/deprecate" {:post {:handler (auth/wrap-require-role :developer (h/deprecate-release-handler system))}}]]
        ["/promotions"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/promotions-page system))}
              :post {:handler (auth/wrap-require-role :developer (h/create-promotion-handler system))}}]
         ["/:id/approve" {:post {:handler (auth/wrap-require-role :developer (h/approve-promotion-handler system))}}]
         ["/:id/reject" {:post {:handler (auth/wrap-require-role :developer (h/reject-promotion-handler system))}}]]
        ["/strategies"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/strategies-page system))}
              :post {:handler (auth/wrap-require-role :developer (h/create-strategy-handler system))}}]
         ["/seed" {:post {:handler (auth/wrap-require-role :admin (h/seed-strategies-handler system))}}]]
        ["/deployments"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/deployments-page system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :developer (h/deployment-detail-page system))}}]
         ["/:id/execute" {:post {:handler (auth/wrap-require-role :developer (h/execute-deployment-handler system))}}]
         ["/:id/cancel" {:post {:handler (auth/wrap-require-role :developer (h/cancel-deployment-handler system))}}]
         ["/:id/rollback" {:post {:handler (auth/wrap-require-role :developer (h/rollback-deployment-handler system))}}]]]
       ;; Phase 12: IaC routes (developer+)
       ["/iac"
        ["" {:get {:handler (auth/wrap-require-role :developer (h/iac-dashboard-page system))}}]
        ["/projects"
         ["" {:post {:handler (auth/wrap-require-role :developer (h/iac-create-project-handler system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :developer (h/iac-project-detail-page system))}
                  :post {:handler (auth/wrap-require-role :developer (h/iac-update-project-handler system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :developer (h/iac-delete-project-handler system))}}]
         ["/:id/execute" {:post {:handler (auth/wrap-require-role :developer (h/iac-execute-handler system))}}]]
        ["/plans"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/iac-plans-page system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :developer (h/iac-plan-detail-page system))}}]
         ["/:id/approve" {:post {:handler (auth/wrap-require-role :developer (h/iac-approve-plan-handler system))}}]
         ["/:id/reject" {:post {:handler (auth/wrap-require-role :developer (h/iac-reject-plan-handler system))}}]]
        ["/states"
         ["" {:get {:handler (auth/wrap-require-role :developer (h/iac-states-page system))}}]
         ["/:id" {:get {:handler (auth/wrap-require-role :developer (h/iac-state-detail-page system))}}]
         ["/:id/unlock" {:post {:handler (auth/wrap-require-role :developer (h/iac-force-unlock-handler system))}}]]]
       ;; Analytics (viewer+)
       ["/analytics" {:get {:handler (auth/wrap-require-role :viewer (h/analytics-page system))}}]
       ["/analytics/flaky-tests" {:get {:handler (auth/wrap-require-role :viewer (h/flaky-tests-page system))}}]
       ;; Log Search (viewer+)
       ["/search/logs" {:get {:handler (h/log-search-page system)}
                        :post {:handler (h/log-search-results-handler system)}}]
       ["/api"
        ["/alerts" {:get {:handler (alerts/alerts-handler system)}}]
        ["/alerts/fragment" {:get {:handler (alerts/alerts-fragment-handler system)}}]
        ["/auth/token" {:post {:handler (h/api-generate-token system)}}]
        ["/builds/:id/events" {:get {:handler (h/build-events-sse system)}}]
        ["/builds/:id/events/replay" {:get {:handler (auth/wrap-require-role :viewer (h/build-events-handler system))}}]
        ;; Agent communication — developer+
        ["/builds/:id/agent-events" {:post {:handler (master-api/ingest-event-handler system)}}]
        ["/builds/:id/result" {:post {:handler (master-api/ingest-result-handler system)}}]
        ["/builds/:id/artifacts" {:post {:handler (artifact-transfer/artifact-upload-handler system)}}]
        ["/agents"
         ["" {:get {:handler (master-api/list-agents-handler system)}}]
         ["/register" {:post {:handler (auth/wrap-require-role :admin (master-api/register-agent-handler system))}}]
         ["/:id/heartbeat" {:post {:handler (master-api/heartbeat-handler system)}}]]
        ["/webhook" {:post {:handler (webhook/webhook-handler system build-runner/build-executor)}}]
        ["/approvals/pending" {:get {:handler (auth/wrap-require-role :developer (h/api-pending-approvals system))}}]
        ["/policies/evaluate" {:post {:handler (auth/wrap-require-role :admin (h/api-evaluate-policies system))}}]
        ["/traces/:trace-id/otlp" {:get {:handler (auth/wrap-require-role :viewer (h/trace-otlp-export system))}}]
        ["/analytics/trends" {:get {:handler (auth/wrap-require-role :viewer (h/api-analytics-trends system))}}]
        ["/analytics/stages" {:get {:handler (auth/wrap-require-role :viewer (h/api-analytics-stages system))}}]
        ["/events/global" {:get {:handler (h/global-events-sse system)}}]
        ["/costs/summary" {:get {:handler (auth/wrap-require-role :viewer (h/api-cost-summary system))}}]
        ["/analytics/flaky-tests" {:get {:handler (auth/wrap-require-role :viewer (h/api-flaky-tests system))}}]
        ;; Phase 6: Cron scheduling API
        ["/cron"
         ["" {:post {:handler (auth/wrap-require-role :admin (h/api-create-cron-schedule system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/api-delete-cron-schedule system))}}]]
        ;; Phase 6: Build dependencies API
        ["/jobs/:job-id/dependencies" {:post {:handler (auth/wrap-require-role :developer (h/api-create-dependency system))}}]
        ["/jobs/:job-id/checks"
         ["" {:post {:handler (auth/wrap-require-role :developer (h/api-create-pr-check system))}}]
         ["/:check-id/delete" {:post {:handler (auth/wrap-require-role :developer (h/api-delete-pr-check system))}}]]
        ;; Phase 6: Webhook replay API
        ["/webhooks/:id/replay" {:post {:handler (auth/wrap-require-role :admin (h/api-replay-webhook system))}}]
        ;; Phase 6: Dependencies delete API
        ["/dependencies/:id/delete" {:post {:handler (auth/wrap-require-role :developer (h/api-delete-dependency system))}}]
        ;; Phase 7: Supply Chain API (build-scoped)
        ["/supply-chain/builds/:build-id"
         ["/provenance" {:get {:handler (auth/wrap-require-role :viewer (h/api-provenance system))}}]
         ["/sbom/:format" {:get {:handler (auth/wrap-require-role :viewer (h/api-sbom system))}}]
         ["/scans" {:get {:handler (auth/wrap-require-role :viewer (h/api-build-scans system))}}]
         ["/licenses" {:get {:handler (auth/wrap-require-role :viewer (h/api-build-licenses system))}}]
         ["/verify" {:post {:handler (auth/wrap-require-role :admin (h/api-verify-signatures system))}}]]
        ;; Phase 7: Supply Chain management API
        ["/supply-chain/opa"
         ["" {:post {:handler (auth/wrap-require-role :admin (h/api-create-opa-policy system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/api-delete-opa-policy system))}}]]
        ["/supply-chain/licenses/policy"
         ["" {:post {:handler (auth/wrap-require-role :admin (h/api-create-license-policy system))}}]
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/api-delete-license-policy system))}}]]
        ;; Phase 7: Regulatory API
        ["/regulatory"
         ["/assess" {:post {:handler (auth/wrap-require-role :admin (h/api-regulatory-assess system))}}]
         ["/frameworks/:framework" {:get {:handler (auth/wrap-require-role :viewer (h/api-regulatory-framework system))}}]]]]))
    (ring/create-default-handler
      {:not-found (constantly {:status 404
                               :headers {"Content-Type" "text/html"}
                               :body "<h1>404 - Page Not Found</h1>"})})
    {:middleware [[rate-limit/wrap-rate-limit system]
                  [metrics-mw/wrap-http-metrics (:metrics system)]
                  [wrap-cors system]
                  [wrap-security-headers system]
                  [wrap-skip-csrf-for-api]
                  [wrap-defaults
                   (-> site-defaults
                       (assoc-in [:security :anti-forgery]
                                 {:read-token (fn [req]
                                                (get-in req [:headers "x-csrf-token"]))})
                       (assoc-in [:session :cookie-attrs :same-site] :lax))]
                  [wrap-restore-method]
                  [auth/wrap-auth system]
                  [auth/wrap-org-context system]
                  [audit/wrap-audit (:audit-writer system)]]}))
