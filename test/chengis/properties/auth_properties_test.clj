(ns chengis.properties.auth-properties-test
  "Property-based tests for authentication and authorization functions.
   Verifies RBAC algebra (reflexivity, transitivity, total ordering),
   input validation (usernames, passwords, roles), and scope checking."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.web.auth :as auth]))

;; ---------------------------------------------------------------------------
;; valid-role? — only accepts known roles
;; ---------------------------------------------------------------------------

(defspec valid-role-accepts-known-roles 100
  (prop/for-all [role cgen/gen-role]
    (true? (auth/valid-role? role))))

(defspec valid-role-rejects-keywords 100
  (prop/for-all [role cgen/gen-role-keyword]
    ;; valid-role? uses (str role) which produces ":admin" not "admin" for keywords
    ;; so keywords should NOT pass validation (str coercion includes colon)
    (false? (auth/valid-role? role))))

(defspec valid-role-rejects-unknown-strings 200
  (prop/for-all [s (gen/such-that
                     #(not (contains? #{"admin" "developer" "viewer"} (str %)))
                     gen/string-alphanumeric)]
    (false? (auth/valid-role? s))))

;; ---------------------------------------------------------------------------
;; valid-username? — format validation
;; ---------------------------------------------------------------------------

(defspec valid-username-accepts-valid 200
  (prop/for-all [username cgen/gen-valid-username]
    (true? (auth/valid-username? username))))

(defspec valid-username-rejects-invalid 200
  (prop/for-all [username cgen/gen-invalid-username]
    (false? (auth/valid-username? username))))

(defspec valid-username-rejects-non-strings 50
  (prop/for-all [v (gen/one-of [gen/nat gen/keyword (gen/return nil)])]
    (false? (auth/valid-username? v))))

;; ---------------------------------------------------------------------------
;; valid-password? — minimum length
;; ---------------------------------------------------------------------------

(defspec valid-password-accepts-long-enough 200
  (prop/for-all [pw cgen/gen-valid-password]
    (true? (auth/valid-password? pw))))

(defspec valid-password-rejects-short 200
  (prop/for-all [pw cgen/gen-short-password]
    (false? (auth/valid-password? pw))))

;; ---------------------------------------------------------------------------
;; role-sufficient? — RBAC algebra
;; ---------------------------------------------------------------------------

(defspec role-sufficient-reflexive 100
  (prop/for-all [role cgen/gen-role-keyword]
    ;; Every role is sufficient for itself
    (true? (auth/role-sufficient? role role))))

(defspec role-sufficient-transitive 200
  (prop/for-all [a cgen/gen-role-keyword
                 b cgen/gen-role-keyword
                 c cgen/gen-role-keyword]
    ;; If A >= B and B >= C then A >= C
    (if (and (auth/role-sufficient? a b)
             (auth/role-sufficient? b c))
      (auth/role-sufficient? a c)
      true)))

(defspec role-sufficient-admin-is-top 100
  (prop/for-all [role cgen/gen-role-keyword]
    ;; Admin is always sufficient for any role
    (true? (auth/role-sufficient? :admin role))))

(defspec role-sufficient-viewer-is-bottom 100
  (prop/for-all [role cgen/gen-role-keyword]
    ;; Any role is sufficient for viewer
    (true? (auth/role-sufficient? role :viewer))))

(defspec role-sufficient-unknown-gets-zero 100
  (prop/for-all [unknown-role (gen/such-that
                                #(not (contains? #{:admin :developer :viewer} %))
                                gen/keyword)]
    ;; Unknown roles get level 0, so they should NOT be sufficient for viewer (level 1)
    (false? (auth/role-sufficient? unknown-role :viewer))))

;; ---------------------------------------------------------------------------
;; scope-sufficient? — API token scope checking
;; ---------------------------------------------------------------------------

(defspec scope-sufficient-nil-scopes-means-full-access 100
  (prop/for-all [scope cgen/gen-scope]
    ;; User with nil token-scopes = full access
    (true? (auth/scope-sufficient? {:token-scopes nil} scope))))

(defspec scope-sufficient-no-key-means-full-access 100
  (prop/for-all [scope cgen/gen-scope]
    ;; Session/JWT user (no :token-scopes key) = full access
    (true? (auth/scope-sufficient? {:username "test"} scope))))

(defspec scope-sufficient-exact-match 100
  (prop/for-all [scope cgen/gen-scope]
    ;; Scope set containing the exact required scope should pass
    (true? (auth/scope-sufficient? {:token-scopes #{scope}} scope))))

(defspec scope-sufficient-wildcard-covers-prefix 100
  (prop/for-all [prefix (gen/elements ["build" "job" "secret" "agent" "admin"])]
    ;; "prefix:*" should cover "prefix:anything"
    (auth/scope-sufficient?
      {:token-scopes #{(str prefix ":*")}}
      (str prefix ":read"))))

(defspec scope-sufficient-missing-scope-denied 100
  (prop/for-all [required (gen/elements ["build:trigger" "job:create" "secret:write"])]
    ;; Empty scope set should deny everything
    (false? (auth/scope-sufficient? {:token-scopes #{}} required))))

;; ---------------------------------------------------------------------------
;; current-user / current-org — extraction
;; ---------------------------------------------------------------------------

(defspec current-user-extracts-auth-user 100
  (prop/for-all [username (gen/not-empty gen/string-alphanumeric)
                 role cgen/gen-role-keyword]
    (let [user {:username username :role role}
          req {:auth/user user}]
      (= user (auth/current-user req)))))

(defspec current-user-nil-when-missing 50
  (prop/for-all [_ (gen/return nil)]
    (nil? (auth/current-user {}))))

(defspec current-org-extracts-org-context 100
  (prop/for-all [org-id cgen/gen-id]
    (let [req {:org/current {:id org-id :role :admin}}]
      (and (= org-id (:id (auth/current-org req)))
           (= org-id (auth/current-org-id req))))))

(defspec current-org-id-nil-when-missing 50
  (prop/for-all [_ (gen/return nil)]
    (nil? (auth/current-org-id {}))))
