(ns chengis.web.oidc-test
  "Tests for OIDC authentication integration.
   Covers: JWT decoding/verification, role mapping, PKCE, callback handling,
   OIDC identity store, and JIT user provisioning."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.oidc :as oidc]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security KeyPairGenerator MessageDigest]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-oidc-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; JWT test helpers
;; ---------------------------------------------------------------------------

(defn- base64url-encode
  "Base64url-encode a byte array without padding."
  [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- base64url-encode-str
  "Base64url-encode a string."
  [^String s]
  (base64url-encode (.getBytes s "UTF-8")))

(defn- make-jwt-unsigned
  "Build a JWT string from header and payload maps, with a dummy signature."
  [header payload]
  (let [h (base64url-encode-str (json/write-str header))
        p (base64url-encode-str (json/write-str payload))]
    (str h "." p "." (base64url-encode (.getBytes "fakesig" "UTF-8")))))

(defn- sign-hs256
  "Sign header.payload with HMAC-SHA256 and return full JWT string."
  [header payload secret]
  (let [h (base64url-encode-str (json/write-str header))
        p (base64url-encode-str (json/write-str payload))
        signing-input (str h "." p)
        mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [sig (.doFinal mac (.getBytes signing-input "UTF-8"))]
      (str h "." p "." (base64url-encode sig)))))

(defn- generate-rsa-keypair
  "Generate an RSA key pair for testing JWT signature verification."
  []
  (let [gen (KeyPairGenerator/getInstance "RSA")]
    (.initialize gen 2048)
    (.generateKeyPair gen)))

(defn- sign-rs256
  "Sign header.payload with RS256 and return full JWT string."
  [header payload ^java.security.PrivateKey private-key]
  (let [h (base64url-encode-str (json/write-str header))
        p (base64url-encode-str (json/write-str payload))
        signing-input (str h "." p)
        sig-inst (java.security.Signature/getInstance "SHA256withRSA")]
    (.initSign sig-inst private-key)
    (.update sig-inst (.getBytes signing-input "UTF-8"))
    (let [sig (.sign sig-inst)]
      (str h "." p "." (base64url-encode sig)))))

(defn- rsa-key->jwk
  "Convert an RSA public key to a JWK map for testing."
  [^java.security.interfaces.RSAPublicKey pub-key kid]
  (let [n (.getModulus pub-key)
        e (.getPublicExponent pub-key)]
    {:kty "RSA"
     :use "sig"
     :kid kid
     :n (base64url-encode (.toByteArray n))
     :e (base64url-encode (.toByteArray e))}))

;; ---------------------------------------------------------------------------
;; JWT Decoding & Verification Tests
;; ---------------------------------------------------------------------------

(def ^:private test-secret "test-client-secret-for-hs256!!")

(defn- future-epoch
  "Return a future epoch timestamp (now + seconds)."
  [seconds-from-now]
  (+ (long (/ (System/currentTimeMillis) 1000)) seconds-from-now))

(defn- past-epoch
  "Return a past epoch timestamp (now - seconds)."
  [seconds-ago]
  (- (long (/ (System/currentTimeMillis) 1000)) seconds-ago))

(deftest hs256-jwt-verification-test
  (let [claims {:sub "user123"
                :iss "https://idp.example.com"
                :aud "chengis-client"
                :exp (future-epoch 3600)
                :nonce "test-nonce-123"}
        header {:alg "HS256" :typ "JWT"}
        jwt (sign-hs256 header claims test-secret)
        opts {:issuer-url "https://idp.example.com"
              :client-id "chengis-client"
              :client-secret test-secret
              :expected-nonce "test-nonce-123"
              :jwks-uri nil}]

    (testing "valid HS256 JWT is accepted"
      (let [result (oidc/decode-and-verify-jwt jwt opts)]
        (is (some? result))
        (is (= "user123" (:sub result)))
        (is (= "https://idp.example.com" (:iss result)))
        (is (= "chengis-client" (:aud result)))))

    (testing "HS256 JWT with wrong secret is rejected"
      (let [bad-jwt (sign-hs256 header claims "wrong-secret")
            result (oidc/decode-and-verify-jwt bad-jwt opts)]
        (is (nil? result))))

    (testing "HS256 JWT with tampered payload is rejected"
      (let [parts (str/split jwt #"\.")
            ;; Modify the payload part
            tampered (str (first parts) "."
                          (base64url-encode-str (json/write-str (assoc claims :sub "admin")))
                          "." (nth parts 2))
            result (oidc/decode-and-verify-jwt tampered opts)]
        (is (nil? result))))))

(deftest rs256-jwt-verification-test
  (let [keypair (generate-rsa-keypair)
        pub-key (.getPublic keypair)
        priv-key (.getPrivate keypair)
        kid "test-kid-1"
        jwk (rsa-key->jwk pub-key kid)
        claims {:sub "rsauser" :iss "https://rsa.example.com" :aud "chengis"
                :exp (future-epoch 3600) :nonce "rsa-nonce"}
        header {:alg "RS256" :typ "JWT" :kid kid}
        jwt (sign-rs256 header claims priv-key)]

    ;; Mock JWKS fetching by using with-redefs
    (with-redefs [oidc/fetch-oidc-discovery (fn [_] {:jwks_uri "https://rsa.example.com/.well-known/jwks.json"})]
      (testing "valid RS256 JWT with matching JWKS key"
        ;; We need to mock fetch-jwks to return our test JWK
        (with-redefs [chengis.web.oidc/fetch-jwks (fn [_] [jwk])]
          (let [result (oidc/decode-and-verify-jwt jwt
                         {:issuer-url "https://rsa.example.com"
                          :client-id "chengis"
                          :client-secret nil
                          :expected-nonce "rsa-nonce"
                          :jwks-uri "https://rsa.example.com/.well-known/jwks.json"})]
            (is (some? result))
            (is (= "rsauser" (:sub result))))))

      (testing "RS256 JWT with wrong kid returns nil"
        (with-redefs [chengis.web.oidc/fetch-jwks
                      (fn [_] [{:kty "RSA" :kid "different-kid" :use "sig"
                                 :n (:n jwk) :e (:e jwk)}])]
          (let [result (oidc/decode-and-verify-jwt jwt
                         {:issuer-url "https://rsa.example.com"
                          :client-id "chengis"
                          :expected-nonce "rsa-nonce"
                          :jwks-uri "https://rsa.example.com/.well-known/jwks.json"})]
            ;; kid mismatch → no matching key → nil
            (is (nil? result))))))))

(deftest jwt-claims-validation-test
  (let [header {:alg "HS256" :typ "JWT"}
        base-claims {:sub "user1" :iss "https://idp.example.com" :aud "chengis"
                     :exp (future-epoch 3600) :nonce "nonce1"}
        base-opts {:issuer-url "https://idp.example.com"
                   :client-id "chengis"
                   :client-secret test-secret
                   :expected-nonce "nonce1"}]

    (testing "expired JWT is rejected"
      (let [expired-claims (assoc base-claims :exp (past-epoch 60))
            jwt (sign-hs256 header expired-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt base-opts)]
        (is (nil? result))))

    (testing "wrong issuer is rejected"
      (let [jwt (sign-hs256 header base-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt (assoc base-opts :issuer-url "https://evil.com"))]
        (is (nil? result))))

    (testing "wrong audience is rejected"
      (let [jwt (sign-hs256 header base-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt (assoc base-opts :client-id "other-app"))]
        (is (nil? result))))

    (testing "wrong nonce is rejected"
      (let [jwt (sign-hs256 header base-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt (assoc base-opts :expected-nonce "wrong-nonce"))]
        (is (nil? result))))

    (testing "audience as array is accepted"
      (let [array-claims (assoc base-claims :aud ["chengis" "other-app"])
            jwt (sign-hs256 header array-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt base-opts)]
        (is (some? result))
        (is (= "user1" (:sub result)))))

    (testing "nil issuer-url skips issuer check"
      (let [jwt (sign-hs256 header base-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt (assoc base-opts :issuer-url nil))]
        (is (some? result))))

    (testing "nil expected-nonce skips nonce check"
      (let [jwt (sign-hs256 header base-claims test-secret)
            result (oidc/decode-and-verify-jwt jwt (assoc base-opts :expected-nonce nil))]
        (is (some? result))))))

(deftest jwt-malformed-input-test
  (let [opts {:client-secret test-secret}]

    (testing "empty string returns nil"
      (is (nil? (oidc/decode-and-verify-jwt "" opts))))

    (testing "non-JWT string returns nil"
      (is (nil? (oidc/decode-and-verify-jwt "not.a.jwt" opts))))

    (testing "JWT with only 2 parts returns nil"
      (is (nil? (oidc/decode-and-verify-jwt "part1.part2" opts))))

    (testing "JWT with invalid base64 returns nil"
      (is (nil? (oidc/decode-and-verify-jwt "!!!.@@@.###" opts))))))

(deftest unsupported-algorithm-test
  (let [header {:alg "none" :typ "JWT"}
        claims {:sub "user1" :exp (future-epoch 3600)}
        jwt (make-jwt-unsigned header claims)]
    (testing "alg=none is rejected"
      (is (nil? (oidc/decode-and-verify-jwt jwt {:client-secret test-secret}))))))

;; ---------------------------------------------------------------------------
;; Role Mapping Tests
;; ---------------------------------------------------------------------------

(deftest resolve-role-test
  (let [config {:oidc {:role-claim "realm_access.roles"
                       :role-mapping {"admin-role" "admin"
                                      "dev-role" "developer"}
                       :default-role "viewer"}}]

    (testing "maps first matching role from list"
      (let [claims {:realm_access {:roles ["dev-role" "admin-role"]}}]
        (is (= "developer" (oidc/resolve-role claims config)))))

    (testing "maps single string role"
      (let [config (assoc-in config [:oidc :role-claim] "role")
            claims {:role "admin-role"}]
        (is (= "admin" (oidc/resolve-role claims config)))))

    (testing "falls back to default when no mapping match"
      (let [claims {:realm_access {:roles ["unknown-role"]}}]
        (is (= "viewer" (oidc/resolve-role claims config)))))

    (testing "falls back to default when claim missing"
      (let [claims {:other "value"}]
        (is (= "viewer" (oidc/resolve-role claims config)))))

    (testing "falls back to default when role-claim is blank"
      (let [config (assoc-in config [:oidc :role-claim] "")]
        (is (= "viewer" (oidc/resolve-role {:realm_access {:roles ["admin-role"]}} config)))))

    (testing "deeply nested claim path works"
      (let [config (assoc-in config [:oidc :role-claim] "resource_access.chengis.roles")
            claims {:resource_access {:chengis {:roles ["admin-role"]}}}]
        (is (= "admin" (oidc/resolve-role claims config)))))))

;; ---------------------------------------------------------------------------
;; OIDC Identity Store Tests
;; ---------------------------------------------------------------------------

(deftest oidc-identity-crud-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "create OIDC identity link"
      (let [user (user-store/create-user! ds {:username "alice" :password "pw" :role "developer"})]
        (oidc/create-oidc-identity! ds {:user-id (:id user)
                                         :issuer "https://idp.example.com"
                                         :subject "oidc-sub-123"
                                         :email "alice@example.com"
                                         :display-name "Alice Smith"})

        (testing "find existing OIDC identity"
          (let [found (oidc/find-oidc-identity ds "https://idp.example.com" "oidc-sub-123")]
            (is (some? found))
            (is (= (:id user) (:user-id found)))
            (is (= "alice" (:username found)))
            (is (= "developer" (:role found)))))

        (testing "find returns nil for unknown subject"
          (is (nil? (oidc/find-oidc-identity ds "https://idp.example.com" "unknown-sub"))))

        (testing "find returns nil for unknown issuer"
          (is (nil? (oidc/find-oidc-identity ds "https://other-idp.com" "oidc-sub-123"))))))))

(deftest oidc-identity-deactivated-user-test
  (let [ds (conn/create-datasource test-db-path)]
    (let [user (user-store/create-user! ds {:username "bob" :password "pw" :role "developer"})]
      (oidc/create-oidc-identity! ds {:user-id (:id user)
                                       :issuer "https://idp.example.com"
                                       :subject "bob-sub"
                                       :email "bob@example.com"
                                       :display-name "Bob"})
      ;; Deactivate user
      (user-store/delete-user! ds (:id user))

      (testing "find-oidc-identity returns user with active=0 for deactivated user"
        (let [found (oidc/find-oidc-identity ds "https://idp.example.com" "bob-sub")]
          (is (some? found))
          (is (zero? (:active found))))))))

;; ---------------------------------------------------------------------------
;; Callback Handler Tests
;; ---------------------------------------------------------------------------

(deftest handle-callback-state-mismatch-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret}}]

    (testing "state mismatch returns error"
      (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                          "expected-state" "wrong-state" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "state mismatch"))))

    (testing "blank code returns error"
      (let [result (oidc/handle-callback ds config "" "http://callback"
                                          "state" "state" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "no authorization code"))))

    (testing "nil code returns error"
      (let [result (oidc/handle-callback ds config nil "http://callback"
                                          "state" "state" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "no authorization code"))))))

