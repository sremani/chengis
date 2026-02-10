(ns chengis.web.ldap
  "LDAP/Active Directory authentication integration.
   Supports bind authentication, group-based role mapping,
   and JIT user provisioning. Feature-flag gated via :ldap.

   Configuration:
     :ldap {:enabled true
            :host \"ldap.example.com\"
            :port 389
            :use-ssl false
            :bind-dn \"cn=readonly,dc=example,dc=com\"
            :bind-password \"secret\"
            :user-base-dn \"ou=people,dc=example,dc=com\"
            :user-filter \"(uid={0})\"
            :username-attribute \"uid\"
            :email-attribute \"mail\"
            :display-name-attribute \"cn\"
            :group-base-dn \"ou=groups,dc=example,dc=com\"
            :group-filter \"(member={0})\"
            :role-mapping {\"cn=admins,ou=groups,dc=example,dc=com\" \"admin\"
                           \"cn=developers,ou=groups,dc=example,dc=com\" \"developer\"}
            :default-role \"viewer\"
            :auto-create-users true
            :sync-interval-minutes 60}"
  (:require [chengis.db.user-store :as user-store]
            [chengis.util :as util]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log])
  (:import [com.unboundid.ldap.sdk LDAPConnection LDAPSearchException
            SearchScope SearchRequest SearchResultEntry Filter
            LDAPConnectionOptions LDAPException]
           [com.unboundid.ldap.sdk.extensions StartTLSExtendedRequest]
           [com.unboundid.util.ssl SSLUtil TrustAllTrustManager]))

;; ---------------------------------------------------------------------------
;; LDAP Connection
;; ---------------------------------------------------------------------------

