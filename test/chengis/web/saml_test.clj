(ns chengis.web.saml-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.saml :as saml])
  (:import [java.util Base64 UUID]))

;; ---------------------------------------------------------------------------
;; Test DB setup (same pattern as regulatory_test.clj)
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-saml-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def test-saml-config
  {:feature-flags {:saml true}
   :saml {:enabled true
          :sp-entity-id "https://chengis.example.com/auth/saml/metadata"
          :idp-sso-url "https://idp.example.com/saml/sso"
          :idp-certificate nil ;; No cert for basic tests
          :acs-url "https://chengis.example.com/auth/saml/acs"
          :role-attribute "Role"
          :role-mapping {"Admin" "admin"
                         "Developer" "developer"
                         "Viewer" "viewer"}
          :default-role "viewer"
          :auto-create-users true
          :provider-name "Test SAML"}})

(def disabled-saml-config
  {:feature-flags {:saml false}
   :saml {:enabled false}})

(defn- test-system
  ([] (test-system test-saml-config))
  ([config]
   {:db (conn/create-datasource test-db-path)
    :config config}))

(defn- build-mock-saml-response-xml
  "Build a minimal SAML Response XML string for testing.
   Does NOT include a real XML signature."
  [{:keys [name-id email display-name role in-response-to status-code
           audience not-before not-on-or-after]
    :or {name-id "user@example.com"
         email "user@example.com"
         display-name "Test User"
         role "Developer"
         status-code "urn:oasis:names:tc:SAML:2.0:status:Success"
         audience "https://chengis.example.com/auth/saml/metadata"}}]
  (let [now-str (.toString (java.time.Instant/now))
        not-before (or not-before now-str)
        not-on-or-after (or not-on-or-after
                             (.toString (.plusSeconds (java.time.Instant/now) 300)))
        in-resp-attr (if in-response-to
                       (str " InResponseTo=\"" in-response-to "\"")
                       "")]
    (str
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
      "<samlp:Response"
      " xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
      " xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\""
      " ID=\"_resp-" (UUID/randomUUID) "\""
      " Version=\"2.0\""
      " IssueInstant=\"" now-str "\""
      in-resp-attr
      ">"
      "<samlp:Status>"
      "<samlp:StatusCode Value=\"" status-code "\"/>"
      "</samlp:Status>"
      "<saml:Assertion"
      " xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\""
      " ID=\"_assert-" (UUID/randomUUID) "\""
      " Version=\"2.0\""
      " IssueInstant=\"" now-str "\""
      ">"
      "<saml:Issuer>https://idp.example.com</saml:Issuer>"
      "<saml:Subject>"
      "<saml:NameID Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress\">"
      name-id
      "</saml:NameID>"
      "</saml:Subject>"
      "<saml:Conditions"
      " NotBefore=\"" not-before "\""
      " NotOnOrAfter=\"" not-on-or-after "\">"
      "<saml:AudienceRestriction>"
      "<saml:Audience>" audience "</saml:Audience>"
      "</saml:AudienceRestriction>"
      "</saml:Conditions>"
      "<saml:AttributeStatement>"
      "<saml:Attribute Name=\"email\">"
      "<saml:AttributeValue>" email "</saml:AttributeValue>"
      "</saml:Attribute>"
      "<saml:Attribute Name=\"displayName\">"
      "<saml:AttributeValue>" display-name "</saml:AttributeValue>"
      "</saml:Attribute>"
      "<saml:Attribute Name=\"Role\">"
      "<saml:AttributeValue>" role "</saml:AttributeValue>"
      "</saml:Attribute>"
      "</saml:AttributeStatement>"
      "</saml:Assertion>"
      "</samlp:Response>")))

(defn- encode-saml-response
  "Base64-encode a SAML Response XML string for POST binding."
  [xml-str]
  (.encodeToString (Base64/getEncoder) (.getBytes xml-str "UTF-8")))

;; ---------------------------------------------------------------------------
;; Tests: generate-authn-request
;; ---------------------------------------------------------------------------