(deftest handle-callback-token-exchange-failure-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret}}]

    (testing "token exchange failure returns error"
      ;; exchange-code returns nil on failure
      (with-redefs [oidc/exchange-code (fn [& _] nil)]
        (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                            "state" "state" nil nil)]
          (is (false? (:success result)))
          (is (str/includes? (:error result) "token exchange error")))))))

(deftest handle-callback-existing-user-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret
                       :auto-create-users true}}
        user (user-store/create-user! ds {:username "existing" :password "pw" :role "developer"})]

    ;; Link OIDC identity
    (oidc/create-oidc-identity! ds {:user-id (:id user)
                                     :issuer "https://idp.example.com"
                                     :subject "existing-sub"
                                     :email "existing@example.com"
                                     :display-name "Existing User"})

    (testing "existing OIDC user logs in successfully"
      ;; Mock exchange-code and decode-and-verify-jwt
      (with-redefs [oidc/exchange-code (fn [& _] {:id_token "mock-token"})
                    oidc/decode-and-verify-jwt
                    (fn [_ _] {:sub "existing-sub" :email "existing@example.com"
                               :iss "https://idp.example.com"})
                    oidc/fetch-oidc-discovery (fn [_] {:jwks_uri "https://idp.example.com/jwks"})]
        (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                            "state" "state" nil nil)]
          (is (true? (:success result)))
          (is (= (:id user) (get-in result [:user :id])))
          (is (= "existing" (get-in result [:user :username])))
          (is (= :developer (get-in result [:user :role]))))))))

