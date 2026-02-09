(ns chengis.web.routes
  (:require [chengis.web.handlers :as h]
            [chengis.web.auth :as auth]
            [chengis.web.audit :as audit]
            [chengis.web.webhook :as webhook]
            [chengis.web.oidc :as oidc]
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
   "/api/builds/"])        ;; agent events/results: /api/builds/:id/agent-events, /api/builds/:id/result

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
       ["/health" {:get {:handler (h/health-check system)}}]
       ["/ready" {:get {:handler (h/readiness-check system)}}]
       ;; Prometheus metrics endpoint (only when metrics enabled)
       (metrics-route system)

       ;; Viewer+ routes (any authenticated user)
       ["/" {:get {:handler (h/dashboard-page system)}}]
       ["/jobs"
        ["" {:get {:handler (h/jobs-list-page system)}}]
        ["/:name" {:get {:handler (h/job-detail-page system)}}]
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
        ["/:id/artifacts/:filename" {:get {:handler (h/download-artifact system)}}]]
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
        ["/tokens/:id/revoke" {:post {:handler (auth/wrap-require-role :viewer (h/revoke-token-handler system))}}]]
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
         ["/:id/delete" {:post {:handler (auth/wrap-require-role :admin (h/delete-template-handler system))}}]]]
       ["/api"
        ["/alerts" {:get {:handler (alerts/alerts-handler system)}}]
        ["/alerts/fragment" {:get {:handler (alerts/alerts-fragment-handler system)}}]
        ["/auth/token" {:post {:handler (h/api-generate-token system)}}]
        ["/builds/:id/events" {:get {:handler (h/build-events-sse system)}}]
        ;; Agent communication — developer+
        ["/builds/:id/agent-events" {:post {:handler (master-api/ingest-event-handler system)}}]
        ["/builds/:id/result" {:post {:handler (master-api/ingest-result-handler system)}}]
        ["/builds/:id/artifacts" {:post {:handler (artifact-transfer/artifact-upload-handler system)}}]
        ["/agents"
         ["" {:get {:handler (master-api/list-agents-handler system)}}]
         ["/register" {:post {:handler (auth/wrap-require-role :admin (master-api/register-agent-handler system))}}]
         ["/:id/heartbeat" {:post {:handler (master-api/heartbeat-handler system)}}]]
        ["/webhook" {:post {:handler (webhook/webhook-handler system build-runner/build-executor)}}]
        ["/approvals/pending" {:get {:handler (auth/wrap-require-role :developer (h/api-pending-approvals system))}}]]]))
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