(deftest generate-authn-request-returns-url-and-ids
  (testing "generate-authn-request returns url, relay-state, and request-id"
    (let [result (saml/generate-authn-request test-saml-config)]
      (is (some? result) "Should return non-nil when SAML is enabled")
      (is (string? (:url result)) "Should contain a URL string")
      (is (string? (:relay-state result)) "Should contain a relay-state")
      (is (string? (:request-id result)) "Should contain a request-id")
      (is (.startsWith (:request-id result) "_") "request-id should start with underscore")
      (is (.contains (:url result) "SAMLRequest=") "URL should contain SAMLRequest parameter")
      (is (.contains (:url result) "RelayState=") "URL should contain RelayState parameter")
      (is (.startsWith (:url result) "https://idp.example.com/saml/sso?")
          "URL should start with IdP SSO URL"))))

(deftest generate-authn-request-returns-nil-when-disabled
  (testing "generate-authn-request returns nil when SAML is disabled"
    (let [result (saml/generate-authn-request disabled-saml-config)]
      (is (nil? result) "Should return nil when SAML is disabled"))))

;; ---------------------------------------------------------------------------
;; Tests: validate-saml-response
;; ---------------------------------------------------------------------------

(deftest validate-saml-response-valid-test
  (testing "validate-saml-response succeeds with valid mock response (no signature check)"
    ;; Use config without IdP certificate so signature verification is skipped
    (let [config (assoc-in test-saml-config [:saml :idp-certificate] nil)
          xml (build-mock-saml-response-xml {:name-id "alice@example.com"
                                              :email "alice@example.com"
                                              :display-name "Alice"
                                              :role "Admin"})
          b64 (encode-saml-response xml)
          result (saml/validate-saml-response config b64 nil)]
      (is (:success result) (str "Should succeed, got: " (:error result)))
      (is (= "alice@example.com" (:name-id result)))
      (is (some? (:attributes result)))
      (is (= ["alice@example.com"] (get (:attributes result) "email")))
      (is (= ["Admin"] (get (:attributes result) "Role"))))))

(deftest validate-saml-response-in-response-to-mismatch-test
  (testing "validate-saml-response fails on InResponseTo mismatch"
    (let [config (assoc-in test-saml-config [:saml :idp-certificate] nil)
          xml (build-mock-saml-response-xml {:in-response-to "_wrong-id"})
          b64 (encode-saml-response xml)
          result (saml/validate-saml-response config b64 "_expected-id")]
      (is (not (:success result)) "Should fail on InResponseTo mismatch")
      (is (string? (:error result))))))

(deftest validate-saml-response-failure-status-test
  (testing "validate-saml-response fails on non-Success status"
    (let [config (assoc-in test-saml-config [:saml :idp-certificate] nil)
          xml (build-mock-saml-response-xml
                {:status-code "urn:oasis:names:tc:SAML:2.0:status:Requester"})
          b64 (encode-saml-response xml)
          result (saml/validate-saml-response config b64 nil)]
      (is (not (:success result)) "Should fail on non-Success status"))))

(deftest validate-saml-response-expired-test
  (testing "validate-saml-response fails on expired assertion"
    (let [config (assoc-in test-saml-config [:saml :idp-certificate] nil)
          past (.toString (.minusSeconds (java.time.Instant/now) 7200))
          past2 (.toString (.minusSeconds (java.time.Instant/now) 3600))
          xml (build-mock-saml-response-xml {:not-before past
                                              :not-on-or-after past2})
          b64 (encode-saml-response xml)
          result (saml/validate-saml-response config b64 nil)]
      (is (not (:success result)) "Should fail on expired assertion"))))

(deftest validate-saml-response-audience-mismatch-test
  (testing "validate-saml-response fails on audience mismatch"
    (let [config (assoc-in test-saml-config [:saml :idp-certificate] nil)
          xml (build-mock-saml-response-xml
                {:audience "https://wrong-sp.example.com/metadata"})
          b64 (encode-saml-response xml)
          result (saml/validate-saml-response config b64 nil)]
      (is (not (:success result)) "Should fail on audience mismatch"))))

;; ---------------------------------------------------------------------------
;; Tests: resolve-saml-role
;; ---------------------------------------------------------------------------