(deftest handle-callback-jit-provision-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret
                       :auto-create-users true
                       :role-claim "roles"
                       :role-mapping {"dev" "developer"}
                       :default-role "viewer"}}]

    (testing "new user is JIT provisioned"
      (with-redefs [oidc/exchange-code (fn [& _] {:id_token "mock-token"})
                    oidc/decode-and-verify-jwt
                    (fn [_ _] {:sub "new-sub-1" :email "newuser@example.com"
                               :preferred_username "newuser" :name "New User"
                               :roles "dev" :iss "https://idp.example.com"})
                    oidc/fetch-oidc-discovery (fn [_] {:jwks_uri "https://idp.example.com/jwks"})]
        (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                            "state" "state" nil nil)]
          (is (true? (:success result)))
          (is (= "newuser" (get-in result [:user :username])))
          (is (= :developer (get-in result [:user :role]))))))

    (testing "auto-create disabled rejects new users"
      (let [config (assoc-in config [:oidc :auto-create-users] false)]
        (with-redefs [oidc/exchange-code (fn [& _] {:id_token "mock-token"})
                      oidc/decode-and-verify-jwt
                      (fn [_ _] {:sub "brand-new-sub" :email "new2@example.com"
                                 :iss "https://idp.example.com"})
                      oidc/fetch-oidc-discovery (fn [_] {:jwks_uri "https://idp.example.com/jwks"})]
          (let [result (oidc/handle-callback ds config "code456" "http://callback"
                                              "state" "state" nil nil)]
            (is (false? (:success result)))
            (is (str/includes? (:error result) "No account linked"))))))))

