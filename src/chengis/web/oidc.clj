(ns chengis.web.oidc
  "OpenID Connect (OIDC) authentication integration.
   Supports authorization code flow with any OIDC-compliant identity provider
   (Keycloak, Auth0, Okta, Azure AD, Google Workspace, etc.).

   Configuration:
     :oidc {:enabled true
            :issuer-url \"https://idp.example.com/realms/chengis\"
            :client-id \"chengis\"
            :client-secret \"secret\"
            :scopes \"openid profile email\"
            :role-claim \"realm_access.roles\"
            :role-mapping {\"chengis-admin\" \"admin\"
                           \"chengis-dev\" \"developer\"}
            :default-role \"viewer\"
            :auto-create-users true}"
  (:require [chengis.db.user-store :as user-store]
            [chengis.util :as util]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [java.net URI URLEncoder]
           [java.security SecureRandom]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; OIDC Discovery
;; ---------------------------------------------------------------------------

(defonce ^:private oidc-config-cache
  (atom {:data nil :fetched-at 0}))

(def ^:private discovery-cache-ttl-ms
  "Cache OIDC discovery document for 1 hour."
  3600000)

(defn- http-get-json
  "Perform a simple HTTP GET and parse JSON response.
   Uses java.net.http.HttpClient (Java 11+) to avoid external HTTP dependencies."
  [url]
  (let [client (java.net.http.HttpClient/newHttpClient)
        request (-> (java.net.http.HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Accept" "application/json")
                    (.GET)
                    (.build))
        response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (when (= 200 (.statusCode response))
      (json/read-str (.body response) :key-fn keyword))))

(defn- http-post-form
  "Perform an HTTP POST with form-urlencoded body and parse JSON response."
  [url params]
  (let [body (->> params
                  (map (fn [[k v]]
                         (str (URLEncoder/encode (name k) "UTF-8")
                              "="
                              (URLEncoder/encode (str v) "UTF-8"))))
                  (str/join "&"))
        client (java.net.http.HttpClient/newHttpClient)
        request (-> (java.net.http.HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.header "Accept" "application/json")
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                    (.build))
        response (.send client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (when (< (.statusCode response) 400)
      (json/read-str (.body response) :key-fn keyword))))

(defn fetch-oidc-discovery
  "Fetch the OIDC discovery document from the issuer's well-known endpoint.
   Results are cached for 1 hour."
  [issuer-url]
  (let [now (System/currentTimeMillis)
        cached @oidc-config-cache]
    (if (and (:data cached)
             (< (- now (:fetched-at cached)) discovery-cache-ttl-ms))
      (:data cached)
      (try
        (let [discovery-url (str (str/trimr issuer-url "/")
                                 "/.well-known/openid-configuration")
              data (http-get-json discovery-url)]
          (when data
            (reset! oidc-config-cache {:data data :fetched-at now})
            (log/info "OIDC discovery loaded from" issuer-url)
            data))
        (catch Exception e
          (log/error "Failed to fetch OIDC discovery:" (.getMessage e))
          ;; Return stale cache if available
          (:data cached))))))

;; ---------------------------------------------------------------------------
;; State management (CSRF protection for OAuth flow)
;; ---------------------------------------------------------------------------

(defn- generate-state
  "Generate a cryptographically secure state parameter for CSRF protection."
  []
  (let [bytes (byte-array 32)
        rng (SecureRandom.)]
    (.nextBytes rng bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

;; ---------------------------------------------------------------------------
;; Authorization URL
;; ---------------------------------------------------------------------------

(defn authorization-url
  "Build the OIDC authorization URL for the login redirect.
   Returns {:url \"...\", :state \"...\"} or nil if OIDC is not configured."
  [config callback-url]
  (when (get-in config [:oidc :enabled])
    (let [issuer-url (get-in config [:oidc :issuer-url])
          client-id (get-in config [:oidc :client-id])
          scopes (get-in config [:oidc :scopes] "openid profile email")
          discovery (fetch-oidc-discovery issuer-url)]
      (when-let [auth-endpoint (:authorization_endpoint discovery)]
        (let [state (generate-state)
              params {"response_type" "code"
                      "client_id" client-id
                      "redirect_uri" callback-url
                      "scope" scopes
                      "state" state}
              query-str (->> params
                             (map (fn [[k v]]
                                    (str (URLEncoder/encode k "UTF-8")
                                         "="
                                         (URLEncoder/encode (str v) "UTF-8"))))
                             (str/join "&"))]
          {:url (str auth-endpoint "?" query-str)
           :state state})))))

;; ---------------------------------------------------------------------------
;; Token exchange
;; ---------------------------------------------------------------------------

(defn exchange-code
  "Exchange an authorization code for tokens.
   Returns {:access_token, :id_token, :refresh_token, ...} or nil on failure."
  [config code callback-url]
  (let [issuer-url (get-in config [:oidc :issuer-url])
        client-id (get-in config [:oidc :client-id])
        client-secret (get-in config [:oidc :client-secret])
        discovery (fetch-oidc-discovery issuer-url)]
    (when-let [token-endpoint (:token_endpoint discovery)]
      (try
        (http-post-form token-endpoint
          {:grant_type "authorization_code"
           :code code
           :redirect_uri callback-url
           :client_id client-id
           :client_secret client-secret})
        (catch Exception e
          (log/error "OIDC token exchange failed:" (.getMessage e))
          nil)))))

;; ---------------------------------------------------------------------------
;; ID token parsing (simple base64 decode, no signature verification —
;; the token was received directly from the IdP over HTTPS via back-channel)
;; ---------------------------------------------------------------------------

(defn- decode-jwt-payload
  "Decode the payload (claims) section of a JWT without signature verification.
   Safe here because the token was received directly from the token endpoint
   over HTTPS (back-channel), not from the user's browser."
  [jwt-str]
  (try
    (let [parts (str/split jwt-str #"\.")
          payload (second parts)
          ;; Add padding if needed
          padded (let [rem (mod (count payload) 4)]
                   (if (zero? rem)
                     payload
                     (str payload (apply str (repeat (- 4 rem) "=")))))
          decoded (String. (.decode (Base64/getUrlDecoder) padded) "UTF-8")]
      (json/read-str decoded :key-fn keyword))
    (catch Exception e
      (log/error "Failed to decode JWT payload:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Role mapping
;; ---------------------------------------------------------------------------

(defn- extract-nested-claim
  "Extract a value from claims using a dot-separated path.
   e.g., \"realm_access.roles\" -> (get-in claims [:realm_access :roles])"
  [claims path]
  (let [keys (mapv keyword (str/split path #"\."))]
    (get-in claims keys)))

(defn resolve-role
  "Determine the Chengis role from OIDC claims using configured role-claim
   and role-mapping. Falls back to :default-role (default \"viewer\")."
  [claims config]
  (let [role-claim (get-in config [:oidc :role-claim])
        role-mapping (get-in config [:oidc :role-mapping] {})
        default-role (get-in config [:oidc :default-role] "viewer")]
    (if (str/blank? role-claim)
      default-role
      (let [claim-value (extract-nested-claim claims role-claim)]
        (cond
          ;; Claim is a list of roles — find the first match
          (sequential? claim-value)
          (or (some (fn [role-str]
                      (get role-mapping (str role-str)))
                    claim-value)
              default-role)

          ;; Claim is a single value
          (some? claim-value)
          (or (get role-mapping (str claim-value))
              default-role)

          :else
          default-role)))))

;; ---------------------------------------------------------------------------
;; OIDC identity store
;; ---------------------------------------------------------------------------

(defn find-oidc-identity
  "Look up a Chengis user by OIDC issuer + subject. Returns user map or nil."
  [ds issuer subject]
  (when-let [identity (jdbc/execute-one! ds
                        (sql/format {:select [:oi/user-id :u/username :u/role :u/active :u/session-version]
                                     :from [[:oidc-identities :oi]]
                                     :join [[:users :u] [:= :oi/user-id :u/id]]
                                     :where [:and
                                             [:= :oi/issuer issuer]
                                             [:= :oi/subject subject]]})
                        {:builder-fn rs/as-unqualified-kebab-maps})]
    ;; Update last_login_at
    (jdbc/execute-one! ds
      (sql/format {:update :oidc-identities
                   :set {:last-login-at [:raw "CURRENT_TIMESTAMP"]}
                   :where [:and [:= :issuer issuer] [:= :subject subject]]}))
    identity))

(defn create-oidc-identity!
  "Link an OIDC identity to a Chengis user."
  [ds {:keys [user-id issuer subject email display-name]}]
  (jdbc/execute-one! ds
    (sql/format {:insert-into :oidc-identities
                 :values [{:id (util/generate-id)
                           :user-id user-id
                           :issuer issuer
                           :subject subject
                           :email email
                           :display-name display-name}]})))

(defn- provision-oidc-user!
  "JIT (Just-In-Time) provision a new Chengis user from OIDC claims.
   Creates the user and links the OIDC identity."
  [ds config claims issuer]
  (let [subject (str (:sub claims))
        email (:email claims)
        ;; Use preferred_username, falling back to email prefix, then subject
        username (or (:preferred_username claims)
                     (when email (first (str/split email #"@")))
                     (str "oidc-" (subs subject 0 (min 8 (count subject)))))
        ;; Ensure unique username
        username (loop [candidate username n 0]
                   (if (user-store/get-user-by-username ds candidate)
                     (recur (str username "-" (inc n)) (inc n))
                     candidate))
        role (resolve-role claims config)
        display-name (or (:name claims) username)
        ;; Create user with a random password (OIDC users don't use passwords)
        random-pw (str (java.util.UUID/randomUUID))
        user (user-store/create-user! ds {:username username
                                           :password random-pw
                                           :role role})]
    ;; Link OIDC identity
    (create-oidc-identity! ds {:user-id (:id user)
                                :issuer issuer
                                :subject subject
                                :email email
                                :display-name display-name})
    (log/info "OIDC JIT provisioned user:" username "role:" role "from" issuer)
    user))

;; ---------------------------------------------------------------------------
;; OIDC callback handler
;; ---------------------------------------------------------------------------

(defn handle-callback
  "Process the OIDC callback after the user authenticates with the IdP.
   Returns {:success true :user user-map} or {:success false :error msg}."
  [ds config code callback-url expected-state actual-state]
  (cond
    ;; State mismatch — possible CSRF attack
    (not= expected-state actual-state)
    (do (log/warn "OIDC state mismatch — possible CSRF attack")
        {:success false :error "Authentication failed: state mismatch"})

    ;; Missing code
    (str/blank? code)
    {:success false :error "Authentication failed: no authorization code"}

    :else
    (let [tokens (exchange-code config code callback-url)]
      (if-not tokens
        {:success false :error "Authentication failed: token exchange error"}
        (let [id-token (:id_token tokens)
              claims (when id-token (decode-jwt-payload id-token))
              issuer-url (get-in config [:oidc :issuer-url])]
          (if-not claims
            {:success false :error "Authentication failed: invalid ID token"}
            (let [subject (str (:sub claims))
                  existing (find-oidc-identity ds issuer-url subject)]
              (cond
                ;; Existing OIDC user — check active status
                (and existing (some? (:active existing)) (zero? (:active existing)))
                {:success false :error "Account is deactivated"}

                ;; Existing OIDC user — log in
                existing
                {:success true
                 :user {:id (:user-id existing)
                        :username (:username existing)
                        :role (keyword (:role existing))
                        :session-version (or (:session-version existing) 0)}}

                ;; New user — JIT provision if allowed
                (get-in config [:oidc :auto-create-users] true)
                (let [user (provision-oidc-user! ds config claims issuer-url)]
                  {:success true
                   :user {:id (:id user)
                          :username (:username user)
                          :role (keyword (:role user))
                          :session-version 0}})

                :else
                {:success false
                 :error "No account linked to this identity. Contact your administrator."}))))))))

;; ---------------------------------------------------------------------------
;; Ring handlers
;; ---------------------------------------------------------------------------

(defn oidc-login-handler
  "Redirect the user to the OIDC provider's login page."
  [system]
  (fn [req]
    (let [config (:config system)
          scheme (or (get-in req [:headers "x-forwarded-proto"]) (name (:scheme req "http")))
          host (get-in req [:headers "host"] "localhost:8080")
          callback-url (str scheme "://" host "/auth/oidc/callback")
          auth-info (authorization-url config callback-url)]
      (if auth-info
        {:status 302
         :headers {"Location" (:url auth-info)}
         :session (assoc (:session req) :oidc-state (:state auth-info))}
        {:status 400
         :headers {"Content-Type" "text/html"}
         :body "<h1>OIDC Not Configured</h1><p>OpenID Connect is not configured.</p>"}))))

(defn oidc-callback-handler
  "Handle the OIDC callback after IdP authentication."
  [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)
          params (:query-params req)
          ;; Reitit passes query-params differently than ring defaults
          params (or params (:params req) {})
          code (or (get params "code") (get params :code))
          state (or (get params "state") (get params :state))
          error (or (get params "error") (get params :error))
          expected-state (get-in req [:session :oidc-state])
          scheme (or (get-in req [:headers "x-forwarded-proto"]) (name (:scheme req "http")))
          host (get-in req [:headers "host"] "localhost:8080")
          callback-url (str scheme "://" host "/auth/oidc/callback")]
      (cond
        ;; IdP returned an error
        error
        (do (log/warn "OIDC error from IdP:" error)
            {:status 303
             :headers {"Location" (str "/login?error=" (URLEncoder/encode (str "SSO error: " error) "UTF-8"))}})

        :else
        (let [result (handle-callback ds config code callback-url expected-state state)]
          (if (:success result)
            ;; Success — set session and redirect to dashboard
            {:status 303
             :headers {"Location" "/"}
             :session (-> (:session req)
                          (assoc :user (:user result))
                          (dissoc :oidc-state))}
            ;; Failure — redirect to login with error
            {:status 303
             :headers {"Location" (str "/login?error=" (URLEncoder/encode (:error result) "UTF-8"))}}))))))

;; ---------------------------------------------------------------------------
;; Helpers for views
;; ---------------------------------------------------------------------------

(defn oidc-enabled?
  "Check if OIDC is enabled in the configuration."
  [config]
  (boolean (get-in config [:oidc :enabled])))

(defn oidc-provider-name
  "Get a display name for the OIDC provider (from config or derive from issuer URL)."
  [config]
  (or (get-in config [:oidc :provider-name])
      (try
        (let [issuer (get-in config [:oidc :issuer-url])
              host (.getHost (URI/create issuer))]
          (cond
            (str/includes? host "keycloak") "Keycloak"
            (str/includes? host "auth0") "Auth0"
            (str/includes? host "okta") "Okta"
            (str/includes? host "login.microsoftonline") "Azure AD"
            (str/includes? host "accounts.google") "Google"
            :else "SSO"))
        (catch Exception _ "SSO"))))
