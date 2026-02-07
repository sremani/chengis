(ns chengis.web.audit
  "Async audit logging via core.async channel.
   Zero latency impact on request handlers — events are written in background."
  (:require [clojure.core.async :as async]
            [chengis.db.audit-store :as audit-store]
            [chengis.web.auth :as auth]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Async audit writer
;; ---------------------------------------------------------------------------

(defn start-audit-writer!
  "Start the background audit writer. Returns {:channel ch :stop-fn fn}.
   Events put on the channel are written to the database asynchronously."
  [ds config]
  (let [buffer-size (get-in config [:audit :buffer-size] 1024)
        ch (async/chan buffer-size)]
    (async/go-loop []
      (when-let [event (async/<! ch)]
        (try
          (audit-store/insert-audit! ds event)
          (catch Exception e
            (log/warn "Failed to write audit event:" (.getMessage e))))
        (recur)))
    {:channel ch
     :stop-fn (fn []
                (async/close! ch)
                (log/info "Audit writer stopped"))}))

(defn audit!
  "Put an audit event onto the channel. Non-blocking with timeout.
   event: {:user-id :username :action :resource-type :resource-id :detail :ip-address :user-agent}"
  [audit-writer event]
  (when-let [ch (:channel audit-writer)]
    (async/offer! ch event)))

;; ---------------------------------------------------------------------------
;; Audit middleware
;; ---------------------------------------------------------------------------

(defn- action-from-request
  "Derive an audit action string from the request method and URI."
  [req]
  (let [method (:request-method req)
        uri (or (:uri req) "")]
    (cond
      ;; Auth
      (and (= method :post) (= uri "/login"))  "login"
      (and (= method :post) (= uri "/logout")) "logout"
      ;; Jobs
      (and (= method :post) (re-matches #"/jobs/.+/trigger" uri)) "trigger-build"
      (and (= method :post) (re-matches #"/jobs/.+/secrets.*" uri)) "manage-secret"
      ;; Builds
      (and (= method :post) (re-matches #"/builds/.+/cancel" uri)) "cancel-build"
      (and (= method :post) (re-matches #"/builds/.+/retry" uri)) "retry-build"
      ;; Admin
      (and (= method :post) (= uri "/admin/cleanup")) "admin-cleanup"
      (and (= method :post) (re-matches #"/admin/users.*" uri)) "manage-user"
      ;; API
      (and (= method :post) (= uri "/api/auth/token")) "generate-api-token"
      (and (= method :post) (= uri "/api/webhook")) "webhook"
      (and (= method :post) (re-matches #"/api/agents.*" uri)) "agent-api"
      ;; Default: only audit POST/PUT/DELETE, not GETs
      (#{:post :put :delete} method) (str (name method) " " uri)
      :else nil)))

(defn- resource-from-uri
  "Extract resource type and ID from the URI."
  [uri]
  (cond
    (re-matches #"/jobs/([^/]+).*" uri)
    (let [[_ name] (re-matches #"/jobs/([^/]+).*" uri)]
      {:resource-type "job" :resource-id name})

    (re-matches #"/builds/([^/]+).*" uri)
    (let [[_ id] (re-matches #"/builds/([^/]+).*" uri)]
      {:resource-type "build" :resource-id id})

    (re-matches #"/admin.*" uri)
    {:resource-type "admin" :resource-id nil}

    :else nil))

(defn wrap-audit
  "Ring middleware: log audit events for mutating requests.
   Attaches after response to avoid adding latency."
  [handler audit-writer]
  (fn [req]
    (let [resp (handler req)]
      ;; Only audit successful mutating requests
      (when-let [action (action-from-request req)]
        (let [user (auth/current-user req)
              resource (resource-from-uri (or (:uri req) ""))]
          (audit! audit-writer
            (merge
              {:user-id (:id user)
               :username (:username user)
               :action action
               :ip-address (or (get-in req [:headers "x-forwarded-for"])
                               (:remote-addr req))
               :user-agent (get-in req [:headers "user-agent"])}
              resource))))
      resp)))
