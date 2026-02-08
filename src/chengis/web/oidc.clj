(ns chengis.web.oidc
  "OpenID Connect (OIDC) authentication integration.
   Supports authorization code flow with PKCE and any OIDC-compliant identity provider
   (Keycloak, Auth0, Okta, Azure AD, Google Workspace, etc.).

   Security features:
   - PKCE (RFC 7636) for authorization code protection
   - Nonce validation for ID token replay prevention
   - JWT signature verification via JWKS
   - ID token claims validation (iss, aud, exp)
   - State parameter CSRF protection

   Configuration:
     :oidc {:enabled true
            :issuer-url \"https://idp.example.com/realms/chengis\"
            :client-id \"chengis\"
            :client-secret \"secret\"
            :callback-url nil  ;; Optional: explicit callback URL (recommended behind proxy)
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
  (:import [java.math BigInteger]
           [java.net URI URLEncoder]
           [java.security KeyFactory MessageDigest SecureRandom]
           [java.security.spec RSAPublicKeySpec]
           [java.time Instant]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Shared HTTP client (reused across all OIDC calls — CR-09)
;; ---------------------------------------------------------------------------

(def ^:private shared-http-client
  "Shared java.net.http.HttpClient with connection pooling and timeouts."
  (delay
    (-> (java.net.http.HttpClient/newBuilder)
        (.connectTimeout (java.time.Duration/ofSeconds 10))
        (.build))))

(defn- http-get-json
  "Perform a simple HTTP GET and parse JSON response.
   Uses java.net.http.HttpClient (Java 11+) to avoid external HTTP dependencies."
  [url]
  (let [request (-> (java.net.http.HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Accept" "application/json")
                    (.timeout (java.time.Duration/ofSeconds 10))
                    (.GET)
                    (.build))
        response (.send @shared-http-client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
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
        request (-> (java.net.http.HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.header "Accept" "application/json")
                    (.timeout (java.time.Duration/ofSeconds 10))
                    (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                    (.build))
        response (.send @shared-http-client request (java.net.http.HttpResponse$BodyHandlers/ofString))]
    (when (< (.statusCode response) 400)
      (json/read-str (.body response) :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; OIDC Discovery
;; ---------------------------------------------------------------------------

(defonce ^:private oidc-config-cache
  (atom {:data nil :fetched-at 0}))

(def ^:private discovery-cache-ttl-ms
  "Cache OIDC discovery document for 1 hour."
  3600000)

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
        (let [discovery-url (str (str/replace issuer-url #"/+$" "")
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
;; JWKS — JSON Web Key Set (CR-01: JWT signature verification)
;; ---------------------------------------------------------------------------

(defonce ^:private jwks-cache ;; Cached JWKS keyset: {:keys [...] :fetched-at epoch-ms}
  (atom {:keys nil :fetched-at 0}))

(def ^:private jwks-cache-ttl-ms
  "Cache JWKS for 1 hour (keys rotate infrequently)."
  3600000)

(defn- base64url-decode-bigint
  "Decode a Base64url-encoded string to a BigInteger (unsigned, big-endian)."
  [^String s]
  (let [bytes (.decode (Base64/getUrlDecoder) s)]
    (BigInteger. 1 bytes)))

(defn- jwk->public-key
  "Convert a JWK (JSON Web Key) map to a java.security.PublicKey.
   Supports RSA keys (kty=RSA)."
  [{:keys [kty n e]}]
  (when (= kty "RSA")
    (try
      (let [modulus (base64url-decode-bigint n)
            exponent (base64url-decode-bigint e)
            spec (RSAPublicKeySpec. modulus exponent)
            factory (KeyFactory/getInstance "RSA")]
        (.generatePublic factory spec))
      (catch Exception ex
        (log/warn "Failed to parse JWK:" (.getMessage ex))
        nil))))

(defn- fetch-jwks
  "Fetch the JWKS from the IdP's jwks_uri. Cached for 1 hour."
  [jwks-uri]
  (let [now (System/currentTimeMillis)
        cached @jwks-cache]
    (if (and (:keys cached)
             (< (- now (:fetched-at cached)) jwks-cache-ttl-ms))
      (:keys cached)
      (try
        (let [data (http-get-json jwks-uri)]
          (when-let [keys (:keys data)]
            (reset! jwks-cache {:keys keys :fetched-at now})
            (log/debug "JWKS loaded:" (count keys) "keys")
            keys))
        (catch Exception e
          (log/error "Failed to fetch JWKS:" (.getMessage e))
          ;; Return stale cache if available
          (:keys cached))))))

(defn- find-jwk-by-kid
  "Find a JWK matching the given kid (Key ID) from the JWKS list."
  [jwks kid]
  (if kid
    (first (filter #(= (:kid %) kid) jwks))
    ;; If no kid in JWT header, use the first RSA signing key
    (first (filter #(and (= (:kty %) "RSA")
                         (or (nil? (:use %)) (= (:use %) "sig")))
                   jwks))))

;; ---------------------------------------------------------------------------
;; PKCE — Proof Key for Code Exchange (CR-03)
;; ---------------------------------------------------------------------------

(defn- generate-code-verifier
  "Generate a cryptographically random code verifier (43-128 chars, RFC 7636)."
  []
  (let [bytes (byte-array 32)
        rng (SecureRandom.)]
    (.nextBytes rng bytes)
    ;; Base64url-encode without padding (per RFC 7636)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn- compute-code-challenge
  "Compute the S256 code challenge from a code verifier (RFC 7636).
   challenge = BASE64URL(SHA256(code_verifier))"
  [code-verifier]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash (.digest digest (.getBytes ^String code-verifier "US-ASCII"))]
    (-> (Base64/getUrlEncoder)
        (.withoutPadding)
        (.encodeToString hash))))

;; ---------------------------------------------------------------------------
;; State + Nonce generation (CSRF + replay protection)
;; ---------------------------------------------------------------------------

(defn- generate-random-token
  "Generate a cryptographically secure random token (URL-safe base64, 32 bytes)."
  []
  (let [bytes (byte-array 32)
        rng (SecureRandom.)]
    (.nextBytes rng bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

;; ---------------------------------------------------------------------------
;; Authorization URL (with PKCE + nonce)
;; ---------------------------------------------------------------------------

(defn authorization-url
  "Build the OIDC authorization URL for the login redirect.
   Returns {:url, :state, :nonce, :code-verifier} or nil if OIDC is not configured."
  [config callback-url]
  (when (get-in config [:oidc :enabled])
    (let [issuer-url (get-in config [:oidc :issuer-url])
          client-id (get-in config [:oidc :client-id])
          scopes (get-in config [:oidc :scopes] "openid profile email")
          discovery (fetch-oidc-discovery issuer-url)]
      (when-let [auth-endpoint (:authorization_endpoint discovery)]
        (let [state (generate-random-token)
              nonce (generate-random-token)
              code-verifier (generate-code-verifier)
              code-challenge (compute-code-challenge code-verifier)
              params {"response_type" "code"
                      "client_id" client-id
                      "redirect_uri" callback-url
                      "scope" scopes
                      "state" state
                      "nonce" nonce
                      "code_challenge" code-challenge
                      "code_challenge_method" "S256"}
              query-str (->> params
                             (map (fn [[k v]]
                                    (str (URLEncoder/encode k "UTF-8")
                                         "="
                                         (URLEncoder/encode (str v) "UTF-8"))))
                             (str/join "&"))]
          {:url (str auth-endpoint "?" query-str)
           :state state
           :nonce nonce
           :code-verifier code-verifier})))))

;; ---------------------------------------------------------------------------
;; Token exchange (with PKCE code_verifier)
;; ---------------------------------------------------------------------------

(defn exchange-code
  "Exchange an authorization code for tokens. Includes PKCE code_verifier.
   Returns {:access_token, :id_token, :refresh_token, ...} or nil on failure."
  [config code callback-url code-verifier]
  (let [issuer-url (get-in config [:oidc :issuer-url])
        client-id (get-in config [:oidc :client-id])
        client-secret (get-in config [:oidc :client-secret])
        discovery (fetch-oidc-discovery issuer-url)]
    (when-let [token-endpoint (:token_endpoint discovery)]
      (try
        (http-post-form token-endpoint
          (cond-> {:grant_type "authorization_code"
                   :code code
                   :redirect_uri callback-url
                   :client_id client-id
                   :client_secret client-secret}
            ;; Include PKCE verifier
            code-verifier (assoc :code_verifier code-verifier)))
        (catch Exception e
          (log/error "OIDC token exchange failed:" (.getMessage e))
          nil)))))

;; ---------------------------------------------------------------------------
;; JWT decoding and verification (CR-01, CR-02)
;; ---------------------------------------------------------------------------

(defn- decode-base64url
  "Decode a Base64url-encoded string to a byte array, handling missing padding."
  [^String s]
  (let [padded (let [rem (mod (count s) 4)]
                 (if (zero? rem)
                   s
                   (str s (apply str (repeat (- 4 rem) "=")))))]
    (.decode (Base64/getUrlDecoder) padded)))

(defn- decode-jwt-part
  "Decode a single JWT part (header or payload) from Base64url to a map."
  [^String part]
  (let [decoded (String. (decode-base64url part) "UTF-8")]
    (json/read-str decoded :key-fn keyword)))

(defn- verify-rsa-signature
  "Verify an RS256 JWT signature using the public key.
   Returns true if valid, false otherwise."
  [^String signing-input ^bytes signature-bytes ^java.security.PublicKey public-key]
  (try
    (let [sig (java.security.Signature/getInstance "SHA256withRSA")]
      (.initVerify sig public-key)
      (.update sig (.getBytes signing-input "UTF-8"))
      (.verify sig signature-bytes))
    (catch Exception e
      (log/warn "JWT signature verification failed:" (.getMessage e))
      false)))

(defn- verify-hmac-signature
  "Verify an HS256 JWT signature using the client secret.
   Returns true if valid, false otherwise."
  [^String signing-input ^bytes signature-bytes ^String secret]
  (try
    (let [mac (Mac/getInstance "HmacSHA256")
          key-spec (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")]
      (.init mac key-spec)
      (let [expected (.doFinal mac (.getBytes signing-input "UTF-8"))]
        (MessageDigest/isEqual expected signature-bytes)))
    (catch Exception e
      (log/warn "JWT HMAC verification failed:" (.getMessage e))
      false)))

(defn decode-and-verify-jwt
  "Decode and verify a JWT ID token.
   Verifies: signature (RS256 via JWKS or HS256 via client secret), exp, aud, iss, nonce.
   Returns claims map on success, nil on failure."
  [jwt-str {:keys [issuer-url client-id client-secret expected-nonce jwks-uri]}]
  (try
    (let [parts (str/split jwt-str #"\.")
          ;; P2 fix: enforce exactly 3 parts (reject 4+ segment tokens)
          _ (when (not= (count parts) 3)
              (throw (ex-info "JWT must have exactly 3 parts" {:parts (count parts)})))
          header (decode-jwt-part (nth parts 0))
          claims (decode-jwt-part (nth parts 1))
          signature-bytes (decode-base64url (nth parts 2))
          signing-input (str (nth parts 0) "." (nth parts 1))
          alg (:alg header)]

      ;; --- Signature verification (CR-01) ---
      (let [sig-valid?
            (case alg
              "RS256"
              (if jwks-uri
                (let [jwks (fetch-jwks jwks-uri)
                      jwk (find-jwk-by-kid jwks (:kid header))
                      public-key (when jwk (jwk->public-key jwk))]
                  (if public-key
                    (verify-rsa-signature signing-input signature-bytes public-key)
                    (do (log/error "No matching JWK found for kid:" (:kid header))
                        false)))
                (do (log/error "RS256 JWT but no jwks_uri available")
                    false))

              "HS256"
              (if client-secret
                (verify-hmac-signature signing-input signature-bytes client-secret)
                (do (log/error "HS256 JWT but no client_secret available")
                    false))

              ;; Unsupported algorithm
              (do (log/error "Unsupported JWT algorithm:" alg)
                  false))]

        (when-not sig-valid?
          (throw (ex-info "JWT signature verification failed" {:alg alg})))

        ;; --- Claims validation (CR-02) ---

        ;; Check expiry — P2 fix: exp is mandatory per OIDC Core spec §2
        (let [now-epoch (/ (System/currentTimeMillis) 1000)]
          (when-not (:exp claims)
            (throw (ex-info "JWT missing required exp claim" {:claims-keys (keys claims)})))
          (when (<= (:exp claims) now-epoch)
            (throw (ex-info "JWT has expired" {:exp (:exp claims) :now now-epoch})))

          ;; Check not-before (optional claim)
          (when-let [nbf (:nbf claims)]
            (when (> nbf (+ now-epoch 60)) ;; 60s clock skew tolerance
              (throw (ex-info "JWT not yet valid" {:nbf nbf :now now-epoch})))))

        ;; Check issuer
        (when issuer-url
          (when-not (= (:iss claims) issuer-url)
            (throw (ex-info "JWT issuer mismatch"
                            {:expected issuer-url :actual (:iss claims)}))))

        ;; Check audience (can be string or array)
        (when client-id
          (let [aud (:aud claims)
                aud-set (cond
                          (string? aud) #{aud}
                          (sequential? aud) (set aud)
                          :else #{})]
            (when-not (contains? aud-set client-id)
              (throw (ex-info "JWT audience mismatch"
                              {:expected client-id :actual aud})))))

        ;; Check nonce (CR-05)
        (when expected-nonce
          (when-not (= (:nonce claims) expected-nonce)
            (throw (ex-info "JWT nonce mismatch" {}))))

        ;; All checks passed
        claims))
    (catch Exception e
      (log/error "JWT verification failed:" (.getMessage e))
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
   and role-mapping. Falls back to :default-role (default \"viewer\").
   Logs a warning when role extraction returns nil (helps diagnose misconfig)."
  [claims config]
  (let [role-claim (get-in config [:oidc :role-claim])
        role-mapping (get-in config [:oidc :role-mapping] {})
        default-role (get-in config [:oidc :default-role] "viewer")]
    (if (str/blank? role-claim)
      default-role
      (let [claim-value (extract-nested-claim claims role-claim)]
        (when (nil? claim-value)
          (log/warn "OIDC role claim" role-claim "not found in ID token claims — using default role:" default-role))
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
;; Callback URL resolution (CR-06)
;; ---------------------------------------------------------------------------

(defn- resolve-callback-url
  "Resolve the OIDC callback URL. Uses explicit config if set,
   otherwise reconstructs from request headers."
  [config req]
  (or (get-in config [:oidc :callback-url])
      (let [scheme (or (get-in req [:headers "x-forwarded-proto"]) (name (:scheme req "http")))
            host (get-in req [:headers "host"] "localhost:8080")]
        (str scheme "://" host "/auth/oidc/callback"))))

;; ---------------------------------------------------------------------------
;; OIDC callback handler
;; ---------------------------------------------------------------------------

(defn handle-callback
  "Process the OIDC callback after the user authenticates with the IdP.
   Validates state, exchanges code (with PKCE), verifies ID token (signature, claims, nonce).
   Returns {:success true :user user-map} or {:success false :error msg}."
  [ds config code callback-url expected-state actual-state expected-nonce code-verifier]
  (cond
    ;; State mismatch or missing — possible CSRF attack
    ;; P1 fix: require both values to be present (prevents nil=nil bypass)
    (or (str/blank? expected-state)
        (str/blank? actual-state)
        (not= expected-state actual-state))
    (do (log/warn "OIDC state mismatch — possible CSRF attack"
                  {:expected-present (some? expected-state)
                   :actual-present (some? actual-state)})
        {:success false :error "Authentication failed: state mismatch"})

    ;; Missing code
    (str/blank? code)
    {:success false :error "Authentication failed: no authorization code"}

    :else
    (let [tokens (exchange-code config code callback-url code-verifier)]
      (if-not tokens
        {:success false :error "Authentication failed: token exchange error"}
        (let [id-token (:id_token tokens)
              issuer-url (get-in config [:oidc :issuer-url])
              client-id (get-in config [:oidc :client-id])
              client-secret (get-in config [:oidc :client-secret])
              discovery (fetch-oidc-discovery issuer-url)
              ;; Verify ID token with full validation (CR-01, CR-02, CR-05)
              claims (when id-token
                       (decode-and-verify-jwt id-token
                         {:issuer-url issuer-url
                          :client-id client-id
                          :client-secret client-secret
                          :expected-nonce expected-nonce
                          :jwks-uri (:jwks_uri discovery)}))]
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
          callback-url (resolve-callback-url config req)
          auth-info (authorization-url config callback-url)]
      (if auth-info
        {:status 302
         :headers {"Location" (:url auth-info)}
         :session (assoc (:session req)
                    :oidc-state (:state auth-info)
                    :oidc-nonce (:nonce auth-info)
                    :oidc-code-verifier (:code-verifier auth-info))}
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
          expected-nonce (get-in req [:session :oidc-nonce])
          code-verifier (get-in req [:session :oidc-code-verifier])
          callback-url (resolve-callback-url config req)]
      (cond
        ;; IdP returned an error
        error
        (do (log/warn "OIDC error from IdP:" error)
            {:status 303
             :headers {"Location" (str "/login?error=" (URLEncoder/encode (str "SSO error: " error) "UTF-8"))}
             ;; Clean up session state even on IdP error (CR-11)
             :session (-> (:session req)
                          (dissoc :oidc-state :oidc-nonce :oidc-code-verifier))})

        :else
        (let [result (handle-callback ds config code callback-url
                                       expected-state state expected-nonce code-verifier)]
          (if (:success result)
            ;; Success — set session and redirect to dashboard
            {:status 303
             :headers {"Location" "/"}
             :session (-> (:session req)
                          (assoc :user (:user result))
                          (dissoc :oidc-state :oidc-nonce :oidc-code-verifier))}
            ;; Failure — redirect to login with error; clean session state (CR-11)
            {:status 303
             :headers {"Location" (str "/login?error=" (URLEncoder/encode (:error result) "UTF-8"))}
             :session (-> (:session req)
                          (dissoc :oidc-state :oidc-nonce :oidc-code-verifier))}))))))

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
