(ns chengis.web.auth
  "Authentication and authorization middleware.
   Config-gated: when :auth :enabled is false, all requests are treated as admin."
  (:require [chengis.db.user-store :as user-store]
            [chengis.metrics :as metrics]
            [buddy.sign.jwt :as jwt]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.security SecureRandom]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Input validation
;; ---------------------------------------------------------------------------

(def ^:private valid-roles
  "The set of valid role values."
  #{"admin" "developer" "viewer"})

(defn valid-role?
  "Check if a role string is one of the allowed roles."
  [role]
  (contains? valid-roles (str role)))

(defn valid-username?
  "Check if a username is valid: 2-64 alphanumeric chars, hyphens, underscores."
  [username]
  (boolean
    (and (string? username)
         (re-matches #"[a-zA-Z0-9_-]{2,64}" username))))

(def min-password-length
  "Minimum password length for user accounts."
  8)

(defn valid-password?
  "Check if a password meets minimum requirements (>= 8 chars)."
  [password]
  (and (string? password)
       (>= (count password) min-password-length)))

;; ---------------------------------------------------------------------------
;; Role hierarchy
;; ---------------------------------------------------------------------------

(def ^:private role-hierarchy
  "Role hierarchy: admin > developer > viewer."
  {:admin 3
   :developer 2
   :viewer 1})

(defn role-sufficient?
  "Check if the user's role is sufficient for the required role."
  [user-role required-role]
  (>= (get role-hierarchy (keyword user-role) 0)
      (get role-hierarchy (keyword required-role) 0)))

;; ---------------------------------------------------------------------------
;; JWT helpers
;; ---------------------------------------------------------------------------

(defn- generate-random-secret
  "Generate a cryptographically secure random string for JWT signing."
  []
  (let [bytes (byte-array 32)
        rng (SecureRandom.)]
    (.nextBytes rng bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

;; Auto-generated JWT secret for sessions without explicit configuration.
;; Regenerated on each JVM restart — tokens won't survive restarts.
(defonce ^:private auto-jwt-secret
  (delay
    (let [secret (generate-random-secret)]
      (log/warn "No :jwt-secret configured — using auto-generated secret (tokens won't survive restarts)")
      secret)))

(defn- resolve-jwt-secret
  "Resolve the JWT secret: use configured value, or auto-generate one."
  [config]
  (or (get-in config [:auth :jwt-secret])
      @auto-jwt-secret))

(defn generate-jwt
  "Generate a JWT token for a user."
  [user config]
  (let [secret (resolve-jwt-secret config)
        expiry-hours (get-in config [:auth :jwt-expiry-hours] 24)
        now (Instant/now)
        exp (.plusSeconds now (* expiry-hours 3600))]
    (jwt/sign {:user-id (:id user)
               :username (:username user)
               :role (:role user)
               :iat (.getEpochSecond now)
               :exp (.getEpochSecond exp)}
              secret)))

(defn verify-jwt
  "Verify and decode a JWT token. Returns claims map or nil."
  [token config]
  (try
    (let [secret (resolve-jwt-secret config)
          claims (jwt/unsign token secret)
          now (.getEpochSecond (Instant/now))]
      (when (> (:exp claims) now)
        claims))
    (catch Exception _
      nil)))

;; ---------------------------------------------------------------------------
;; Extract user from request
;; ---------------------------------------------------------------------------

(defn current-user
  "Extract the current user from the request (set by wrap-auth)."
  [req]
  (:auth/user req))

(defn- extract-bearer-token
  "Extract Bearer token from Authorization header."
  [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? (str/lower-case auth-header) "bearer ")
      (str/trim (subs auth-header 7)))))

(defn- api-request?
  "Check if this is an API request (expects JSON, not HTML)."
  [req]
  (let [uri (or (:uri req) "")
        accept (get-in req [:headers "accept"] "")]
    (or (str/starts-with? uri "/api/")
        (str/includes? accept "application/json"))))

;; ---------------------------------------------------------------------------
;; Public (no-auth) paths
;; ---------------------------------------------------------------------------

(def ^:private public-paths
  "Paths that always bypass authentication."
  #{"/login" "/health" "/ready"})

(def ^:private public-prefixes
  "Path prefixes that don't require authentication."
  ["/health" "/ready"])

(defn- public-path?
  "Check if the request path is a public (no-auth) path.
   /metrics is conditionally public based on :metrics :auth-required config."
  [uri config]
  (or (contains? public-paths uri)
      (some #(str/starts-with? uri %) public-prefixes)
      ;; /metrics is public unless :metrics :auth-required is true
      (and (= uri "/metrics")
           (not (get-in config [:metrics :auth-required] false)))))

;; ---------------------------------------------------------------------------
;; Distributed agent endpoint exemption
;; ---------------------------------------------------------------------------

(def ^:private distributed-api-prefixes
  "API path prefixes for distributed agent-to-master communication.
   These endpoints use their own auth (shared secret) via check-auth in
   master_api.clj and artifact_transfer.clj, not JWT/API-token auth."
  ["/api/agents/" "/api/builds/"])

(def ^:private distributed-api-exempt-exact
  "Exact distributed API paths that should NOT be exempted from global auth.
   These use RBAC (wrap-require-role) and need the global auth user."
  #{"/api/agents/register"})

(defn- distributed-api-path?
  "Check if the request path is a distributed agent endpoint that should
   bypass global auth (handled by handler-level check-auth instead).
   Returns true for agent communication paths, false for registration
   (which uses RBAC) and other API paths."
  [uri]
  (and (some #(str/starts-with? uri %) distributed-api-prefixes)
       (not (contains? distributed-api-exempt-exact uri))))

;; ---------------------------------------------------------------------------
;; Auth response helpers
;; ---------------------------------------------------------------------------

(defn- unauthorized-response
  "Return a 401 response for unauthenticated requests."
  [req message]
  (if (api-request? req)
    {:status 401
     :headers {"Content-Type" "application/json"}
     :body (str "{\"error\":\"" message "\"}")}
    {:status 303
     :headers {"Location" "/login"}}))

(defn- forbidden-response
  "Return a 403 response for insufficient permissions."
  [req required-role]
  (if (api-request? req)
    {:status 403
     :headers {"Content-Type" "application/json"}
     :body (str "{\"error\":\"Insufficient permissions. Required role: " (name required-role) "\"}")}
    {:status 403
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body "<h1>403 Forbidden</h1><p>You don't have permission to access this page.</p>"}))

;; ---------------------------------------------------------------------------
;; Auth middleware — authenticate request via session, JWT, or API token
;; ---------------------------------------------------------------------------

(defn- attach-user
  "Attach a user map to the request under :auth/user."
  [req user]
  (assoc req :auth/user user))

(defn- try-session-auth
  "Try to authenticate via session cookie. Returns user map or nil."
  [req]
  (get-in req [:session :user]))

(defn- try-jwt-auth
  "Try to authenticate via JWT bearer token. Returns user map or nil."
  [token config]
  (when-let [claims (verify-jwt token config)]
    {:id (:user-id claims)
     :username (:username claims)
     :role (keyword (:role claims))}))

(defn- try-api-token-auth
  "Try to authenticate via API token. Returns user map or nil."
  [ds token]
  (when-let [user (user-store/find-api-token-user ds token)]
    {:id (:id user)
     :username (:username user)
     :role (keyword (:role user))}))

(defn wrap-auth
  "Ring middleware: authenticate the request.
   When auth is disabled: attach admin user to every request (backward compat).
   When auth is enabled: check session -> JWT -> API token -> reject.
   Distributed agent endpoints bypass global auth when distributed mode is on
   (they use handler-level check-auth with a shared secret instead).
   Uses a flat cond structure for readability."
  [handler system]
  (let [config (:config system)
        ds (:db system)
        registry (:metrics system)
        auth-enabled? (get-in config [:auth :enabled] false)
        distributed-enabled? (get-in config [:distributed :enabled] false)]
    (fn [req]
      (let [uri (or (:uri req) "")]
        (cond
          ;; Auth disabled — backward compatible: everyone is admin
          (not auth-enabled?)
          (handler (attach-user req {:username "anonymous" :role :admin :id nil}))

          ;; Public path — no auth needed (config-aware for /metrics)
          (public-path? uri config)
          (handler req)

          ;; Distributed agent endpoint — bypass global auth, let handler check-auth
          ;; validate the shared secret. Only when distributed mode is enabled.
          (and distributed-enabled? (distributed-api-path? uri))
          (handler req)

          ;; Session auth
          (try-session-auth req)
          (handler (attach-user req (try-session-auth req)))

          ;; Bearer token present — try JWT, then API token
          (extract-bearer-token req)
          (let [token (extract-bearer-token req)]
            (if-let [user (or (try-jwt-auth token config)
                              (try-api-token-auth ds token))]
              (do
                (try (metrics/record-token-auth! registry :success) (catch Exception _))
                (handler (attach-user req user)))
              (do
                (try (metrics/record-token-auth! registry :failure) (catch Exception _))
                (unauthorized-response req "Invalid or expired token"))))

          ;; No credentials at all
          :else
          (unauthorized-response req "Authentication required"))))))

;; ---------------------------------------------------------------------------
;; RBAC middleware
;; ---------------------------------------------------------------------------

(defn wrap-require-role
  "Middleware that checks if the current user has the required role.
   Must be applied AFTER wrap-auth."
  [required-role handler]
  (fn [req]
    (let [user (current-user req)]
      (if (and user (role-sufficient? (:role user) required-role))
        (handler req)
        (forbidden-response req required-role)))))

;; ---------------------------------------------------------------------------
;; Login/Logout helpers
;; ---------------------------------------------------------------------------

(defn login!
  "Authenticate a user with username/password.
   Returns {:success true :user user-map} or {:success false :error msg}.
   Uses consistent error messages to prevent user enumeration.
   Optionally accepts a metrics registry for recording login attempts."
  ([ds username password]
   (login! ds username password nil))
  ([ds username password registry]
   (let [generic-error "Invalid username or password"
         user (user-store/get-user-by-username ds username)]
     (cond
       ;; Unknown user — use same error message to prevent enumeration
       (nil? user)
       (do (log/warn "Failed login attempt — user not found:" username)
           (metrics/record-login! registry :failure)
           {:success false :error generic-error})

       ;; Deactivated account — safe to differentiate (user already knows their account)
       (zero? (:active user))
       (do (log/warn "Login attempt for deactivated account:" username)
           (metrics/record-login! registry :failure)
           {:success false :error "Account is deactivated"})

       ;; Wrong password
       (not (user-store/check-password password (:password-hash user)))
       (do (log/warn "Failed login attempt — wrong password for:" username)
           (metrics/record-login! registry :failure)
           {:success false :error generic-error})

       ;; Success
       :else
       (do (log/info "User logged in:" username)
           (metrics/record-login! registry :success)
           {:success true
            :user {:id (:id user)
                   :username (:username user)
                   :role (keyword (:role user))}})))))