(deftest handle-callback-deactivated-user-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret}}
        user (user-store/create-user! ds {:username "deactuser" :password "pw" :role "developer"})]
    (oidc/create-oidc-identity! ds {:user-id (:id user)
                                     :issuer "https://idp.example.com"
                                     :subject "deact-sub"
                                     :email "deact@example.com"
                                     :display-name "Deactivated"})
    (user-store/delete-user! ds (:id user))

    (testing "deactivated OIDC user is rejected"
      (with-redefs [oidc/exchange-code (fn [& _] {:id_token "mock-token"})
                    oidc/decode-and-verify-jwt
                    (fn [_ _] {:sub "deact-sub" :iss "https://idp.example.com"})
                    oidc/fetch-oidc-discovery (fn [_] {:jwks_uri "https://idp.example.com/jwks"})]
        (let [result (oidc/handle-callback ds config "code" "http://callback"
                                            "state" "state" nil nil)]
          (is (false? (:success result)))
          (is (str/includes? (:error result) "deactivated")))))))

;; ---------------------------------------------------------------------------
;; Ring Handler Tests
;; ---------------------------------------------------------------------------

(deftest oidc-login-handler-test
  (testing "OIDC not configured returns 400"
    (let [system {:config {:oidc {:enabled false}} :db nil}
          handler (oidc/oidc-login-handler system)
          resp (handler {:uri "/auth/oidc/login" :request-method :get
                         :headers {"host" "localhost:8080"}
                         :scheme :http :session {}})]
      (is (= 400 (:status resp)))))

  (testing "OIDC enabled redirects to IdP"
    (with-redefs [oidc/fetch-oidc-discovery
                  (fn [_] {:authorization_endpoint "https://idp.example.com/auth"})]
      (let [system {:config {:oidc {:enabled true
                                     :issuer-url "https://idp.example.com"
                                     :client-id "chengis"
                                     :scopes "openid"}} :db nil}
            handler (oidc/oidc-login-handler system)
            resp (handler {:uri "/auth/oidc/login" :request-method :get
                           :headers {"host" "localhost:8080"}
                           :scheme :http :session {}})]
        (is (= 302 (:status resp)))
        (is (str/starts-with? (get-in resp [:headers "Location"]) "https://idp.example.com/auth?"))
        ;; Session should contain state, nonce, and code-verifier (PKCE)
        (is (some? (get-in resp [:session :oidc-state])))
        (is (some? (get-in resp [:session :oidc-nonce])))
        (is (some? (get-in resp [:session :oidc-code-verifier])))))))