(defn create-connection
  "Create an LDAPConnection with timeout and optional SSL/TLS.
   Uses the service account bind-dn/bind-password for search operations.
   Returns the connected LDAPConnection or nil on failure."
  [config]
  (let [ldap-cfg (:ldap config)
        host (:host ldap-cfg "localhost")
        port (:port ldap-cfg 389)
        use-ssl (:use-ssl ldap-cfg false)
        bind-dn (:bind-dn ldap-cfg)
        bind-password (:bind-password ldap-cfg)
        options (doto (LDAPConnectionOptions.)
                  (.setConnectTimeoutMillis 10000)
                  (.setResponseTimeoutMillis 10000))]
    (try
      (let [conn (if use-ssl
                   (let [ssl-util (SSLUtil. (TrustAllTrustManager.))
                         ssl-factory (.createSSLSocketFactory ssl-util)]
                     (LDAPConnection. ssl-factory options host port))
                   (LDAPConnection. options host port))]
        ;; Bind with service account
        (when (and bind-dn bind-password)
          (.bind conn bind-dn bind-password))
        (log/debug "LDAP connection established to" host ":" port)
        conn)
      (catch LDAPException e
        (log/error "Failed to connect to LDAP server" host ":" port "-" (.getMessage e))
        nil)
      (catch Exception e
        (log/error "Unexpected error connecting to LDAP:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; User Search
;; ---------------------------------------------------------------------------

(defn search-user
  "Search for a user by username using the configured user-filter and user-base-dn.
   The {0} placeholder in the filter is replaced with the escaped username.
   Returns a SearchResultEntry or nil if not found."
  [^LDAPConnection conn config username]
  (let [ldap-cfg (:ldap config)
        user-base-dn (:user-base-dn ldap-cfg)
        user-filter-template (:user-filter ldap-cfg "(uid={0})")
        ;; Escape special LDAP characters in username
        escaped-username (Filter/encodeValue username)
        filter-str (str/replace user-filter-template "{0}" escaped-username)]
    (try
      (let [search-request (SearchRequest. user-base-dn
                                           SearchScope/SUB
                                           filter-str
                                           (into-array String ["*"]))
            result (.search conn search-request)
            entries (.getSearchEntries result)]
        (if (seq entries)
          (do
            (when (> (count entries) 1)
              (log/warn "LDAP search returned multiple entries for username:" username
                        "- using first match"))
            (first entries))
          (do
            (log/debug "LDAP user not found:" username)
            nil)))
      (catch LDAPSearchException e
        (log/error "LDAP search failed for username:" username "-" (.getMessage e))
        nil)
      (catch Exception e
        (log/error "Unexpected error searching LDAP:" (.getMessage e))
        nil))))

;; ---------------------------------------------------------------------------
;; User Bind (Authentication)
;; ---------------------------------------------------------------------------

(defn bind-as-user
  "Attempt an LDAP bind with the user's own credentials.
   Creates a NEW connection for the bind (does not reuse search connection).
   Returns true if bind succeeds, false otherwise."
  [config user-dn password]
  (let [ldap-cfg (:ldap config)
        host (:host ldap-cfg "localhost")
        port (:port ldap-cfg 389)
        use-ssl (:use-ssl ldap-cfg false)
        options (doto (LDAPConnectionOptions.)
                  (.setConnectTimeoutMillis 10000)
                  (.setResponseTimeoutMillis 10000))]
    (let [conn (atom nil)]
      (try
        (let [c (if use-ssl
                  (let [ssl-util (SSLUtil. (TrustAllTrustManager.))
                        ssl-factory (.createSSLSocketFactory ssl-util)]
                    (LDAPConnection. ssl-factory options host port))
                  (LDAPConnection. options host port))]
          (reset! conn c)
          (.bind c user-dn password)
          (log/debug "LDAP bind succeeded for DN:" user-dn)
          true)
        (catch LDAPException e
          (log/warn "LDAP bind failed for DN:" user-dn "-" (.getMessage e))
          false)
        (catch Exception e
          (log/error "Unexpected error during LDAP bind:" (.getMessage e))
          false)
        (finally
          (when-let [c @conn]
            (.close c)))))))

;; ---------------------------------------------------------------------------
;; Group Membership
;; ---------------------------------------------------------------------------

(defn fetch-user-groups
  "Search for groups the user belongs to.
   Uses the configured group-base-dn and group-filter with {0} replaced by user DN.
   Returns a vector of group DN strings."
  [^LDAPConnection conn config user-dn]
  (let [ldap-cfg (:ldap config)
        group-base-dn (:group-base-dn ldap-cfg)
        group-filter-template (:group-filter ldap-cfg "(member={0})")]
    (if-not group-base-dn
      (do (log/debug "No group-base-dn configured, skipping group lookup")
          [])
      (try
        (let [escaped-dn (Filter/encodeValue user-dn)
              filter-str (str/replace group-filter-template "{0}" escaped-dn)
              search-request (SearchRequest. group-base-dn
                                             SearchScope/SUB
                                             filter-str
                                             (into-array String ["dn" "cn"]))
              result (.search conn search-request)
              entries (.getSearchEntries result)]
          (mapv (fn [^SearchResultEntry entry]
                  (.getDN entry))
                entries))
        (catch LDAPSearchException e
          (log/error "LDAP group search failed for DN:" user-dn "-" (.getMessage e))
          [])
        (catch Exception e
          (log/error "Unexpected error fetching LDAP groups:" (.getMessage e))
          [])))))

;; ---------------------------------------------------------------------------
;; Role Resolution
;; ---------------------------------------------------------------------------

(defn resolve-ldap-role
  "Map LDAP group membership to a Chengis role using the configured role-mapping.
   Checks each group DN against the mapping; first match wins.
   Falls back to :default-role (default \"viewer\")."
  [groups config]
  (let [ldap-cfg (:ldap config)
        role-mapping (:role-mapping ldap-cfg {})
        default-role (:default-role ldap-cfg "viewer")]
    (or (some (fn [group-dn]
                ;; Try exact DN match first
                (or (get role-mapping group-dn)
                    ;; Try case-insensitive match
                    (some (fn [[pattern role]]
                            (when (= (str/lower-case group-dn) (str/lower-case pattern))
                              role))
                          role-mapping)))
              groups)
        default-role)))

;; ---------------------------------------------------------------------------
;; User Attribute Extraction
;; ---------------------------------------------------------------------------

(defn extract-user-attributes
  "Extract email and display-name from an LDAP SearchResultEntry
   using the configured attribute names."
  [^SearchResultEntry entry config]
  (let [ldap-cfg (:ldap config)
        email-attr (:email-attribute ldap-cfg "mail")
        display-name-attr (:display-name-attribute ldap-cfg "cn")
        username-attr (:username-attribute ldap-cfg "uid")]
    {:username (.getAttributeValue entry username-attr)
     :email (.getAttributeValue entry email-attr)
     :display-name (.getAttributeValue entry display-name-attr)}))

;; ---------------------------------------------------------------------------
;; LDAP Identity Store (DB operations)
;; ---------------------------------------------------------------------------

(defn find-ldap-identity
  "Look up an existing LDAP identity in the database by server and UID.
   Returns a joined user+identity map or nil."
  [ds ldap-server ldap-uid]
  (when-let [identity (jdbc/execute-one! ds
                        (sql/format {:select [:li/user-id :li/distinguished-name :li/groups-json
                                              :u/username :u/role :u/active :u/session-version]
                                     :from [[:ldap-identities :li]]
                                     :join [[:users :u] [:= :li/user-id :u/id]]
                                     :where [:and
                                             [:= :li/ldap-server ldap-server]
                                             [:= :li/ldap-uid ldap-uid]]})
                        {:builder-fn rs/as-unqualified-kebab-maps})]
    ;; Update last_login_at
    (jdbc/execute-one! ds
      (sql/format {:update :ldap-identities
                   :set {:last-login-at [:raw "CURRENT_TIMESTAMP"]}
                   :where [:and [:= :ldap-server ldap-server] [:= :ldap-uid ldap-uid]]}))
    identity))

(defn create-ldap-identity!
  "Insert an LDAP identity record linking a Chengis user to an LDAP account."
  [ds {:keys [user-id ldap-server distinguished-name ldap-uid email display-name groups-json]}]
  (jdbc/execute-one! ds
    (sql/format {:insert-into :ldap-identities
                 :values [{:id (util/generate-id)
                           :user-id user-id
                           :ldap-server ldap-server
                           :distinguished-name distinguished-name
                           :ldap-uid ldap-uid
                           :email email
                           :display-name display-name
                           :groups-json groups-json}]})))

;; ---------------------------------------------------------------------------
;; JIT User Provisioning
;; ---------------------------------------------------------------------------

(defn provision-ldap-user!
  "JIT (Just-In-Time) provision a new Chengis user from LDAP attributes.
   Creates the user and links the LDAP identity. Same pattern as OIDC's provision-oidc-user!."
  [ds config username email display-name role]
  (let [ldap-cfg (:ldap config)
        ldap-server (str (:host ldap-cfg) ":" (:port ldap-cfg 389))
        ;; Ensure unique username
        username (loop [candidate username n 0]
                   (if (user-store/get-user-by-username ds candidate)
                     (recur (str username "-" (inc n)) (inc n))
                     candidate))
        ;; Create user with a random password (LDAP users don't use local passwords)
        random-pw (str (java.util.UUID/randomUUID))
        user (user-store/create-user! ds {:username username
                                           :password random-pw
                                           :role role})]
    (log/info "LDAP JIT provisioned user:" username "role:" role)
    user))

;; ---------------------------------------------------------------------------
;; Full LDAP Login Flow
;; ---------------------------------------------------------------------------

(defn ldap-login!
  "Full LDAP login flow:
   1. Check feature flag
   2. Connect to LDAP with service account
   3. Search for user by username
   4. Bind as user to verify password
   5. Fetch group membership
   6. Resolve Chengis role from groups
   7. Find or provision local identity
   8. Return {:success true :user user-map} or {:success false :error msg}

   Follows the same return pattern as auth/login!."
  [ds config username password registry lockout-config]
  (if-not (feature-flags/enabled? config :ldap)
    {:success false :error "LDAP authentication is not enabled"}
    (let [ldap-cfg (:ldap config)
          ldap-server (str (:host ldap-cfg) ":" (:port ldap-cfg 389))]
      (let [conn (create-connection config)]
        (if-not conn
          {:success false :error "Unable to connect to LDAP server"}
          (try
            ;; Step 1: Search for user
            (let [entry (search-user conn config username)]
              (if-not entry
                (do (log/warn "LDAP login failed — user not found:" username)
                    {:success false :error "Invalid username or password"})
                ;; Step 2: Extract attributes and bind as user
                (let [user-dn (.getDN entry)
                      attrs (extract-user-attributes entry config)
                      username-attr (:username-attribute ldap-cfg "uid")
                      ldap-uid (or (.getAttributeValue entry username-attr) username)]
                  (if-not (bind-as-user config user-dn password)
                    (do (log/warn "LDAP login failed — bind failed for:" username)
                        {:success false :error "Invalid username or password"})
                    ;; Step 3: Fetch groups and resolve role
                    (let [groups (fetch-user-groups conn config user-dn)
                          role (resolve-ldap-role groups config)
                          email (:email attrs)
                          display-name (or (:display-name attrs) username)
                          groups-json (when (seq groups) (json/write-str groups))
                          ;; Step 4: Find or create identity
                          existing (find-ldap-identity ds ldap-server ldap-uid)]
                      (cond
                        ;; Existing user, deactivated
                        (and existing (some? (:active existing)) (zero? (:active existing)))
                        {:success false :error "Account is deactivated"}

                        ;; Existing user — update groups and log in
                        existing
                        (do
                          ;; Update groups_json on each login
                          (when groups-json
                            (jdbc/execute-one! ds
                              (sql/format {:update :ldap-identities
                                           :set {:groups-json groups-json
                                                 :last-sync-at [:raw "CURRENT_TIMESTAMP"]}
                                           :where [:and [:= :ldap-server ldap-server]
                                                        [:= :ldap-uid ldap-uid]]})))
                          (log/info "LDAP user logged in:" username)
                          {:success true
                           :user {:id (:user-id existing)
                                  :username (:username existing)
                                  :role (keyword (:role existing))
                                  :session-version (or (:session-version existing) 0)}})

                        ;; New user — JIT provision if allowed
                        (:auto-create-users ldap-cfg true)
                        (let [user (provision-ldap-user! ds config
                                                          (or (:username attrs) username)
                                                          email display-name role)]
                          ;; Link LDAP identity
                          (create-ldap-identity! ds {:user-id (:id user)
                                                      :ldap-server ldap-server
                                                      :distinguished-name user-dn
                                                      :ldap-uid ldap-uid
                                                      :email email
                                                      :display-name display-name
                                                      :groups-json groups-json})
                          (log/info "LDAP new user provisioned and logged in:" (:username user))
                          {:success true
                           :user {:id (:id user)
                                  :username (:username user)
                                  :role (keyword (:role user))
                                  :session-version 0}})

                        :else
                        {:success false
                         :error "No account linked to this LDAP identity. Contact your administrator."}))))))
            (finally
              (.close conn))))))))

;; ---------------------------------------------------------------------------
;; Periodic Group Sync
;; ---------------------------------------------------------------------------

(defn sync-ldap-groups!
  "Periodic group sync: re-fetch groups for all LDAP users and update roles.
   Intended to be called by the scheduler at :sync-interval-minutes intervals."
  [ds config]
  (when (feature-flags/enabled? config :ldap)
    (log/info "Starting LDAP group sync...")
    (let [conn (create-connection config)]
      (if-not conn
        (log/error "LDAP group sync failed — unable to connect to LDAP server")
        (try
          (let [identities (jdbc/execute! ds
                             (sql/format {:select [:li/id :li/user-id :li/distinguished-name
                                                   :li/ldap-uid :li/ldap-server
                                                   :u/username :u/role]
                                          :from [[:ldap-identities :li]]
                                          :join [[:users :u] [:= :li/user-id :u/id]]
                                          :where [:= :u/active 1]})
                             {:builder-fn rs/as-unqualified-kebab-maps})
                updated (atom 0)]
            (doseq [identity identities]
              (try
                (let [user-dn (:distinguished-name identity)
                      groups (fetch-user-groups conn config user-dn)
                      new-role (resolve-ldap-role groups config)
                      groups-json (when (seq groups) (json/write-str groups))]
                  ;; Update groups JSON
                  (jdbc/execute-one! ds
                    (sql/format {:update :ldap-identities
                                 :set {:groups-json groups-json
                                       :last-sync-at [:raw "CURRENT_TIMESTAMP"]}
                                 :where [:= :id (:id identity)]}))
                  ;; Update role if changed
                  (when (not= new-role (:role identity))
                    (log/info "LDAP sync: updating role for" (:username identity)
                              "from" (:role identity) "to" new-role)
                    (user-store/update-user! ds (:user-id identity) {:role new-role}))
                  (swap! updated inc))
                (catch Exception e
                  (log/error "LDAP sync failed for user:" (:username identity)
                             "-" (.getMessage e)))))
            (log/info "LDAP group sync complete. Updated" @updated "identities."))
          (finally
            (.close conn)))))))

;; ---------------------------------------------------------------------------
;; Helpers for views
;; ---------------------------------------------------------------------------

(defn ldap-enabled?
  "Check if LDAP authentication is enabled in the configuration."
  [config]
  (boolean (and (get-in config [:ldap :enabled])
                (feature-flags/enabled? config :ldap))))
