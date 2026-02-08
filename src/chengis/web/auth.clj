(ns chengis.web.auth
  "Authentication and authorization middleware.
   Config-gated: when :auth :enabled is false, all requests are treated as admin."
  (:require [chengis.db.user-store :as user-store]
            [chengis.web.account-lockout :as lockout]
            [chengis.metrics :as metrics]
            [next.jdbc :as jdbc]
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
  "Generate a JWT token for a user. Includes jti (JWT ID) for blacklist support
   and session-version for password-reset invalidation."
  [user config]
  (let [secret (resolve-jwt-secret config)
        expiry-hours (get-in config [:auth :jwt-expiry-hours] 24)
        now (Instant/now)
        exp (.plusSeconds now (* expiry-hours 3600))
        jti (str (java.util.UUID/randomUUID))]
    (jwt/sign {:user-id (:id user)
               :username (:username user)
               :role (:role user)
               :session-version (or (:session-version user) 0)
               :jti jti
               :iat (.getEpochSecond now)
               :exp (.getEpochSecond exp)}
              secret)))

;; ---------------------------------------------------------------------------
;; JWT blacklist (for early token invalidation)
;; ---------------------------------------------------------------------------

(defn blacklist-jwt!
  "Add a JWT to the blacklist. Used for password change / forced logout."
  [ds jti user-id expires-at reason]
  (try
    (jdbc/execute-one! ds
      ["INSERT OR IGNORE INTO jwt_blacklist (jti, user_id, expires_at, reason) VALUES (?, ?, ?, ?)"
       jti user-id expires-at reason])
    (catch Exception e
      (log/warn "Failed to blacklist JWT:" (.getMessage e)))))

(defn- jti-blacklisted?
  "Check if a JWT ID is in the blacklist.
   Returns false if ds is nil, jti is nil, or the table doesn't exist yet."
  [ds jti]
  (when (and ds jti)
    (try
      (some?
        (jdbc/execute-one! ds
          ["SELECT jti FROM jwt_blacklist WHERE jti = ?" jti]))
      (catch Exception _
        false))))

(defn cleanup-expired-blacklist!
  "Remove expired entries from the JWT blacklist."
  [ds]
  (let [result (jdbc/execute-one! ds
                 ["DELETE FROM jwt_blacklist WHERE expires_at < datetime('now')"])]
    (:next.jdbc/update-count result 0)))

(defn verify-jwt
  "Verify and decode a JWT token. Returns claims map or nil.
   Checks expiry and JWT blacklist (when ds is provided via 2-arity call)."
  ([token config]
   (verify-jwt token config nil))
  ([token config ds]
   (try
     (let [secret (resolve-jwt-secret config)
           claims (jwt/unsign token secret)
           now (.getEpochSecond (Instant/now))]
       (when (and (> (:exp claims) now)
                  (not (jti-blacklisted? ds (:jti claims))))
         claims))
     (catch Exception _
       nil))))

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
   Metrics endpoint is conditionally public based on :metrics :auth-required config.
   Uses :metrics :path from config (default \"/metrics\")."
  [uri config]
  (or (contains? public-paths uri)
      (some #(str/starts-with? uri %) public-prefixes)
      ;; Metrics path is public unless :metrics :auth-required is true
      (and (= uri (get-in config [:metrics :path] "/metrics"))
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

(def ^:private distributed-api-exempt-suffixes
  "Suffixes that should NOT be exempted from global auth.
   /events (SSE) endpoints are read endpoints for authenticated users."
  ["/events"])

(defn- distributed-api-path?
  "Check if the request path is a distributed agent endpoint that should
   bypass global auth (handled by handler-level check-auth instead).
   Returns true for agent communication paths, false for registration
   (which uses RBAC), SSE /events endpoints (which need user auth),
   and other API paths."
  [uri]
  (and (some #(str/starts-with? uri %) distributed-api-prefixes)
       (not (contains? distributed-api-exempt-exact uri))
       (not (some #(str/ends-with? uri %) distributed-api-exempt-suffixes))))

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
  "Try to authenticate via session cookie. Returns user map or nil.
   Validates active status and session_version against the DB to enforce
   deactivation, password-reset, and role-change invalidation.
   Always derives role from DB to prevent stale privilege retention."
  [req ds]
  (when-let [session-user (get-in req [:session :user])]
    (if-let [user-id (:id session-user)]
      ;; Verify user is active and session version matches the DB
      (if-let [db-user (user-store/get-user ds user-id)]
        (cond
          ;; User deactivated — reject
          (and (some? (:active db-user)) (zero? (:active db-user)))
          (do (log/info "Session rejected — user deactivated:" (:username session-user))
              nil)

          ;; Session version mismatch — reject
          (not= (or (:session-version session-user) 0)
                (or (:session-version db-user) 0))
          (do (log/info "Session invalidated for" (:username session-user)
                        "— session version mismatch")
              nil)

          ;; All checks pass — derive role from DB (not stale session)
          :else
          (assoc session-user :role (keyword (:role db-user))))
        ;; User deleted from DB
        nil)
      ;; No user-id in session (legacy/anonymous) — pass through
      session-user)))

(defn- try-jwt-auth
  "Try to authenticate via JWT bearer token. Returns user map or nil.
   Passes ds for JWT blacklist checking, session-version validation,
   and active-status verification."
  [token config ds]
  (when-let [claims (verify-jwt token config ds)]
    ;; Validate session-version against DB to enforce password-change invalidation
    (let [jwt-version (or (:session-version claims) 0)]
      (if-let [db-user (when (:user-id claims) (user-store/get-user ds (:user-id claims)))]
        (cond
          ;; User deactivated — reject
          (and (some? (:active db-user)) (zero? (:active db-user)))
          (do (log/info "JWT rejected — user deactivated:" (:username claims))
              nil)

          ;; Session version mismatch — reject
          (not= jwt-version (or (:session-version db-user) 0))
          (do (log/info "JWT invalidated for" (:username claims)
                        "— session version mismatch")
              nil)

          ;; All checks pass — derive role from DB (not stale JWT claims)
          :else
          {:id (:user-id claims)
           :username (:username claims)
           :role (keyword (:role db-user))})
        ;; User not found — reject JWT
        (do (log/info "JWT rejected — user not found:" (:username claims))
            nil)))))

(defn- try-api-token-auth
  "Try to authenticate via API token. Returns user map or nil.
   Rejects tokens belonging to deactivated users."
  [ds token]
  (when-let [user (user-store/find-api-token-user ds token)]
    (if (and (some? (:active user)) (zero? (:active user)))
      (do (log/info "API token rejected — user deactivated:" (:username user))
          nil)
      {:id (:id user)
       :username (:username user)
       :role (keyword (:role user))})))

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
      (let [uri (or (:uri req) "")
            session-user (when auth-enabled? (try-session-auth req ds))
            bearer-token (when (and auth-enabled? (not session-user))
                           (extract-bearer-token req))]
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
          session-user
          (handler (attach-user req session-user))

          ;; Bearer token present — try JWT, then API token
          bearer-token
          (if-let [user (or (try-jwt-auth bearer-token config ds)
                            (try-api-token-auth ds bearer-token))]
            (do
              (try (metrics/record-token-auth! registry :success) (catch Exception _))
              (handler (attach-user req user)))
            (do
              (try (metrics/record-token-auth! registry :failure) (catch Exception _))
              (unauthorized-response req "Invalid or expired token")))

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
   Integrates account lockout: checks lock status before password verification,
   records failed attempts, and resets on success.
   Optionally accepts a metrics registry and lockout config."
  ([ds username password]
   (login! ds username password nil))
  ([ds username password registry]
   (login! ds username password registry nil))
  ([ds username password registry lockout-config]
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

       ;; Account locked — check before password verification to avoid unnecessary bcrypt
       (when-let [lock-result (lockout/check-lockout user lockout-config)]
         lock-result)
       (do (log/warn "Login attempt for locked account:" username)
           (metrics/record-login! registry :failure)
           {:success false :error (:error (lockout/check-lockout user lockout-config))})

       ;; Wrong password — record failed attempt for lockout tracking
       (not (user-store/check-password password (:password-hash user)))
       (do (log/warn "Failed login attempt — wrong password for:" username)
           (lockout/record-failed-attempt! ds user lockout-config registry)
           (metrics/record-login! registry :failure)
           {:success false :error generic-error})

       ;; Success — reset failed attempts counter
       :else
       (do (log/info "User logged in:" username)
           (lockout/reset-failed-attempts! ds (:id user) lockout-config)
           (metrics/record-login! registry :success)
           {:success true
            :user {:id (:id user)
                   :username (:username user)
                   :role (keyword (:role user))
                   :session-version (or (:session-version user) 0)}})))))