(deftest oidc-callback-handler-idp-error-test
  (let [system {:config {:oidc {:enabled true}} :db nil}
        handler (oidc/oidc-callback-handler system)]

    (testing "IdP error param redirects to login with error"
      (let [resp (handler {:uri "/auth/oidc/callback" :request-method :get
                           :query-params {"error" "access_denied" "state" "s"}
                           :headers {"host" "localhost:8080"}
                           :scheme :http :session {:oidc-state "s"}})]
        (is (= 303 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Location"]) "error="))
        ;; Session state should be cleaned up (CR-11)
        (is (nil? (get-in resp [:session :oidc-state])))
        (is (nil? (get-in resp [:session :oidc-nonce])))
        (is (nil? (get-in resp [:session :oidc-code-verifier])))))))

;; ---------------------------------------------------------------------------
;; View Helpers Tests
;; ---------------------------------------------------------------------------

(deftest oidc-enabled?-test
  (testing "returns true when enabled"
    (is (true? (oidc/oidc-enabled? {:oidc {:enabled true}}))))
  (testing "returns false when disabled"
    (is (false? (oidc/oidc-enabled? {:oidc {:enabled false}}))))
  (testing "returns false when missing"
    (is (false? (oidc/oidc-enabled? {})))))

(deftest oidc-provider-name-test
  (testing "explicit provider name"
    (is (= "MySSO" (oidc/oidc-provider-name {:oidc {:provider-name "MySSO"}}))))
  (testing "auto-detect Keycloak"
    (is (= "Keycloak" (oidc/oidc-provider-name {:oidc {:issuer-url "https://keycloak.company.com/realms/x"}}))))
  (testing "auto-detect Auth0"
    (is (= "Auth0" (oidc/oidc-provider-name {:oidc {:issuer-url "https://my-tenant.auth0.com/"}}))))
  (testing "auto-detect Okta"
    (is (= "Okta" (oidc/oidc-provider-name {:oidc {:issuer-url "https://dev-12345.okta.com"}}))))
  (testing "auto-detect Azure AD"
    (is (= "Azure AD" (oidc/oidc-provider-name {:oidc {:issuer-url "https://login.microsoftonline.com/tenant"}}))))
  (testing "unknown provider returns SSO"
    (is (= "SSO" (oidc/oidc-provider-name {:oidc {:issuer-url "https://custom-idp.example.com"}})))))

;; ---------------------------------------------------------------------------
;; Authorization URL (PKCE) Tests
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Regression Tests (External Review Findings)
;; ---------------------------------------------------------------------------

(deftest callback-rejects-nil-state-test
  (let [ds (conn/create-datasource test-db-path)
        config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                       :client-id "chengis" :client-secret test-secret}}]

    (testing "P1: both expected-state and actual-state nil → rejected (not bypass)"
      (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                          nil nil nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "state mismatch"))))

    (testing "P1: expected-state nil, actual-state present → rejected"
      (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                          nil "some-state" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "state mismatch"))))

    (testing "P1: expected-state present, actual-state nil → rejected"
      (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                          "some-state" nil nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "state mismatch"))))

    (testing "P1: both empty strings → rejected"
      (let [result (oidc/handle-callback ds config "code123" "http://callback"
                                          "" "" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "state mismatch"))))))

