(ns chengis.web.auth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-db-path "/tmp/chengis-auth-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Input validation tests
;; ---------------------------------------------------------------------------

(deftest input-validation-test
  (testing "role validation"
    (is (true? (auth/valid-role? "admin")))
    (is (true? (auth/valid-role? "developer")))
    (is (true? (auth/valid-role? "viewer")))
    (is (false? (auth/valid-role? "superadmin")))
    (is (false? (auth/valid-role? "")))
    (is (false? (auth/valid-role? nil)))
    (is (false? (auth/valid-role? "ADMIN"))))

  (testing "username validation"
    (is (true? (auth/valid-username? "alice")))
    (is (true? (auth/valid-username? "bob-smith")))
    (is (true? (auth/valid-username? "user_123")))
    (is (true? (auth/valid-username? "ab")))   ;; min 2 chars
    (is (false? (auth/valid-username? "a")))   ;; too short
    (is (false? (auth/valid-username? "")))
    (is (false? (auth/valid-username? nil)))
    (is (false? (auth/valid-username? "user with spaces")))
    (is (false? (auth/valid-username? "user@email")))
    (is (false? (auth/valid-username? "<script>alert(1)</script>"))))

  (testing "password validation"
    (is (true? (auth/valid-password? "12345678")))      ;; exactly 8
    (is (true? (auth/valid-password? "longpassword")))
    (is (false? (auth/valid-password? "1234567")))      ;; 7 chars
    (is (false? (auth/valid-password? "")))
    (is (false? (auth/valid-password? nil)))))

;; ---------------------------------------------------------------------------
;; Role hierarchy tests
;; ---------------------------------------------------------------------------

(deftest role-hierarchy-test
  (testing "admin has all permissions"
    (is (true? (auth/role-sufficient? :admin :admin)))
    (is (true? (auth/role-sufficient? :admin :developer)))
    (is (true? (auth/role-sufficient? :admin :viewer))))

  (testing "developer has developer and viewer permissions"
    (is (false? (auth/role-sufficient? :developer :admin)))
    (is (true? (auth/role-sufficient? :developer :developer)))
    (is (true? (auth/role-sufficient? :developer :viewer))))

  (testing "viewer only has viewer permissions"
    (is (false? (auth/role-sufficient? :viewer :admin)))
    (is (false? (auth/role-sufficient? :viewer :developer)))
    (is (true? (auth/role-sufficient? :viewer :viewer))))

  (testing "string roles work too"
    (is (true? (auth/role-sufficient? "admin" "developer")))
    (is (false? (auth/role-sufficient? "viewer" "admin"))))

  (testing "unknown roles get level 0"
    (is (false? (auth/role-sufficient? "hacker" :viewer)))
    (is (false? (auth/role-sufficient? nil :viewer)))))

;; ---------------------------------------------------------------------------
;; JWT tests
;; ---------------------------------------------------------------------------

(deftest jwt-round-trip-test
  (let [config {:auth {:jwt-secret "test-secret-key-32-chars-minimum!"
                       :jwt-expiry-hours 1}}
        user {:id "user-1" :username "alice" :role "developer"}]
    (testing "sign and verify round-trip"
      (let [token (auth/generate-jwt user config)
            claims (auth/verify-jwt token config)]
        (is (string? token))
        (is (some? claims))
        (is (= "user-1" (:user-id claims)))
        (is (= "alice" (:username claims)))
        (is (= "developer" (:role claims)))))

    (testing "invalid token returns nil"
      (is (nil? (auth/verify-jwt "bogus.token.here" config))))

    (testing "wrong secret returns nil"
      (let [token (auth/generate-jwt user config)]
        (is (nil? (auth/verify-jwt token {:auth {:jwt-secret "different-secret"}})))))

    (testing "auto-generated secret when no jwt-secret configured"
      (let [config-no-secret {:auth {}}
            token (auth/generate-jwt user config-no-secret)
            claims (auth/verify-jwt token config-no-secret)]
        (is (string? token))
        (is (some? claims))
        (is (= "alice" (:username claims)))))))

;; ---------------------------------------------------------------------------
;; wrap-auth middleware tests
;; ---------------------------------------------------------------------------

(deftest wrap-auth-disabled-test
  (let [ds (conn/create-datasource test-db-path)
        system {:config {:auth {:enabled false}} :db ds}
        handler (fn [req] {:status 200 :body (:auth/user req)})
        wrapped (auth/wrap-auth handler system)]

    (testing "when auth disabled, every request gets admin user"
      (let [resp (wrapped {:uri "/" :request-method :get})]
        (is (= 200 (:status resp)))
        (is (= :admin (:role (:body resp))))
        (is (= "anonymous" (:username (:body resp))))))))

(deftest wrap-auth-enabled-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
        system {:config config :db ds}
        handler (fn [req] {:status 200 :body (:auth/user req)})
        wrapped (auth/wrap-auth handler system)]

    (testing "unauthenticated browser request redirects to login"
      (let [resp (wrapped {:uri "/" :request-method :get :headers {}})]
        (is (= 303 (:status resp)))
        (is (= "/login" (get-in resp [:headers "Location"])))))

    (testing "unauthenticated API request returns 401"
      (let [resp (wrapped {:uri "/api/jobs" :request-method :get :headers {"accept" "application/json"}})]
        (is (= 401 (:status resp)))))

    (testing "public paths are accessible without auth"
      (let [resp (wrapped {:uri "/login" :request-method :get :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "health endpoint is accessible without auth"
      (let [resp (wrapped {:uri "/health" :request-method :get :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "/ready endpoint is accessible without auth"
      (let [resp (wrapped {:uri "/ready" :request-method :get :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "session auth works"
      (let [bob (user-store/create-user! ds {:username "bob" :password "password1" :role "developer"})
            session-user {:id (:id bob) :username "bob" :role :developer :session-version 1}
            resp (wrapped {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 200 (:status resp)))
        (is (= "bob" (:username (:body resp))))))

    (testing "JWT bearer auth works"
      (let [alice (user-store/create-user! ds {:username "alice" :password "password1" :role "admin"})
            user {:id (:id alice) :username "alice" :role "admin" :session-version 1}
            token (auth/generate-jwt user config)
            resp (wrapped {:uri "/api/jobs" :request-method :get
                           :headers {"authorization" (str "Bearer " token)}})]
        (is (= 200 (:status resp)))
        (is (= "alice" (:username (:body resp))))))

    (testing "invalid JWT falls through to API token check"
      (let [resp (wrapped {:uri "/api/jobs" :request-method :get
                           :headers {"authorization" "Bearer invalid.token.here"
                                     "accept" "application/json"}})]
        ;; No API tokens exist, so returns 401
        (is (= 401 (:status resp)))))

    (testing "API token auth works"
      (let [user (user-store/create-user! ds {:username "tokenuser" :password "password1" :role "developer"})
            token-result (user-store/create-api-token! ds {:user-id (:id user) :name "test"})
            resp (wrapped {:uri "/api/jobs" :request-method :get
                           :headers {"authorization" (str "Bearer " (:token token-result))}})]
        (is (= 200 (:status resp)))
        (is (= "tokenuser" (:username (:body resp))))))

    (testing "expired API token returns 401"
      (let [user (user-store/create-user! ds {:username "expireduser" :password "password1" :role "developer"})
            token-result (user-store/create-api-token! ds {:user-id (:id user)
                                                           :name "expired"
                                                           :expires-at "2020-01-01T00:00:00Z"})
            resp (wrapped {:uri "/api/jobs" :request-method :get
                           :headers {"authorization" (str "Bearer " (:token token-result))
                                     "accept" "application/json"}})]
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; SSE auth bypass fix test
;; ---------------------------------------------------------------------------

(deftest sse-endpoint-requires-auth-in-distributed-mode-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}
                :distributed {:enabled true}}
        system {:config config :db ds}
        handler (fn [req] {:status 200 :body (:auth/user req)})
        wrapped (auth/wrap-auth handler system)]

    (testing "SSE /events endpoint requires auth even in distributed mode"
      (let [resp (wrapped {:uri "/api/builds/some-id/events" :request-method :get
                           :headers {"accept" "application/json"}})]
        ;; Should return 401, NOT 200
        (is (= 401 (:status resp)))))

    (testing "agent POST endpoints are exempt in distributed mode"
      (let [resp (wrapped {:uri "/api/builds/some-id/agent-events" :request-method :post
                           :headers {}})]
        ;; Agent endpoints bypass global auth (handler-level check-auth validates)
        (is (= 200 (:status resp)))))

    (testing "agent heartbeat is exempt in distributed mode"
      (let [resp (wrapped {:uri "/api/agents/some-id/heartbeat" :request-method :post
                           :headers {}})]
        (is (= 200 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Session invalidation on password reset test
;; ---------------------------------------------------------------------------

(deftest session-invalidated-on-password-reset-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
        system {:config config :db ds}
        handler (fn [req] {:status 200 :body (:auth/user req)})
        wrapped (auth/wrap-auth handler system)
        ;; Create a user
        user (user-store/create-user! ds {:username "carol" :password "password1" :role "developer"})
        ;; Simulate a session with current session-version
        db-user (user-store/get-user-by-username ds "carol")
        session-user {:id (:id user) :username "carol" :role :developer
                      :session-version (:session-version db-user)}]

    (testing "session works before password reset"
      (let [resp (wrapped {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 200 (:status resp)))
        (is (= "carol" (:username (:body resp))))))

    ;; Change password — increments session_version
    (user-store/update-password! ds (:id user) "newpassword!")

    (testing "session is invalidated after password reset"
      (let [resp (wrapped {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        ;; Should redirect to login (session rejected)
        (is (= 303 (:status resp)))
        (is (= "/login" (get-in resp [:headers "Location"])))))

    (testing "JWT is invalidated after password reset"
      (let [token (auth/generate-jwt session-user config)
            resp (wrapped {:uri "/api/test" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        ;; JWT session-version (old) != DB session-version (new) → rejected
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Deactivated user JWT rejection test
;; ---------------------------------------------------------------------------

(deftest deactivated-user-jwt-rejected-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
        system {:config config :db ds}
        handler (fn [req] {:status 200 :body (:auth/user req)})
        wrapped (auth/wrap-auth handler system)
        ;; Create and then deactivate a user
        user (user-store/create-user! ds {:username "dave" :password "password1" :role "developer"})
        db-user (user-store/get-user-by-username ds "dave")
        jwt-user {:id (:id user) :username "dave" :role "developer"
                  :session-version (:session-version db-user)}
        token (auth/generate-jwt jwt-user config)]

    (testing "JWT works before deactivation"
      (let [resp (wrapped {:uri "/api/test" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    ;; Deactivate the user
    (user-store/delete-user! ds (:id user))

    (testing "JWT is rejected after user deactivation"
      (let [resp (wrapped {:uri "/api/test" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; wrap-require-role tests
;; ---------------------------------------------------------------------------

(deftest wrap-require-role-test
  (let [handler (fn [req] {:status 200 :body "ok"})
        admin-only (auth/wrap-require-role :admin handler)
        dev-plus (auth/wrap-require-role :developer handler)]

    (testing "admin can access admin-only"
      (let [resp (admin-only {:auth/user {:role :admin} :uri "/admin" :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "developer cannot access admin-only"
      (let [resp (admin-only {:auth/user {:role :developer} :uri "/admin" :headers {}})]
        (is (= 403 (:status resp)))))

    (testing "viewer cannot access developer-plus"
      (let [resp (dev-plus {:auth/user {:role :viewer} :uri "/jobs" :headers {}})]
        (is (= 403 (:status resp)))))

    (testing "developer can access developer-plus"
      (let [resp (dev-plus {:auth/user {:role :developer} :uri "/jobs" :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "no user returns 403"
      (let [resp (admin-only {:uri "/admin" :headers {}})]
        (is (= 403 (:status resp)))))

    (testing "API request gets JSON 403"
      (let [resp (admin-only {:auth/user {:role :viewer}
                              :uri "/api/admin" :headers {"accept" "application/json"}})]
        (is (= 403 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))))

    (testing "browser request gets HTML 403"
      (let [resp (admin-only {:auth/user {:role :viewer}
                              :uri "/admin" :headers {}})]
        (is (= 403 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/html"))))))

;; ---------------------------------------------------------------------------
;; Login tests
;; ---------------------------------------------------------------------------

(deftest login-test
  (let [ds (conn/create-datasource test-db-path)]
    (user-store/create-user! ds {:username "alice" :password "secretpwd" :role "developer"})

    (testing "correct credentials succeed"
      (let [result (auth/login! ds "alice" "secretpwd")]
        (is (true? (:success result)))
        (is (= "alice" (:username (:user result))))
        (is (= :developer (:role (:user result))))))

    (testing "wrong password fails with generic message"
      (let [result (auth/login! ds "alice" "wrongpwd!")]
        (is (false? (:success result)))
        (is (= "Invalid username or password" (:error result)))))

    (testing "unknown user fails with same generic message (no enumeration)"
      (let [result (auth/login! ds "nobody" "password")]
        (is (false? (:success result)))
        (is (= "Invalid username or password" (:error result)))))

    (testing "deactivated user fails"
      (let [user (user-store/get-user-by-username ds "alice")]
        (user-store/delete-user! ds (:id user))
        (let [result (auth/login! ds "alice" "secretpwd")]
          (is (false? (:success result)))
          (is (= "Account is deactivated" (:error result))))))))

;; ---------------------------------------------------------------------------
;; current-user helper tests
;; ---------------------------------------------------------------------------

(deftest current-user-test
  (testing "returns nil when no auth/user on request"
    (is (nil? (auth/current-user {:uri "/"}))))

  (testing "returns user when present"
    (let [user {:id "u1" :username "test" :role :admin}]
      (is (= user (auth/current-user {:auth/user user}))))))

;; ---------------------------------------------------------------------------
;; Role downgrade invalidation tests (CQ-01)
;; ---------------------------------------------------------------------------

(deftest role-downgrade-invalidates-session-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
        system {:config config :db ds}
        admin-handler (auth/wrap-require-role :admin
                        (fn [req] {:status 200 :body (:auth/user req)}))
        wrapped (auth/wrap-auth admin-handler system)
        ;; Create admin user
        user (user-store/create-user! ds {:username "admin-alice" :password "password1" :role "admin"})
        db-user (user-store/get-user-by-username ds "admin-alice")
        session-user {:id (:id user) :username "admin-alice" :role :admin
                      :session-version (:session-version db-user)}]

    (testing "admin session can access admin route"
      (let [resp (wrapped {:uri "/admin-action" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 200 (:status resp)))))

    ;; Downgrade admin to viewer
    (user-store/update-user! ds (:id user) {:role "viewer"})

    (testing "session is rejected after role downgrade (session-version bumped)"
      (let [resp (wrapped {:uri "/admin-action" :request-method :get :headers {}
                           :session {:user session-user}})]
        ;; Session version was bumped by update-user!, so session is invalidated
        ;; returning 303 redirect to login (unauthenticated non-API request)
        (is (= 303 (:status resp)))))))

(deftest role-downgrade-invalidates-jwt-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
        system {:config config :db ds}
        admin-handler (auth/wrap-require-role :admin
                        (fn [req] {:status 200 :body (:auth/user req)}))
        wrapped (auth/wrap-auth admin-handler system)
        ;; Create admin user and generate JWT
        user (user-store/create-user! ds {:username "admin-bob" :password "password1" :role "admin"})
        db-user (user-store/get-user-by-username ds "admin-bob")
        jwt-user {:id (:id user) :username "admin-bob" :role "admin"
                  :session-version (:session-version db-user)}
        token (auth/generate-jwt jwt-user config)]

    (testing "admin JWT can access admin route"
      (let [resp (wrapped {:uri "/api/admin-action" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    ;; Downgrade admin to viewer
    (user-store/update-user! ds (:id user) {:role "viewer"})

    (testing "JWT is rejected after role downgrade (session-version bumped)"
      (let [resp (wrapped {:uri "/api/admin-action" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        ;; Session version was bumped → JWT invalidated → 401
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Custom metrics path tests (CQ-06)
;; ---------------------------------------------------------------------------

(deftest custom-metrics-path-is-public-test
  (let [ds (conn/create-datasource test-db-path)
        config {:auth {:enabled true
                       :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}
                :metrics {:path "/custom-metrics"
                          :enabled true
                          :auth-required false}}
        system {:config config :db ds}
        handler (fn [req] {:status 200 :body "ok"})
        wrapped (auth/wrap-auth handler system)]

    (testing "custom metrics path is public (no auth required)"
      (let [resp (wrapped {:uri "/custom-metrics" :request-method :get :headers {}})]
        (is (= 200 (:status resp)))))

    (testing "default /metrics path requires auth when custom path is configured"
      (let [resp (wrapped {:uri "/metrics" :request-method :get :headers {}})]
        ;; /metrics is not a public path when custom path is set
        (is (= 303 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation testing remediation: scope-sufficient? boolean returns
;; ---------------------------------------------------------------------------

(deftest scope-sufficient-boolean-returns-test
  (testing "session user (no :token-scopes key) returns true"
    (is (true? (auth/scope-sufficient? {:username "alice" :role :admin} "build:read"))))

  (testing "nil scopes (full-access API token) returns true"
    (is (true? (auth/scope-sufficient? {:username "alice" :token-scopes nil} "build:read"))))

  (testing "exact scope match returns true"
    (is (true? (auth/scope-sufficient? {:username "alice" :token-scopes #{"build:read"}} "build:read"))))

  (testing "wildcard scope match returns true"
    (is (true? (auth/scope-sufficient? {:username "alice" :token-scopes #{"admin:*"}} "admin:users"))))

  (testing "missing scope returns false"
    (is (false? (auth/scope-sufficient? {:username "alice" :token-scopes #{"build:read"}} "build:trigger"))))

  (testing "empty scopes set returns false for any scope"
    (is (false? (auth/scope-sufficient? {:username "alice" :token-scopes #{}} "build:read")))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation testing remediation: auth-enabled/distributed-enabled defaults
;; ---------------------------------------------------------------------------

(deftest wrap-auth-config-defaults-test
  (testing "auth-enabled defaults to false (anonymous admin access)"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {} :db ds}
          handler (fn [req] {:status 200 :body (:auth/user req)})
          wrapped (auth/wrap-auth handler system)
          resp (wrapped {:uri "/api/jobs" :request-method :get :headers {}})]
      (is (= 200 (:status resp)))
      (is (= "anonymous" (:username (:body resp))))
      (is (= :admin (:role (:body resp))))))

  (testing "distributed-enabled defaults to false (agent paths require auth)"
    (let [ds (conn/create-datasource test-db-path)
          system {:config {:auth {:enabled true
                                  :jwt-secret "test-jwt-secret-32-chars-min!!!!!"}}
                  :db ds}
          handler (fn [req] {:status 200 :body "ok"})
          wrapped (auth/wrap-auth handler system)
          resp (wrapped {:uri "/api/builds/b1/agent-events" :request-method :post :headers {}})]
      ;; Without distributed enabled, agent paths should require auth
      (is (= 401 (:status resp))))))

(deftest login-returns-success-false-on-failure-test
  (testing "login! returns :success false for unknown user"
    (let [ds (conn/create-datasource test-db-path)
          result (auth/login! ds "nonexistent" "password")]
      (is (false? (:success result)))
      (is (string? (:error result)))))

  (testing "login! returns :success false for wrong password"
    (let [ds (conn/create-datasource test-db-path)]
      (user-store/create-user! ds {:username "testuser" :password "correctpassword" :role "developer"})
      (let [result (auth/login! ds "testuser" "wrongpassword")]
        (is (false? (:success result)))
        (is (string? (:error result)))))))
