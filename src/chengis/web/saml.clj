(ns chengis.web.saml
  "SAML 2.0 Service Provider integration.
   Implements SAML AuthnRequest generation, Response validation, user provisioning,
   and Ring handlers for SSO login, ACS (Assertion Consumer Service), and SP metadata.

   Security features:
   - XML Signature verification via javax.xml.crypto.dsig
   - Audience restriction validation
   - Timestamp (NotBefore/NotOnOrAfter) validation with clock skew tolerance
   - InResponseTo validation to prevent replay attacks
   - Deflate+Base64+URL-encode for AuthnRequest (HTTP-Redirect binding)

   Configuration:
     :saml {:enabled true
            :sp-entity-id \"https://chengis.example.com/auth/saml/metadata\"
            :idp-metadata-url nil
            :idp-sso-url \"https://idp.example.com/saml/sso\"
            :idp-certificate \"-----BEGIN CERTIFICATE-----\\n...\\n-----END CERTIFICATE-----\"
            :acs-url nil  ;; Optional: explicit ACS URL (recommended behind proxy)
            :role-attribute \"Role\"
            :role-mapping {\"Admin\" \"admin\"
                           \"Developer\" \"developer\"}
            :default-role \"viewer\"
            :auto-create-users true
            :provider-name \"SAML SSO\"}"
  (:require [chengis.db.user-store :as user-store]
            [chengis.util :as util]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream StringReader]
           [java.net URLEncoder]
           [java.security SecureRandom]
           [java.security.cert CertificateFactory]
           [java.time Instant]
           [java.util Base64 UUID]
           [java.util.zip Deflater DeflaterOutputStream]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.xml.sax InputSource]))

;; ---------------------------------------------------------------------------
;; Certificate parsing
;; ---------------------------------------------------------------------------

