(ns chengis.web.routes
  (:require [chengis.web.handlers :as h]
            [chengis.web.auth :as auth]
            [chengis.web.audit :as audit]
            [chengis.web.webhook :as webhook]
            [chengis.metrics :as metrics]
            [chengis.web.alerts :as alerts]
            [chengis.web.metrics-middleware :as metrics-mw]
            [chengis.distributed.master-api :as master-api]
            [chengis.engine.build-runner :as build-runner]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defn- wrap-security-headers
  "Add security headers to every response, including HSTS when HTTPS is configured."
  [handler system]
  (let [hsts? (and (get-in (:config system) [:https :enabled] false)
                   (get-in (:config system) [:https :hsts] true))]
    (fn [req]
      (let [resp (handler req)]
        (when resp
          (update resp :headers merge
            (cond-> {"X-Content-Type-Options" "nosniff"
                     "X-Frame-Options" "DENY"
                     "Referrer-Policy" "strict-origin-when-cross-origin"
                     "X-XSS-Protection" "1; mode=block"}
              hsts? (assoc "Strict-Transport-Security" "max-age=31536000; includeSubDomains"))))))))

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
  "Build the /metrics route if metrics are enabled, otherwise nil."
  [system]
  (when-let [registry (:metrics system)]
    ["/metrics" {:get {:handler (if (get-in (:config system) [:metrics :auth-required])
                                  (auth/wrap-require-role :viewer (metrics/metrics-handler registry))
                                  (metrics/metrics-handler registry))}}]))

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
       ;; Admin-only routes
       ["/admin"
        ["" {:get {:handler (auth/wrap-require-role :admin (h/admin-page system))}}]
        ["/cleanup" {:post {:handler (auth/wrap-require-role :admin (h/admin-cleanup system))}}]
        ["/audit" {:get {:handler (auth/wrap-require-role :admin (h/audit-page system))}}]
        ["/users" {:get {:handler (auth/wrap-require-role :admin (h/users-page system))}
                   :post {:handler (auth/wrap-require-role :admin (h/create-user-handler system))}}]
        ["/users/:id" {:post {:handler (auth/wrap-require-role :admin (h/update-user-handler system))}}]
        ["/users/:id/password" {:post {:handler (auth/wrap-require-role :admin (h/reset-password-handler system))}}]
        ["/users/:id/toggle" {:post {:handler (auth/wrap-require-role :admin (h/toggle-user-handler system))}}]]
       ["/api"
        ["/alerts" {:get {:handler (alerts/alerts-handler system)}}]
        ["/alerts/fragment" {:get {:handler (alerts/alerts-fragment-handler system)}}]
        ["/auth/token" {:post {:handler (h/api-generate-token system)}}]
        ["/builds/:id/events" {:get {:handler (h/build-events-sse system)}}]
        ;; Agent communication — developer+
        ["/builds/:id/agent-events" {:post {:handler (master-api/ingest-event-handler system)}}]
        ["/builds/:id/result" {:post {:handler (master-api/ingest-result-handler system)}}]
        ["/agents"
         ["" {:get {:handler (master-api/list-agents-handler system)}}]
         ["/register" {:post {:handler (auth/wrap-require-role :admin (master-api/register-agent-handler system))}}]
         ["/:id/heartbeat" {:post {:handler (master-api/heartbeat-handler system)}}]]
        ["/webhook" {:post {:handler (webhook/webhook-handler system build-runner/build-executor)}}]]))
    (ring/create-default-handler
      {:not-found (constantly {:status 404
                               :headers {"Content-Type" "text/html"}
                               :body "<h1>404 - Page Not Found</h1>"})})
    {:middleware [[metrics-mw/wrap-http-metrics (:metrics system)]
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
                  [audit/wrap-audit (:audit-writer system)]]}))
