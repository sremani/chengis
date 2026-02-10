(ns chengis.web.ldap-test
  "Tests for LDAP/Active Directory authentication integration.
   Covers: LDAP identity store, role mapping, user provisioning,
   full login flow (mocked), group sync, and feature flag gating.
   LDAP connections are mocked with with-redefs since we cannot
   run a real LDAP server in tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.ldap :as ldap]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.unboundid.ldap.sdk SearchResultEntry Attribute Control Entry]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-ldap-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :once setup-db)

;; ---------------------------------------------------------------------------
;; Test config
;; ---------------------------------------------------------------------------

(def ^:private test-ldap-config
  {:feature-flags {:ldap true}
   :ldap {:enabled true
          :host "ldap.example.com"
          :port 389
          :use-ssl false
          :bind-dn "cn=readonly,dc=example,dc=com"
          :bind-password "secret"
          :user-base-dn "ou=people,dc=example,dc=com"
          :user-filter "(uid={0})"
          :username-attribute "uid"
          :email-attribute "mail"
          :display-name-attribute "cn"
          :group-base-dn "ou=groups,dc=example,dc=com"
          :group-filter "(member={0})"
          :role-mapping {"cn=admins,ou=groups,dc=example,dc=com" "admin"
                         "cn=developers,ou=groups,dc=example,dc=com" "developer"}
          :default-role "viewer"
          :auto-create-users true
          :sync-interval-minutes 60}})

;; ---------------------------------------------------------------------------
;; Mock connection helper
;; ---------------------------------------------------------------------------

(defn- mock-ldap-connection
  "Create a mock object that has a .close method (since ldap-login!
   calls .close on the connection in its finally block).
   Returns a reify implementing java.io.Closeable."
  []
  (reify java.io.Closeable
    (close [_] nil)))

;; ---------------------------------------------------------------------------
;; LDAP entry helper
;; ---------------------------------------------------------------------------

(defn- make-search-result-entry
  "Construct a real SearchResultEntry for testing.
   SearchResultEntry is a final class so it cannot be proxied.
   Uses the (Entry, Control...) constructor via an intermediate Entry."
  ^SearchResultEntry [dn attrs]
  (let [ldap-attrs (into-array Attribute
                               (map (fn [[k v]]
                                      (Attribute. (name k) (str v)))
                                    attrs))
        entry (Entry. (str dn) ldap-attrs)]
    (SearchResultEntry. entry (into-array Control []))))

;; ---------------------------------------------------------------------------
;; LDAP Identity Store Tests (DB operations)
;; ---------------------------------------------------------------------------

(deftest find-ldap-identity-nil-when-not-found-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns nil when no matching LDAP identity exists"
      (is (nil? (ldap/find-ldap-identity ds "ldap.example.com:389" "nonexistent"))))))

(deftest create-and-find-ldap-identity-test
  (let [ds (conn/create-datasource test-db-path)]
    (let [user (user-store/create-user! ds {:username "jdoe" :password "pw" :role "developer"})]

      (testing "create LDAP identity"
        (ldap/create-ldap-identity! ds {:user-id (:id user)
                                         :ldap-server "ldap.example.com:389"
                                         :distinguished-name "uid=jdoe,ou=people,dc=example,dc=com"
                                         :ldap-uid "jdoe"
                                         :email "jdoe@example.com"
                                         :display-name "John Doe"
                                         :groups-json "[\"cn=developers,ou=groups,dc=example,dc=com\"]"}))

      (testing "find existing LDAP identity"
        (let [found (ldap/find-ldap-identity ds "ldap.example.com:389" "jdoe")]
          (is (some? found))
          (is (= (:id user) (:user-id found)))
          (is (= "jdoe" (:username found)))
          (is (= "developer" (:role found)))))

      (testing "find returns nil for wrong server"
        (is (nil? (ldap/find-ldap-identity ds "other-ldap.example.com:636" "jdoe"))))

      (testing "find returns nil for wrong uid"
        (is (nil? (ldap/find-ldap-identity ds "ldap.example.com:389" "unknown")))))))

;; ---------------------------------------------------------------------------
;; JIT User Provisioning Test
;; ---------------------------------------------------------------------------