(defn- parse-x509-certificate
  "Parse a PEM-encoded X.509 certificate string into a java.security.cert.X509Certificate.
   Strips PEM header/footer and whitespace before decoding."
  [^String pem-str]
  (when (and pem-str (not (str/blank? pem-str)))
    (try
      (let [cleaned (-> pem-str
                        (str/replace "-----BEGIN CERTIFICATE-----" "")
                        (str/replace "-----END CERTIFICATE-----" "")
                        (str/replace #"\s+" ""))
            der-bytes (.decode (Base64/getDecoder) cleaned)
            cf (CertificateFactory/getInstance "X.509")]
        (.generateCertificate cf (ByteArrayInputStream. der-bytes)))
      (catch Exception e
        (log/error "Failed to parse IdP certificate:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; AuthnRequest generation (HTTP-Redirect binding)
;; ---------------------------------------------------------------------------

(defn- deflate-and-encode
  "Deflate a string using raw DEFLATE (no zlib header), then Base64-encode.
   Per SAML HTTP-Redirect binding specification."
  [^String s]
  (let [input (.getBytes s "UTF-8")
        baos (ByteArrayOutputStream.)
        deflater (Deflater. Deflater/DEFLATED true)] ;; raw deflate, no zlib wrapper
    (with-open [dos (DeflaterOutputStream. baos deflater)]
      (.write dos input))
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))

(defn generate-authn-request
  "Build a SAML AuthnRequest and return {:url :relay-state :request-id}.
   The AuthnRequest XML is deflated, base64-encoded, and URL-encoded
   per the SAML HTTP-Redirect binding spec."
  [config]
  (when (get-in config [:saml :enabled])
    (let [request-id (str "_" (UUID/randomUUID))
          sp-entity-id (get-in config [:saml :sp-entity-id])
          idp-sso-url (get-in config [:saml :idp-sso-url])
          acs-url (get-in config [:saml :acs-url])
          issue-instant (.toString (Instant/now))
          relay-state (str (UUID/randomUUID))

          ;; Build SAML AuthnRequest XML
          authn-xml (str
                      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                      "<samlp:AuthnRequest"
                      " xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
                      " xmlns:saml=\"urn:oasis:names:tc:SAML:2.0:assertion\""
                      " ID=\"" request-id "\""
                      " Version=\"2.0\""
                      " IssueInstant=\"" issue-instant "\""
                      " Destination=\"" idp-sso-url "\""
                      " AssertionConsumerServiceURL=\"" acs-url "\""
                      " ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\""
                      ">"
                      "<saml:Issuer>" sp-entity-id "</saml:Issuer>"
                      "<samlp:NameIDPolicy"
                      " Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified\""
                      " AllowCreate=\"true\"/>"
                      "</samlp:AuthnRequest>")

          ;; Deflate + Base64 + URL-encode
          encoded (deflate-and-encode authn-xml)
          url-encoded (URLEncoder/encode encoded "UTF-8")
          relay-encoded (URLEncoder/encode relay-state "UTF-8")
          redirect-url (str idp-sso-url
                            (if (str/includes? idp-sso-url "?") "&" "?")
                            "SAMLRequest=" url-encoded
                            "&RelayState=" relay-encoded)]
      {:url redirect-url
       :relay-state relay-state
       :request-id request-id})))

;; ---------------------------------------------------------------------------
;; SAML Response validation (manual XML parsing + signature verification)
;; ---------------------------------------------------------------------------

(defn- parse-xml
  "Parse an XML string into a org.w3c.dom.Document."
  [^String xml-str]
  (let [factory (doto (DocumentBuilderFactory/newInstance)
                  (.setNamespaceAware true))
        builder (.newDocumentBuilder factory)
        is (InputSource. (StringReader. xml-str))]
    (.parse builder is)))

(defn- get-elements-by-tag-ns
  "Get all elements with a given namespace URI and local name from a document or element."
  [parent ns-uri local-name]
  (let [node-list (.getElementsByTagNameNS parent ns-uri local-name)]
    (mapv #(.item node-list %) (range (.getLength node-list)))))

(defn- get-element-text
  "Get the text content of the first child element matching the given namespace and local name."
  [parent ns-uri local-name]
  (when-let [elems (seq (get-elements-by-tag-ns parent ns-uri local-name))]
    (.getTextContent (first elems))))

(defn- extract-attributes
  "Extract SAML attributes from an AttributeStatement element.
   Returns a map of attribute-name -> [values]."
  [assertion]
  (let [saml-ns "urn:oasis:names:tc:SAML:2.0:assertion"
        attr-stmts (get-elements-by-tag-ns assertion saml-ns "AttributeStatement")]
    (when (seq attr-stmts)
      (let [attrs (get-elements-by-tag-ns (first attr-stmts) saml-ns "Attribute")]
        (reduce
          (fn [m attr-elem]
            (let [attr-name (.getAttribute attr-elem "Name")
                  values (get-elements-by-tag-ns attr-elem saml-ns "AttributeValue")
                  vals (mapv #(.getTextContent %) values)]
              (assoc m attr-name vals)))
          {}
          attrs)))))

(defn- verify-xml-signature
  "Verify the XML digital signature in the SAML Response using the IdP's X.509 certificate.
   Returns true if the signature is valid, false otherwise."
  [^org.w3c.dom.Document doc ^java.security.cert.X509Certificate cert]
  (try
    (let [dsig-ns "http://www.w3.org/2000/09/xmldsig#"
          sig-elems (get-elements-by-tag-ns doc dsig-ns "Signature")]
      (if (empty? sig-elems)
        (do (log/warn "No XML Signature found in SAML Response")
            false)
        (let [sig-elem (first sig-elems)
              fac (javax.xml.crypto.dsig.XMLSignatureFactory/getInstance "DOM")
              dom-vc (javax.xml.crypto.dsig.dom.DOMValidateContext.
                       (.getPublicKey cert) sig-elem)]
          ;; Set the ID attribute resolver for SAML
          (.setIdAttributeNS
            (.getDocumentElement doc)
            nil "ID" true)
          ;; Also set ID on Assertion elements if present
          (doseq [assertion (get-elements-by-tag-ns doc
                              "urn:oasis:names:tc:SAML:2.0:assertion" "Assertion")]
            (.setIdAttributeNS assertion nil "ID" true))
          (let [signature (.unmarshalXMLSignature fac dom-vc)
                valid? (.validate signature dom-vc)]
            (when-not valid?
              (log/warn "XML Signature validation failed"))
            valid?))))
    (catch Exception e
      (log/error "XML Signature verification error:" (.getMessage e))
      false)))

(defn- check-timestamps
  "Check NotBefore and NotOnOrAfter conditions on the SAML assertion.
   Allows 5 minutes of clock skew. Returns true if valid."
  [conditions-elem]
  (if (nil? conditions-elem)
    true ;; No conditions element means no time constraints
    (let [now (Instant/now)
          clock-skew-secs 300 ;; 5 minutes
          not-before-str (.getAttribute conditions-elem "NotBefore")
          not-on-or-after-str (.getAttribute conditions-elem "NotOnOrAfter")]
      (and
        ;; Check NotBefore (if present)
        (or (str/blank? not-before-str)
            (let [not-before (.minusSeconds (Instant/parse not-before-str) clock-skew-secs)]
              (not (.isBefore now not-before))))
        ;; Check NotOnOrAfter (if present)
        (or (str/blank? not-on-or-after-str)
            (let [not-on-or-after (.plusSeconds (Instant/parse not-on-or-after-str) clock-skew-secs)]
              (.isBefore now not-on-or-after)))))))

(defn- check-audience
  "Check AudienceRestriction in the SAML assertion matches our SP entity ID.
   Returns true if valid (or if no audience restriction is present)."
  [conditions-elem sp-entity-id]
  (if (nil? conditions-elem)
    true
    (let [saml-ns "urn:oasis:names:tc:SAML:2.0:assertion"
          restrictions (get-elements-by-tag-ns conditions-elem saml-ns "AudienceRestriction")]
      (if (empty? restrictions)
        true
        (let [audiences (get-elements-by-tag-ns (first restrictions) saml-ns "Audience")
              audience-values (mapv #(.getTextContent %) audiences)]
          (if (empty? audience-values)
            true
            (boolean (some #(= % sp-entity-id) audience-values))))))))

(defn validate-saml-response
  "Parse and validate a SAML Response from Base64-encoded form POST data.
   Verifies: XML signature, audience, timestamps, InResponseTo.
   Returns {:success true :name-id :name-id-format :attributes}
   or {:success false :error \"...\"}."
  [config saml-response-b64 expected-request-id]
  (try
    (let [decoded (String. (.decode (Base64/getDecoder) saml-response-b64) "UTF-8")
          doc (parse-xml decoded)
          saml-ns "urn:oasis:names:tc:SAML:2.0:assertion"
          samlp-ns "urn:oasis:names:tc:SAML:2.0:protocol"
          sp-entity-id (get-in config [:saml :sp-entity-id])
          idp-cert-pem (get-in config [:saml :idp-certificate])

          ;; Check top-level Status
          response-elem (.getDocumentElement doc)
          status-codes (get-elements-by-tag-ns response-elem samlp-ns "StatusCode")
          status-value (when (seq status-codes)
                         (.getAttribute (first status-codes) "Value"))]

      ;; Verify status is Success
      (when (and status-value
                 (not (str/ends-with? status-value ":Success")))
        (throw (ex-info (str "SAML Response status: " status-value) {:status status-value})))

      ;; Verify InResponseTo
      (when expected-request-id
        (let [in-response-to (.getAttribute response-elem "InResponseTo")]
          (when (and (not (str/blank? in-response-to))
                     (not= in-response-to expected-request-id))
            (throw (ex-info "InResponseTo mismatch"
                            {:expected expected-request-id
                             :actual in-response-to})))))

      ;; Verify XML signature if certificate is configured
      (when idp-cert-pem
        (let [cert (parse-x509-certificate idp-cert-pem)]
          (when (and cert (not (verify-xml-signature doc cert)))
            (throw (ex-info "SAML Response XML signature verification failed" {})))))

      ;; Extract assertion
      (let [assertions (get-elements-by-tag-ns doc saml-ns "Assertion")]
        (if (empty? assertions)
          {:success false :error "No Assertion found in SAML Response"}
          (let [assertion (first assertions)

                ;; Validate conditions (timestamps + audience)
                conditions-elems (get-elements-by-tag-ns assertion saml-ns "Conditions")
                conditions-elem (first conditions-elems)]

            ;; Check timestamps
            (when (and conditions-elem (not (check-timestamps conditions-elem)))
              (throw (ex-info "SAML assertion timestamp validation failed" {})))

            ;; Check audience
            (when (and conditions-elem (not (check-audience conditions-elem sp-entity-id)))
              (throw (ex-info "SAML audience restriction mismatch"
                              {:expected sp-entity-id})))

            ;; Extract NameID
            (let [subject-elems (get-elements-by-tag-ns assertion saml-ns "Subject")
                  name-id-elems (when (seq subject-elems)
                                  (get-elements-by-tag-ns (first subject-elems) saml-ns "NameID"))
                  name-id-elem (first name-id-elems)
                  name-id (when name-id-elem (.getTextContent name-id-elem))
                  name-id-format (when name-id-elem (.getAttribute name-id-elem "Format"))

                  ;; Extract attributes
                  attributes (extract-attributes assertion)]

              (if (str/blank? name-id)
                {:success false :error "No NameID found in SAML assertion"}
                {:success true
                 :name-id name-id
                 :name-id-format name-id-format
                 :attributes attributes}))))))
    (catch Exception e
      (log/error "SAML Response validation failed:" (.getMessage e))
      {:success false :error (str "SAML validation error: " (.getMessage e))})))

;; ---------------------------------------------------------------------------
;; Role mapping
;; ---------------------------------------------------------------------------

(defn resolve-saml-role
  "Map SAML attributes to a Chengis role using the configured role-attribute
   and role-mapping. Falls back to :default-role (default \"viewer\").
   Same pattern as oidc/resolve-role."
  [attributes config]
  (let [role-attribute (get-in config [:saml :role-attribute])
        role-mapping (get-in config [:saml :role-mapping] {})
        default-role (get-in config [:saml :default-role] "viewer")]
    (if (str/blank? role-attribute)
      default-role
      (let [attr-values (get attributes role-attribute)]
        (when (nil? attr-values)
          (log/warn "SAML role attribute" role-attribute "not found in assertion attributes"
                    "-- using default role:" default-role))
        (cond
          ;; Attribute has multiple values -- find the first match
          (sequential? attr-values)
          (or (some (fn [v] (get role-mapping (str v))) attr-values)
              default-role)

          ;; Single value
          (some? attr-values)
          (or (get role-mapping (str attr-values))
              default-role)

          :else
          default-role)))))

;; ---------------------------------------------------------------------------
;; SAML identity store
;; ---------------------------------------------------------------------------

(defn find-saml-identity
  "Look up a Chengis user by IdP entity ID + NameID. Returns user map or nil.
   Same pattern as oidc/find-oidc-identity."
  [ds idp-entity-id name-id]
  (when-let [identity (jdbc/execute-one! ds
                        (sql/format {:select [:si/user-id :u/username :u/role :u/active :u/session-version]
                                     :from [[:saml-identities :si]]
                                     :join [[:users :u] [:= :si/user-id :u/id]]
                                     :where [:and
                                             [:= :si/idp-entity-id idp-entity-id]
                                             [:= :si/name-id name-id]]})
                        {:builder-fn rs/as-unqualified-kebab-maps})]
    ;; Update last_login_at
    (jdbc/execute-one! ds
      (sql/format {:update :saml-identities
                   :set {:last-login-at [:raw "CURRENT_TIMESTAMP"]}
                   :where [:and [:= :idp-entity-id idp-entity-id] [:= :name-id name-id]]}))
    identity))

(defn create-saml-identity!
  "Create a SAML identity record linking a Chengis user to a SAML NameID."
  [ds {:keys [user-id idp-entity-id name-id name-id-format email display-name attributes-json]}]
  (jdbc/execute-one! ds
    (sql/format {:insert-into :saml-identities
                 :values [{:id (util/generate-id)
                           :user-id user-id
                           :idp-entity-id idp-entity-id
                           :name-id name-id
                           :name-id-format name-id-format
                           :email email
                           :display-name display-name
                           :attributes-json attributes-json}]})))

(defn provision-saml-user!
  "JIT (Just-In-Time) provision a new Chengis user from SAML assertion data.
   Creates the user and links the SAML identity.
   Same pattern as oidc/provision-oidc-user!"
  [ds config name-id email display-name attributes]
  (let [idp-entity-id (or (get-in config [:saml :idp-sso-url]) "unknown-idp")
        ;; Derive username: email prefix, or name-id, or fallback
        username (or (when email (first (str/split email #"@")))
                     (when (and name-id (not (str/blank? name-id)))
                       (str "saml-" (subs name-id 0 (min 12 (count name-id)))))
                     (str "saml-" (subs (str (UUID/randomUUID)) 0 8)))
        ;; Ensure unique username
        username (loop [candidate username n 0]
                   (if (user-store/get-user-by-username ds candidate)
                     (recur (str username "-" (inc n)) (inc n))
                     candidate))
        role (resolve-saml-role attributes config)
        display-name (or display-name username)
        ;; Create user with a random password (SAML users don't use passwords)
        random-pw (str (UUID/randomUUID))
        user (user-store/create-user! ds {:username username
                                           :password random-pw
                                           :role role})]
    ;; Link SAML identity
    (create-saml-identity! ds {:user-id (:id user)
                                :idp-entity-id idp-entity-id
                                :name-id name-id
                                :name-id-format nil
                                :email email
                                :display-name display-name
                                :attributes-json (when attributes
                                                   (json/write-str attributes))})
    (log/info "SAML JIT provisioned user:" username "role:" role "from" idp-entity-id)
    user))

;; ---------------------------------------------------------------------------
;; ACS URL resolution
;; ---------------------------------------------------------------------------

(defn- resolve-acs-url
  "Resolve the SAML ACS URL. Uses explicit config if set,
   otherwise reconstructs from request headers."
  [config req]
  (or (get-in config [:saml :acs-url])
      (let [scheme (or (get-in req [:headers "x-forwarded-proto"]) (name (:scheme req "http")))
            host (get-in req [:headers "host"] "localhost:8080")]
        (str scheme "://" host "/auth/saml/acs"))))

;; ---------------------------------------------------------------------------
;; Ring handlers
;; ---------------------------------------------------------------------------

(defn saml-login-handler
  "Redirect the user to the SAML IdP's SSO page with an AuthnRequest.
   Stores the request-id and relay-state in the session for validation."
  [system]
  (fn [req]
    (let [config (:config system)]
      (if-not (and (feature-flags/enabled? config :saml)
                   (get-in config [:saml :enabled]))
        {:status 400
         :headers {"Content-Type" "text/html"}
         :body "<h1>SAML Not Configured</h1><p>SAML authentication is not enabled.</p>"}
        (let [;; Ensure ACS URL is set in config for AuthnRequest generation
              acs-url (resolve-acs-url config req)
              config-with-acs (assoc-in config [:saml :acs-url] acs-url)
              auth-info (generate-authn-request config-with-acs)]
          (if auth-info
            {:status 302
             :headers {"Location" (:url auth-info)}
             :session (assoc (:session req)
                        :saml-request-id (:request-id auth-info)
                        :saml-relay-state (:relay-state auth-info))}
            {:status 400
             :headers {"Content-Type" "text/html"}
             :body "<h1>SAML Error</h1><p>Failed to generate SAML AuthnRequest.</p>"}))))))

(defn saml-acs-handler
  "Handle the SAML ACS (Assertion Consumer Service) POST callback.
   Validates the SAML Response, finds or creates the user, and sets the session."
  [system]
  (fn [req]
    (let [ds (:db system)
          config (:config system)]
      (if-not (and (feature-flags/enabled? config :saml)
                   (get-in config [:saml :enabled]))
        {:status 400
         :headers {"Content-Type" "text/html"}
         :body "<h1>SAML Not Configured</h1><p>SAML authentication is not enabled.</p>"}
        (let [params (or (:form-params req) (:params req) {})
              saml-response (or (get params "SAMLResponse") (get params :SAMLResponse))
              expected-request-id (get-in req [:session :saml-request-id])
              idp-entity-id (or (get-in config [:saml :idp-sso-url]) "unknown-idp")]
          (if (str/blank? saml-response)
            {:status 303
             :headers {"Location" "/login?error=No+SAML+response+received"}
             :session (-> (:session req)
                          (dissoc :saml-request-id :saml-relay-state))}
            (let [result (validate-saml-response config saml-response expected-request-id)]
              (if-not (:success result)
                ;; Validation failed
                {:status 303
                 :headers {"Location" (str "/login?error="
                                           (URLEncoder/encode (or (:error result)
                                                                   "SAML authentication failed")
                                                              "UTF-8"))}
                 :session (-> (:session req)
                              (dissoc :saml-request-id :saml-relay-state))}
                ;; Validation succeeded -- find or create user
                (let [{:keys [name-id name-id-format attributes]} result
                      email (or (first (get attributes "email"))
                                (first (get attributes "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"))
                                (when (and name-id (str/includes? name-id "@")) name-id))
                      display-name (or (first (get attributes "displayName"))
                                       (first (get attributes "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"))
                                       email
                                       name-id)
                      existing (find-saml-identity ds idp-entity-id name-id)]
                  (cond
                    ;; Existing SAML user -- check active status
                    (and existing (some? (:active existing)) (zero? (:active existing)))
                    {:status 303
                     :headers {"Location" (str "/login?error="
                                               (URLEncoder/encode "Account is deactivated" "UTF-8"))}
                     :session (-> (:session req)
                                  (dissoc :saml-request-id :saml-relay-state))}

                    ;; Existing SAML user -- log in
                    existing
                    {:status 303
                     :headers {"Location" "/"}
                     :session (-> (:session req)
                                  (assoc :user {:id (:user-id existing)
                                                :username (:username existing)
                                                :role (keyword (:role existing))
                                                :session-version (or (:session-version existing) 0)})
                                  (dissoc :saml-request-id :saml-relay-state))}

                    ;; New user -- JIT provision if allowed
                    (get-in config [:saml :auto-create-users] true)
                    (let [user (provision-saml-user! ds config name-id email display-name attributes)]
                      {:status 303
                       :headers {"Location" "/"}
                       :session (-> (:session req)
                                    (assoc :user {:id (:id user)
                                                  :username (:username user)
                                                  :role (keyword (:role user))
                                                  :session-version 0})
                                    (dissoc :saml-request-id :saml-relay-state))})

                    ;; Auto-create disabled, no matching identity
                    :else
                    {:status 303
                     :headers {"Location" (str "/login?error="
                                               (URLEncoder/encode
                                                 "No account linked to this identity. Contact your administrator."
                                                 "UTF-8"))}
                     :session (-> (:session req)
                                  (dissoc :saml-request-id :saml-relay-state))}))))))))))

(defn saml-metadata-handler
  "Serve the SAML Service Provider metadata XML.
   Returns SP entity ID and ACS endpoint for IdP configuration."
  [system]
  (fn [req]
    (let [config (:config system)]
      (if-not (and (feature-flags/enabled? config :saml)
                   (get-in config [:saml :enabled]))
        {:status 400
         :headers {"Content-Type" "text/html"}
         :body "<h1>SAML Not Configured</h1><p>SAML authentication is not enabled.</p>"}
        (let [sp-entity-id (get-in config [:saml :sp-entity-id])
              acs-url (resolve-acs-url config req)
              metadata-xml (str
                             "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                             "<md:EntityDescriptor"
                             " xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\""
                             " entityID=\"" sp-entity-id "\">"
                             "<md:SPSSODescriptor"
                             " AuthnRequestsSigned=\"false\""
                             " WantAssertionsSigned=\"true\""
                             " protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
                             "<md:NameIDFormat>urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified</md:NameIDFormat>"
                             "<md:AssertionConsumerService"
                             " Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\""
                             " Location=\"" acs-url "\""
                             " index=\"0\""
                             " isDefault=\"true\"/>"
                             "</md:SPSSODescriptor>"
                             "</md:EntityDescriptor>")]
          {:status 200
           :headers {"Content-Type" "application/samlmetadata+xml"
                     "Cache-Control" "public, max-age=86400"}
           :body metadata-xml})))))

;; ---------------------------------------------------------------------------
;; View helpers
;; ---------------------------------------------------------------------------

(defn saml-enabled?
  "Check if SAML is enabled in the configuration (both feature flag and config key)."
  [config]
  (boolean (and (feature-flags/enabled? config :saml)
                (get-in config [:saml :enabled]))))

(defn saml-provider-name
  "Get a display name for the SAML provider.
   Uses the configured :provider-name or falls back to \"SAML SSO\"."
  [config]
  (or (get-in config [:saml :provider-name])
      "SAML SSO"))