(deftest resolve-saml-role-admin-mapping-test
  (testing "resolve-saml-role maps Admin to admin"
    (let [attrs {"Role" ["Admin"]}
          role (saml/resolve-saml-role attrs test-saml-config)]
      (is (= "admin" role)))))

(deftest resolve-saml-role-developer-mapping-test
  (testing "resolve-saml-role maps Developer to developer"
    (let [attrs {"Role" ["Developer"]}
          role (saml/resolve-saml-role attrs test-saml-config)]
      (is (= "developer" role)))))

(deftest resolve-saml-role-default-fallback-test
  (testing "resolve-saml-role falls back to default role for unmapped values"
    (let [attrs {"Role" ["UnknownRole"]}
          role (saml/resolve-saml-role attrs test-saml-config)]
      (is (= "viewer" role)))))

(deftest resolve-saml-role-missing-attribute-test
  (testing "resolve-saml-role falls back when role attribute is missing"
    (let [attrs {"SomeOtherAttr" ["value"]}
          role (saml/resolve-saml-role attrs test-saml-config)]
      (is (= "viewer" role)))))

(deftest resolve-saml-role-no-role-attribute-configured-test
  (testing "resolve-saml-role returns default when no role attribute is configured"
    (let [config (assoc-in test-saml-config [:saml :role-attribute] nil)
          attrs {"Role" ["Admin"]}
          role (saml/resolve-saml-role attrs config)]
      (is (= "viewer" role)))))

;; ---------------------------------------------------------------------------
;; Tests: SAML identity store (find / create)
;; ---------------------------------------------------------------------------

(deftest find-saml-identity-nil-when-not-found-test
  (testing "find-saml-identity returns nil when identity does not exist"
    (let [ds (conn/create-datasource test-db-path)
          result (saml/find-saml-identity ds "https://idp.example.com" "nonexistent@example.com")]
      (is (nil? result)))))

(deftest create-and-find-saml-identity-test
  (testing "create-saml-identity! inserts and find-saml-identity retrieves it"
    (let [ds (conn/create-datasource test-db-path)
          ;; Create a user first
          user (user-store/create-user! ds {:username "saml-alice"
                                             :password "random-pw"
                                             :role "developer"})
          _ (saml/create-saml-identity! ds
              {:user-id (:id user)
               :idp-entity-id "https://idp.example.com"
               :name-id "alice@example.com"
               :name-id-format "urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress"
               :email "alice@example.com"
               :display-name "Alice"
               :attributes-json (json/write-str {"Role" ["Developer"]})})
          found (saml/find-saml-identity ds "https://idp.example.com" "alice@example.com")]
      (is (some? found) "Should find the SAML identity")
      (is (= (:id user) (:user-id found)))
      (is (= "saml-alice" (:username found)))
      (is (= "developer" (:role found))))))

;; ---------------------------------------------------------------------------
;; Tests: provision-saml-user!
;; ---------------------------------------------------------------------------

(deftest provision-saml-user-test
  (testing "provision-saml-user! creates user and links SAML identity"
    (let [ds (conn/create-datasource test-db-path)
          user (saml/provision-saml-user! ds test-saml-config
                 "bob@example.com" "bob@example.com" "Bob Smith"
                 {"Role" ["Developer"] "email" ["bob@example.com"]})]
      (is (some? user))
      (is (= "bob" (:username user)) "Should derive username from email prefix")
      (is (= "developer" (:role user)) "Should map Developer to developer role")
      ;; Verify identity was created
      (let [idp-entity-id (get-in test-saml-config [:saml :idp-sso-url])
            found (saml/find-saml-identity ds idp-entity-id "bob@example.com")]
        (is (some? found) "Should have created SAML identity link")
        (is (= (:id user) (:user-id found)))))))

;; ---------------------------------------------------------------------------
;; Tests: view helpers
;; ---------------------------------------------------------------------------

(deftest saml-enabled-test
  (testing "saml-enabled? returns true when both feature flag and config are enabled"
    (is (true? (saml/saml-enabled? test-saml-config))))
  (testing "saml-enabled? returns false when disabled"
    (is (false? (saml/saml-enabled? disabled-saml-config))))
  (testing "saml-enabled? returns false when feature flag is off but config says enabled"
    (is (false? (saml/saml-enabled? {:feature-flags {:saml false}
                                      :saml {:enabled true}})))))