(deftest provision-ldap-user-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "provision creates a new user with correct role"
      (let [user (ldap/provision-ldap-user! ds test-ldap-config "alice" "alice@example.com" "Alice Smith" "developer")]
        (is (some? (:id user)))
        (is (= "alice" (:username user)))
        (is (= "developer" (:role user)))))

    (testing "provision deduplicates username if taken"
      ;; "alice" is already taken from above
      (let [user2 (ldap/provision-ldap-user! ds test-ldap-config "alice" "alice2@example.com" "Alice Two" "viewer")]
        (is (some? (:id user2)))
        (is (= "alice-1" (:username user2)))))))

;; ---------------------------------------------------------------------------
;; Role Resolution Tests
;; ---------------------------------------------------------------------------

(deftest resolve-ldap-role-test
  (testing "maps admin group to admin role"
    (let [groups ["cn=admins,ou=groups,dc=example,dc=com"]]
      (is (= "admin" (ldap/resolve-ldap-role groups test-ldap-config)))))

  (testing "maps developer group to developer role"
    (let [groups ["cn=developers,ou=groups,dc=example,dc=com"]]
      (is (= "developer" (ldap/resolve-ldap-role groups test-ldap-config)))))

  (testing "first matching group wins"
    (let [groups ["cn=developers,ou=groups,dc=example,dc=com"
                  "cn=admins,ou=groups,dc=example,dc=com"]]
      (is (= "developer" (ldap/resolve-ldap-role groups test-ldap-config)))))

  (testing "falls back to default when no mapping match"
    (let [groups ["cn=unknown,ou=groups,dc=example,dc=com"]]
      (is (= "viewer" (ldap/resolve-ldap-role groups test-ldap-config)))))

  (testing "falls back to default when no groups"
    (is (= "viewer" (ldap/resolve-ldap-role [] test-ldap-config))))

  (testing "falls back to default when nil groups"
    (is (= "viewer" (ldap/resolve-ldap-role nil test-ldap-config))))

  (testing "case-insensitive group matching works"
    (let [groups ["CN=Admins,OU=Groups,DC=Example,DC=Com"]]
      (is (= "admin" (ldap/resolve-ldap-role groups test-ldap-config))))))

;; ---------------------------------------------------------------------------
;; User Attribute Extraction Tests
;; ---------------------------------------------------------------------------

(deftest extract-user-attributes-test
  (let [entry (make-search-result-entry
                "uid=jdoe,ou=people,dc=example,dc=com"
                {:uid "jdoe"
                 :mail "jdoe@example.com"
                 :cn "John Doe"})]

    (testing "extracts all configured attributes"
      (let [attrs (ldap/extract-user-attributes entry test-ldap-config)]
        (is (= "jdoe" (:username attrs)))
        (is (= "jdoe@example.com" (:email attrs)))
        (is (= "John Doe" (:display-name attrs)))))

    (testing "handles custom attribute names"
      (let [custom-config (assoc-in test-ldap-config [:ldap :email-attribute] "emailAddress")
            custom-entry (make-search-result-entry
                           "uid=jdoe,ou=people,dc=example,dc=com"
                           {:uid "jdoe"
                            :emailAddress "custom@example.com"
                            :cn "John"})]
        (let [attrs (ldap/extract-user-attributes custom-entry custom-config)]
          (is (= "custom@example.com" (:email attrs))))))))

;; ---------------------------------------------------------------------------
;; ldap-enabled? Helper Tests
;; ---------------------------------------------------------------------------

(deftest ldap-enabled?-test
  (testing "returns true when both ldap config and feature flag are enabled"
    (is (true? (ldap/ldap-enabled? test-ldap-config))))

  (testing "returns false when ldap config disabled"
    (is (false? (ldap/ldap-enabled?
                  (assoc-in test-ldap-config [:ldap :enabled] false)))))

  (testing "returns false when feature flag disabled"
    (is (false? (ldap/ldap-enabled?
                  (assoc-in test-ldap-config [:feature-flags :ldap] false)))))

  (testing "returns false when ldap config missing"
    (is (false? (ldap/ldap-enabled? {:feature-flags {:ldap true}}))))

  (testing "returns false when empty config"
    (is (false? (ldap/ldap-enabled? {})))))

