(ns chengis.web.auth-scopes-test
  "Tests for API token scope checking (CR-07, CR-08).
   Covers: scope-sufficient?, wrap-require-scope, scope validation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-scopes-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; scope-sufficient? Tests
;; ---------------------------------------------------------------------------

(deftest scope-sufficient-nil-scopes-test
  (testing "nil scopes = full access (backward compat)"
    (is (true? (auth/scope-sufficient? {:role :developer :token-scopes nil} "build:trigger")))
    (is (true? (auth/scope-sufficient? {:role :developer :token-scopes nil} "admin:*")))
    (is (true? (auth/scope-sufficient? {:role :developer :token-scopes nil} "secret:write")))))

(deftest scope-sufficient-no-token-scopes-key-test
  (testing "session/JWT users without :token-scopes key bypass scope checks"
    (is (true? (auth/scope-sufficient? {:role :developer} "build:trigger")))
    (is (true? (auth/scope-sufficient? {:role :admin} "admin:*")))
    (is (true? (auth/scope-sufficient? {:role :viewer} "secret:write")))))

(deftest scope-sufficient-exact-match-test
  (testing "exact scope match permits access"
    (is (true? (auth/scope-sufficient?
                 {:role :developer :token-scopes #{"build:trigger" "build:read"}}
                 "build:trigger"))))

  (testing "exact scope mismatch denies access"
    (is (false? (auth/scope-sufficient?
                  {:role :developer :token-scopes #{"build:read"}}
                  "build:trigger")))))

(deftest scope-sufficient-wildcard-test
  (testing "admin:* wildcard covers admin:anything"
    (is (true? (auth/scope-sufficient?
                 {:role :developer :token-scopes #{"admin:*"}}
                 "admin:users")))
    (is (true? (auth/scope-sufficient?
                 {:role :developer :token-scopes #{"admin:*"}}
                 "admin:*"))))

  (testing "build:* wildcard covers build:trigger"
    (is (true? (auth/scope-sufficient?
                 {:role :developer :token-scopes #{"build:*"}}
                 "build:trigger"))))

  (testing "wrong prefix wildcard doesn't match"
    (is (false? (auth/scope-sufficient?
                  {:role :developer :token-scopes #{"build:*"}}
                  "job:create")))))

(deftest scope-sufficient-empty-scopes-test
  (testing "empty scope set denies all"
    (is (false? (auth/scope-sufficient?
                  {:role :developer :token-scopes #{}}
                  "build:trigger")))
    (is (false? (auth/scope-sufficient?
                  {:role :developer :token-scopes #{}}
                  "admin:*")))))

;; ---------------------------------------------------------------------------
;; wrap-require-scope Middleware Tests
;; ---------------------------------------------------------------------------

(deftest wrap-require-scope-permit-test
  (let [handler (fn [_req] {:status 200 :body "ok"})
        wrapped (auth/wrap-require-scope "build:trigger" handler)]

    (testing "API token with exact scope is permitted"
      (let [resp (wrapped {:auth/user {:role :developer :token-scopes #{"build:trigger"}}
                           :uri "/api/builds/trigger" :headers {"accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    (testing "session user (no scopes) is permitted"
      (let [resp (wrapped {:auth/user {:role :developer}
                           :uri "/builds" :headers {}})]
        (is (= 200 (:status resp)))))))

(deftest wrap-require-scope-deny-test
  (let [handler (fn [_req] {:status 200 :body "ok"})
        wrapped (auth/wrap-require-scope "build:trigger" handler)]

    (testing "API token with insufficient scope is denied (JSON)"
      (let [resp (wrapped {:auth/user {:role :developer :token-scopes #{"build:read"}}
                           :uri "/api/builds/trigger"
                           :headers {"accept" "application/json"}})]
        (is (= 403 (:status resp)))
        (is (str/includes? (:body resp) "build:trigger"))))

    (testing "API token with empty scopes is denied"
      (let [resp (wrapped {:auth/user {:role :developer :token-scopes #{}}
                           :uri "/api/builds/trigger"
                           :headers {"accept" "application/json"}})]
        (is (= 403 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Token Scope JSON Parsing Tests (CR-07)
;; ---------------------------------------------------------------------------

(deftest token-scope-json-parsing-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "alice" :password "pass123" :role "developer"})]

    (testing "valid scopes JSON is parsed to a set"
      (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                      :name "scoped-token"
                                                      :scopes ["build:trigger" "build:read"]})
            found (user-store/find-api-token-user ds (:token result))]
        (is (some? found))
        (is (= #{"build:trigger" "build:read"} (:token-scopes found)))))

    (testing "nil scopes stays nil (full access)"
      (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                      :name "full-token"
                                                      :scopes nil})
            found (user-store/find-api-token-user ds (:token result))]
        (is (some? found))
        (is (nil? (:token-scopes found)))))

    (testing "empty scopes stays nil"
      (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                      :name "empty-scope-token"
                                                      :scopes []})
            found (user-store/find-api-token-user ds (:token result))]
        (is (some? found))
        (is (nil? (:token-scopes found)))))))

(deftest malformed-scope-json-deny-all-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "bob" :password "pass123" :role "developer"})
        result (user-store/create-api-token! ds {:user-id (:id user)
                                                  :name "bad-scope-token"
                                                  :scopes ["build:read"]})]

    ;; Corrupt the scopes JSON directly in the database
    (jdbc/execute-one! ds
      (sql/format {:update :api-tokens
                   :set {:scopes "INVALID-JSON{{{"}
                   :where [:= :id (:id result)]}))

    (testing "malformed scopes JSON results in empty set (deny all) not nil (allow all)"
      (let [found (user-store/find-api-token-user ds (:token result))]
        (is (some? found))
        ;; CR-07: Should be empty set #{}, NOT nil
        (is (set? (:token-scopes found)))
        (is (empty? (:token-scopes found)))
        ;; Verify it actually denies access
        (is (false? (auth/scope-sufficient? found "build:read")))))))

;; ---------------------------------------------------------------------------
;; Scope Validation Tests (CR-08)
;; ---------------------------------------------------------------------------

(deftest valid-scopes-is-public-test
  (testing "valid-scopes is accessible (not private)"
    (is (set? auth/valid-scopes))
    (is (contains? auth/valid-scopes "build:trigger"))
    (is (contains? auth/valid-scopes "admin:*"))))

;; ---------------------------------------------------------------------------
;; Regression Tests (External Review Findings)
;; ---------------------------------------------------------------------------

(deftest all-invalid-scopes-creates-full-access-token-vulnerability-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "scopetest" :password "pass123" :role "developer"})]

    (testing "P1: empty scopes vector → nil scopes-json → full access token (the bug)"
      ;; This demonstrates why the handler must reject all-invalid scopes:
      ;; passing an empty vector to create-api-token! results in a full-access token
      (let [result (user-store/create-api-token! ds {:user-id (:id user)
                                                      :name "empty-vec-token"
                                                      :scopes []})
            found (user-store/find-api-token-user ds (:token result))]
        ;; The token was stored with NULL scopes (full access) because (seq []) = nil
        (is (nil? (:token-scopes found)))
        (is (true? (auth/scope-sufficient? found "admin:*")))
        (is (true? (auth/scope-sufficient? found "build:trigger")))))

    (testing "P1: scope filtering must reject, not pass empty vector to create-api-token!"
      ;; Simulate what the handler SHOULD do: validate and reject
      (let [scopes-raw ["fake:scope" "bogus:perm"]
            valid (filterv #(contains? auth/valid-scopes %) scopes-raw)]
        ;; All invalid scopes filtered → empty vector
        (is (empty? valid))
        ;; The handler now detects this and shows an error instead of creating a token
        ;; This test verifies the filtering logic works correctly
        (is (= [] valid))))))
