(ns chengis.web.routes
  (:require [chengis.web.handlers :as h]
            [chengis.web.webhook :as webhook]
            [chengis.engine.build-runner :as build-runner]
            [clojure.string :as str]
            [reitit.ring :as ring]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(defn- wrap-security-headers
  "Add security headers to every response."
  [handler]
  (fn [req]
    (let [resp (handler req)]
      (update resp :headers merge
        {"X-Content-Type-Options" "nosniff"
         "X-Frame-Options" "DENY"
         "Referrer-Policy" "strict-origin-when-cross-origin"}))))

(defn- wrap-skip-csrf-for-webhook
  "Middleware that skips CSRF validation for the webhook endpoint.
   Works by temporarily overriding the request method to GET for webhook paths,
   which Ring anti-forgery ignores."
  [handler]
  (fn [req]
    (if (and (= :post (:request-method req))
             (str/starts-with? (or (:uri req) "") "/api/webhook"))
      ;; Temporarily mark as GET so anti-forgery skips it, then restore
      (handler (assoc req :request-method :get
                          :original-method :post))
      (handler req))))

(defn- wrap-restore-method
  "Restore the original method after CSRF check for webhook requests."
  [handler]
  (fn [req]
    (if (:original-method req)
      (handler (assoc req :request-method (:original-method req)))
      (handler req))))

(defn app-routes
  "Build the Ring handler with all routes."
  [system]
  (ring/ring-handler
    (ring/router
      [["/" {:get {:handler (h/dashboard-page system)}}]
       ["/jobs"
        ["" {:get {:handler (h/jobs-list-page system)}}]
        ["/:name" {:get {:handler (h/job-detail-page system)}}]
        ["/:name/trigger" {:post {:handler (h/trigger-build system)}}]
        ["/:name/trigger-form" {:get {:handler (h/trigger-form system)}}]
        ["/:name/secrets" {:post {:handler (h/create-secret system)}}]
        ["/:name/secrets/:key" {:post {:handler (h/delete-secret system)}}]]
       ["/builds"
        ["/:id" {:get {:handler (h/build-detail-page system)}}]
        ["/:id/log" {:get {:handler (h/build-log-page system)}}]
        ["/:id/cancel" {:post {:handler (h/cancel-build system)}}]
        ["/:id/retry" {:post {:handler (h/retry-build system)}}]
        ["/:id/artifacts/:filename" {:get {:handler (h/download-artifact system)}}]]
       ["/admin"
        ["" {:get {:handler (h/admin-page system)}}]
        ["/cleanup" {:post {:handler (h/admin-cleanup system)}}]]
       ["/api"
        ["/builds/:id/events" {:get {:handler (h/build-events-sse system)}}]
        ["/webhook" {:post {:handler (webhook/webhook-handler system build-runner/build-executor)}}]]])
    (ring/create-default-handler
      {:not-found (constantly {:status 404
                               :headers {"Content-Type" "text/html"}
                               :body "<h1>404 - Page Not Found</h1>"})})
    {:middleware [[wrap-security-headers]
                  [wrap-skip-csrf-for-webhook]
                  [wrap-defaults
                   (-> site-defaults
                       (assoc-in [:security :anti-forgery]
                                 {:read-token (fn [req]
                                                (get-in req [:headers "x-csrf-token"]))}))]
                  [wrap-restore-method]]}))