(deftest saml-provider-name-test
  (testing "saml-provider-name returns configured name"
    (is (= "Test SAML" (saml/saml-provider-name test-saml-config))))
  (testing "saml-provider-name falls back to SAML SSO"
    (is (= "SAML SSO" (saml/saml-provider-name {:saml {}})))))

;; ---------------------------------------------------------------------------
;; Tests: Ring handler — saml-login-handler
;; ---------------------------------------------------------------------------

(deftest saml-login-handler-redirect-test
  (testing "saml-login-handler returns 302 redirect to IdP"
    (let [system (test-system)
          handler (saml/saml-login-handler system)
          req {:session {} :scheme :https :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 302 (:status resp)))
      (is (.contains (get-in resp [:headers "Location"]) "SAMLRequest="))
      (is (some? (get-in resp [:session :saml-request-id])))
      (is (some? (get-in resp [:session :saml-relay-state]))))))

(deftest saml-login-handler-disabled-returns-400-test
  (testing "saml-login-handler returns 400 when SAML is disabled"
    (let [system (test-system disabled-saml-config)
          handler (saml/saml-login-handler system)
          req {:session {} :scheme :https :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Tests: Ring handler — saml-acs-handler (with mocked validate-saml-response)
;; ---------------------------------------------------------------------------

(deftest saml-acs-handler-valid-response-test
  (testing "saml-acs-handler creates user and sets session on valid response"
    (let [system (test-system)
          handler (saml/saml-acs-handler system)]
      ;; Mock validate-saml-response to return a valid result
      (with-redefs [saml/validate-saml-response
                    (fn [_config _resp _req-id]
                      {:success true
                       :name-id "charlie@example.com"
                       :name-id-format "urn:oasis:names:tc:SAML:2.0:nameid-format:emailAddress"
                       :attributes {"email" ["charlie@example.com"]
                                    "displayName" ["Charlie"]
                                    "Role" ["Admin"]}})]
        (let [req {:session {:saml-request-id "_req-123"
                             :saml-relay-state "relay-abc"}
                   :form-params {"SAMLResponse" "mock-base64-encoded"}
                   :scheme :https
                   :headers {"host" "chengis.example.com"}}
              resp (handler req)]
          (is (= 303 (:status resp)))
          (is (= "/" (get-in resp [:headers "Location"])))
          (is (some? (get-in resp [:session :user])))
          (is (= "charlie" (get-in resp [:session :user :username])))
          (is (= :admin (get-in resp [:session :user :role])))
          ;; Session should be cleaned up
          (is (nil? (get-in resp [:session :saml-request-id])))
          (is (nil? (get-in resp [:session :saml-relay-state]))))))))

(deftest saml-acs-handler-invalid-response-test
  (testing "saml-acs-handler redirects to login on validation failure"
    (let [system (test-system)
          handler (saml/saml-acs-handler system)]
      (with-redefs [saml/validate-saml-response
                    (fn [_config _resp _req-id]
                      {:success false
                       :error "Signature verification failed"})]
        (let [req {:session {:saml-request-id "_req-123"}
                   :form-params {"SAMLResponse" "mock-base64-encoded"}
                   :scheme :https
                   :headers {"host" "chengis.example.com"}}
              resp (handler req)]
          (is (= 303 (:status resp)))
          (is (.contains (get-in resp [:headers "Location"]) "/login?error=")))))))

(deftest saml-acs-handler-disabled-returns-400-test
  (testing "saml-acs-handler returns 400 when SAML is disabled"
    (let [system (test-system disabled-saml-config)
          handler (saml/saml-acs-handler system)
          req {:session {}
               :form-params {"SAMLResponse" "mock-base64-encoded"}
               :scheme :https
               :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 400 (:status resp))))))

(deftest saml-acs-handler-missing-response-test
  (testing "saml-acs-handler redirects when SAMLResponse is missing"
    (let [system (test-system)
          handler (saml/saml-acs-handler system)
          req {:session {:saml-request-id "_req-123"}
               :form-params {}
               :scheme :https
               :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 303 (:status resp)))
      (is (.contains (get-in resp [:headers "Location"]) "/login?error=")))))

;; ---------------------------------------------------------------------------
;; Tests: saml-metadata-handler
;; ---------------------------------------------------------------------------

(deftest saml-metadata-handler-returns-xml-test
  (testing "saml-metadata-handler returns SP metadata XML"
    (let [system (test-system)
          handler (saml/saml-metadata-handler system)
          req {:scheme :https :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 200 (:status resp)))
      (is (= "application/samlmetadata+xml" (get-in resp [:headers "Content-Type"])))
      (is (.contains (:body resp) "EntityDescriptor"))
      (is (.contains (:body resp) "https://chengis.example.com/auth/saml/metadata"))
      (is (.contains (:body resp) "AssertionConsumerService")))))

(deftest saml-metadata-handler-disabled-returns-400-test
  (testing "saml-metadata-handler returns 400 when SAML is disabled"
    (let [system (test-system disabled-saml-config)
          handler (saml/saml-metadata-handler system)
          req {:scheme :https :headers {"host" "chengis.example.com"}}
          resp (handler req)]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Phase 3e: SAML certificate parsing and security path tests
;; ---------------------------------------------------------------------------

(deftest parse-x509-certificate-nil-returns-nil-test
  (testing "nil certificate string returns nil"
    (is (nil? (#'saml/parse-x509-certificate nil)))))

(deftest parse-x509-certificate-blank-returns-nil-test
  (testing "blank certificate string returns nil"
    (is (nil? (#'saml/parse-x509-certificate "")))
    (is (nil? (#'saml/parse-x509-certificate "   ")))))

(deftest parse-x509-certificate-invalid-base64-returns-nil-test
  (testing "invalid Base64 content returns nil (exception caught)"
    (is (nil? (#'saml/parse-x509-certificate
                "-----BEGIN CERTIFICATE-----\nNOT-VALID-BASE64!@#$\n-----END CERTIFICATE-----")))))

(deftest parse-x509-certificate-no-headers-returns-nil-test
  (testing "garbage without PEM headers returns nil"
    (is (nil? (#'saml/parse-x509-certificate "just-some-random-text")))))

(deftest generate-authn-request-disabled-returns-nil-test
  (testing "SAML disabled returns nil from generate-authn-request"
    (is (nil? (#'saml/generate-authn-request {:saml {:enabled false}})))))

(deftest generate-authn-request-enabled-returns-map-test
  (testing "SAML enabled returns request with url, relay-state, request-id"
    (let [config {:saml {:enabled true
                          :sp-entity-id "chengis"
                          :idp-sso-url "https://idp.example.com/sso"
                          :acs-url "https://app.example.com/acs"}}
          result (#'saml/generate-authn-request config)]
      (is (some? result))
      (is (some? (:url result)))
      (is (some? (:relay-state result)))
      (is (some? (:request-id result)))
      (is (clojure.string/starts-with? (:url result) "https://idp.example.com/sso?")))))

(deftest resolve-saml-role-blank-attribute-returns-default-test
  (testing "blank role-attribute returns default role"
    (let [result (saml/resolve-saml-role
                   {"role" "admin"}
                   {:saml {:role-attribute ""
                           :role-mapping {"admin" "admin"}
                           :default-role "viewer"}})]
      (is (= "viewer" result)))))

(deftest resolve-saml-role-nil-attribute-value-returns-default-test
  (testing "nil attribute value returns default role"
    (let [result (saml/resolve-saml-role
                   {"other-attr" "admin"}
                   {:saml {:role-attribute "role"
                           :role-mapping {"admin" "admin"}
                           :default-role "viewer"}})]
      (is (= "viewer" result)))))

(deftest resolve-saml-role-sequential-first-match-test
  (testing "sequential attribute values uses first matching role"
    (let [result (saml/resolve-saml-role
                   {"groups" ["ci-user" "ci-admin" "ci-dev"]}
                   {:saml {:role-attribute "groups"
                           :role-mapping {"ci-admin" "admin" "ci-dev" "developer"}
                           :default-role "viewer"}})]
      (is (= "admin" result)))))