(deftest jwt-rejects-four-segment-token-test
  (let [opts {:client-secret test-secret}]

    (testing "P2: JWT with 4 segments is rejected"
      (is (nil? (oidc/decode-and-verify-jwt "a.b.c.d" opts))))

    (testing "P2: JWT with 5 segments is rejected"
      (is (nil? (oidc/decode-and-verify-jwt "a.b.c.d.e" opts))))))

(deftest jwt-rejects-missing-exp-claim-test
  (let [header {:alg "HS256" :typ "JWT"}
        ;; Claims with no :exp field
        claims-no-exp {:sub "user1"
                       :iss "https://idp.example.com"
                       :aud "chengis"
                       :nonce "test-nonce"}
        jwt (sign-hs256 header claims-no-exp test-secret)
        opts {:issuer-url "https://idp.example.com"
              :client-id "chengis"
              :client-secret test-secret
              :expected-nonce "test-nonce"}]

    (testing "P2: JWT without exp claim is rejected"
      (is (nil? (oidc/decode-and-verify-jwt jwt opts))))))

;; ---------------------------------------------------------------------------
;; Authorization URL (PKCE) Tests
;; ---------------------------------------------------------------------------

(deftest authorization-url-pkce-test
  (with-redefs [oidc/fetch-oidc-discovery
                (fn [_] {:authorization_endpoint "https://idp.example.com/auth"})]
    (let [config {:oidc {:enabled true :issuer-url "https://idp.example.com"
                         :client-id "chengis" :scopes "openid"}}
          result (oidc/authorization-url config "http://localhost:8080/auth/oidc/callback")]

      (testing "returns state, nonce, and code-verifier"
        (is (some? (:state result)))
        (is (some? (:nonce result)))
        (is (some? (:code-verifier result))))

      (testing "URL contains PKCE code_challenge"
        (is (str/includes? (:url result) "code_challenge="))
        (is (str/includes? (:url result) "code_challenge_method=S256")))

      (testing "URL contains nonce"
        (is (str/includes? (:url result) "nonce="))))))

(deftest authorization-url-disabled-test
  (testing "returns nil when OIDC is disabled"
    (is (nil? (oidc/authorization-url {:oidc {:enabled false}} "http://callback")))))

;; ---------------------------------------------------------------------------
;; Callback URL Resolution Tests (CR-06)
;; ---------------------------------------------------------------------------

(deftest callback-url-resolution-test
  (testing "uses explicit config callback-url when set"
    (let [config {:oidc {:callback-url "https://app.example.com/auth/oidc/callback"}}
          ;; resolve-callback-url is private, test indirectly via login handler
          system {:config (assoc config :oidc (assoc (:oidc config) :enabled true
                                                      :issuer-url "https://idp.example.com"
                                                      :client-id "chengis"
                                                      :scopes "openid"))
                  :db nil}]
      (with-redefs [oidc/fetch-oidc-discovery
                    (fn [_] {:authorization_endpoint "https://idp.example.com/auth"})]
        (let [handler (oidc/oidc-login-handler system)
              resp (handler {:uri "/auth/oidc/login" :request-method :get
                             :headers {"host" "evil.com:9999" "x-forwarded-proto" "http"}
                             :scheme :http :session {}})]
          ;; The redirect URL should use the configured callback, not the host header
          (is (str/includes? (get-in resp [:headers "Location"])
                             "redirect_uri=https%3A%2F%2Fapp.example.com%2Fauth%2Foidc%2Fcallback")))))))