;; ---------------------------------------------------------------------------
;; Feature Flag Gating Tests
;; ---------------------------------------------------------------------------

(deftest ldap-login-disabled-returns-error-test
  (let [ds (conn/create-datasource test-db-path)
        disabled-config (assoc-in test-ldap-config [:feature-flags :ldap] false)]

    (testing "ldap-login! returns error when feature flag is disabled"
      (let [result (ldap/ldap-login! ds disabled-config "user" "pass" nil nil)]
        (is (false? (:success result)))
        (is (str/includes? (:error result) "not enabled"))))))

;; ---------------------------------------------------------------------------
;; Full LDAP Login Flow Tests (mocked LDAP)
;; ---------------------------------------------------------------------------

(deftest ldap-login-user-not-found-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns error when user not found in LDAP"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] nil)]
        (let [result (ldap/ldap-login! ds test-ldap-config "nobody" "pass" nil nil)]
          (is (false? (:success result)))
          (is (= "Invalid username or password" (:error result))))))))

(deftest ldap-login-bind-fails-test
  (let [ds (conn/create-datasource test-db-path)
        mock-entry (make-search-result-entry
                     "uid=jdoe,ou=people,dc=example,dc=com"
                     {:uid "jdoe" :mail "jdoe@example.com" :cn "John Doe"})]

    (testing "returns error when bind fails (wrong password)"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] mock-entry)
                    ldap/bind-as-user (fn [_ _ _] false)]
        (let [result (ldap/ldap-login! ds test-ldap-config "jdoe" "wrongpw" nil nil)]
          (is (false? (:success result)))
          (is (= "Invalid username or password" (:error result))))))))

(deftest ldap-login-provisions-new-user-test
  (let [ds (conn/create-datasource test-db-path)
        mock-entry (make-search-result-entry
                     "uid=newuser,ou=people,dc=example,dc=com"
                     {:uid "newuser" :mail "newuser@example.com" :cn "New User"})
        mock-groups ["cn=developers,ou=groups,dc=example,dc=com"]]

    (testing "JIT provisions new user on first LDAP login"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] mock-entry)
                    ldap/bind-as-user (fn [_ _ _] true)
                    ldap/fetch-user-groups (fn [_ _ _] mock-groups)]
        (let [result (ldap/ldap-login! ds test-ldap-config "newuser" "password" nil nil)]
          (is (true? (:success result)))
          (is (= "newuser" (get-in result [:user :username])))
          (is (= :developer (get-in result [:user :role])))
          ;; Verify user was created in DB
          (let [db-user (user-store/get-user-by-username ds "newuser")]
            (is (some? db-user))
            (is (= "developer" (:role db-user))))
          ;; Verify LDAP identity was created
          (let [identity (ldap/find-ldap-identity ds "ldap.example.com:389" "newuser")]
            (is (some? identity))
            (is (= "newuser" (:username identity)))))))))

(deftest ldap-login-existing-user-test
  (let [ds (conn/create-datasource test-db-path)
        ;; Pre-create user and LDAP identity
        user (user-store/create-user! ds {:username "existing" :password "pw" :role "developer"})
        _ (ldap/create-ldap-identity! ds {:user-id (:id user)
                                           :ldap-server "ldap.example.com:389"
                                           :distinguished-name "uid=existing,ou=people,dc=example,dc=com"
                                           :ldap-uid "existing"
                                           :email "existing@example.com"
                                           :display-name "Existing User"
                                           :groups-json nil})
        mock-entry (make-search-result-entry
                     "uid=existing,ou=people,dc=example,dc=com"
                     {:uid "existing" :mail "existing@example.com" :cn "Existing User"})
        mock-groups ["cn=admins,ou=groups,dc=example,dc=com"]]

    (testing "existing LDAP user logs in successfully"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] mock-entry)
                    ldap/bind-as-user (fn [_ _ _] true)
                    ldap/fetch-user-groups (fn [_ _ _] mock-groups)]
        (let [result (ldap/ldap-login! ds test-ldap-config "existing" "password" nil nil)]
          (is (true? (:success result)))
          (is (= (:id user) (get-in result [:user :id])))
          (is (= "existing" (get-in result [:user :username])))
          (is (= :developer (get-in result [:user :role]))))))))

(deftest ldap-login-deactivated-user-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "deactuser" :password "pw" :role "developer"})
        _ (ldap/create-ldap-identity! ds {:user-id (:id user)
                                           :ldap-server "ldap.example.com:389"
                                           :distinguished-name "uid=deactuser,ou=people,dc=example,dc=com"
                                           :ldap-uid "deactuser"
                                           :email "deact@example.com"
                                           :display-name "Deactivated"
                                           :groups-json nil})
        mock-entry (make-search-result-entry
                     "uid=deactuser,ou=people,dc=example,dc=com"
                     {:uid "deactuser" :mail "deact@example.com" :cn "Deactivated"})
        mock-groups []]

    ;; Deactivate user
    (user-store/delete-user! ds (:id user))

    (testing "deactivated LDAP user is rejected"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] mock-entry)
                    ldap/bind-as-user (fn [_ _ _] true)
                    ldap/fetch-user-groups (fn [_ _ _] mock-groups)]
        (let [result (ldap/ldap-login! ds test-ldap-config "deactuser" "password" nil nil)]
          (is (false? (:success result)))
          (is (str/includes? (:error result) "deactivated")))))))

(deftest ldap-login-auto-create-disabled-test
  (let [ds (conn/create-datasource test-db-path)
        config (assoc-in test-ldap-config [:ldap :auto-create-users] false)
        mock-entry (make-search-result-entry
                     "uid=brand-new,ou=people,dc=example,dc=com"
                     {:uid "brand-new" :mail "new@example.com" :cn "Brand New"})
        mock-groups []]

    (testing "auto-create disabled rejects new users"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/search-user (fn [_ _ _] mock-entry)
                    ldap/bind-as-user (fn [_ _ _] true)
                    ldap/fetch-user-groups (fn [_ _ _] mock-groups)]
        (let [result (ldap/ldap-login! ds config "brand-new" "password" nil nil)]
          (is (false? (:success result)))
          (is (str/includes? (:error result) "No account linked")))))))

(deftest ldap-login-connection-failure-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "returns error when LDAP connection fails"
      (with-redefs [ldap/create-connection (fn [_] nil)]
        (let [result (ldap/ldap-login! ds test-ldap-config "user" "pass" nil nil)]
          (is (false? (:success result)))
          (is (str/includes? (:error result) "Unable to connect")))))))

;; ---------------------------------------------------------------------------
;; Group Sync Tests (mocked)
;; ---------------------------------------------------------------------------

(deftest sync-ldap-groups-updates-roles-test
  (let [ds (conn/create-datasource test-db-path)
        ;; Create a user with developer role
        user (user-store/create-user! ds {:username "syncuser" :password "pw" :role "developer"})
        _ (ldap/create-ldap-identity! ds {:user-id (:id user)
                                           :ldap-server "ldap.example.com:389"
                                           :distinguished-name "uid=syncuser,ou=people,dc=example,dc=com"
                                           :ldap-uid "syncuser"
                                           :email "sync@example.com"
                                           :display-name "Sync User"
                                           :groups-json "[\"cn=developers,ou=groups,dc=example,dc=com\"]"})
        ;; After sync, user is now in admins group
        new-groups ["cn=admins,ou=groups,dc=example,dc=com"]]

    (testing "sync updates role when groups change"
      (with-redefs [ldap/create-connection (fn [_] (mock-ldap-connection))
                    ldap/fetch-user-groups (fn [_ _ _] new-groups)]
        (ldap/sync-ldap-groups! ds test-ldap-config)
        ;; Verify role was updated
        (let [updated-user (user-store/get-user-by-username ds "syncuser")]
          (is (= "admin" (:role updated-user))))))))

(deftest sync-ldap-groups-disabled-noop-test
  (let [ds (conn/create-datasource test-db-path)
        disabled-config (assoc-in test-ldap-config [:feature-flags :ldap] false)]

    (testing "sync is a no-op when feature flag is disabled"
      ;; If create-connection were called, it would fail — but it shouldn't be
      (with-redefs [ldap/create-connection (fn [_] (throw (Exception. "Should not be called")))]
        (is (nil? (ldap/sync-ldap-groups! ds disabled-config)))))))
