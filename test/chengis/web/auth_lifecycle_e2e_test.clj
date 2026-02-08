(ns chengis.web.auth-lifecycle-e2e-test
  "End-to-end tests verifying that account state changes invalidate
   all active authentication paths (JWT, session, API token)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [chengis.web.routes :as routes]
            [chengis.metrics :as metrics]
            [clojure.core.async :as async]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-auth-lifecycle-e2e.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- make-system [config-overrides]
  (let [ds (conn/create-datasource test-db-path)
        registry (try (metrics/init-registry) (catch Exception _ nil))
        audit-ch (async/chan 16)]
    {:db ds
     :config (merge {:database {:path test-db-path}
                     :auth {:enabled true
                            :jwt-secret "test-lifecycle-secret-32-chars!!!!"}
                     :distributed {:enabled false}
                     :metrics {:enabled true :auth-required false}}
                    config-overrides)
     :metrics registry
     :audit-writer {:channel audit-ch}}))

(defn- make-handler [system]
  (routes/app-routes system))

(defn- make-auth-handler
  "Create a handler wrapped only with wrap-auth (no session middleware).
   Used for session-auth lifecycle tests where Ring's wrap-defaults
   would overwrite the injected :session from cookies."
  [system]
  (auth/wrap-auth
    (fn [req] {:status 200 :body (:auth/user req)})
    system))

;; ---------------------------------------------------------------------------
;; Test 1: JWT denied after user deactivation (soft-delete)
;; ---------------------------------------------------------------------------

(deftest jwt-denied-after-user-deactivation-test
  (let [system (make-system {})
        ds (:db system)
        handler (make-handler system)
        ;; Create user and issue JWT
        user (user-store/create-user! ds {:username "alice" :password "password1" :role "developer"})
        db-user (user-store/get-user-by-username ds "alice")
        jwt-user {:id (:id user) :username "alice" :role "developer"
                  :session-version (:session-version db-user)}
        token (auth/generate-jwt jwt-user (:config system))]

    (testing "JWT works before deactivation"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    ;; Deactivate user (soft-delete)
    (user-store/delete-user! ds (:id user))

    (testing "JWT is rejected after user deactivation"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Test 2: Session denied after user deactivation
;;   Uses wrap-auth directly (not full router) because Ring's wrap-defaults
;;   session middleware overwrites the injected :session from cookie state.
;; ---------------------------------------------------------------------------

(deftest session-denied-after-user-deactivation-test
  (let [system (make-system {})
        ds (:db system)
        handler (make-auth-handler system)
        user (user-store/create-user! ds {:username "bob" :password "password1" :role "developer"})
        db-user (user-store/get-user-by-username ds "bob")
        session-user {:id (:id user) :username "bob" :role :developer
                      :session-version (:session-version db-user)}]

    (testing "session works before deactivation"
      (let [resp (handler {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 200 (:status resp)))))

    ;; Deactivate user
    (user-store/delete-user! ds (:id user))

    (testing "session is rejected after user deactivation"
      (let [resp (handler {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 303 (:status resp)))
        (is (= "/login" (get-in resp [:headers "Location"])))))))

;; ---------------------------------------------------------------------------
;; Test 3: Session denied after password reset (session version bump)
;;   Uses wrap-auth directly for same reason as Test 2.
;; ---------------------------------------------------------------------------

(deftest session-denied-after-password-reset-test
  (let [system (make-system {})
        ds (:db system)
        handler (make-auth-handler system)
        user (user-store/create-user! ds {:username "carol" :password "password1" :role "developer"})
        db-user (user-store/get-user-by-username ds "carol")
        session-user {:id (:id user) :username "carol" :role :developer
                      :session-version (:session-version db-user)}]

    (testing "session works before password reset"
      (let [resp (handler {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 200 (:status resp)))))

    ;; Reset password — bumps session_version
    (user-store/update-password! ds (:id user) "newpassword!")

    (testing "session is invalidated after password reset"
      (let [resp (handler {:uri "/" :request-method :get :headers {}
                           :session {:user session-user}})]
        (is (= 303 (:status resp)))
        (is (= "/login" (get-in resp [:headers "Location"])))))))

;; ---------------------------------------------------------------------------
;; Test 4: API token denied after user deactivation
;; ---------------------------------------------------------------------------

(deftest api-token-denied-after-user-deactivation-test
  (let [system (make-system {})
        ds (:db system)
        handler (make-handler system)
        user (user-store/create-user! ds {:username "dave" :password "password1" :role "developer"})
        token-result (user-store/create-api-token! ds {:user-id (:id user) :name "test-token"})]

    (testing "API token works before deactivation"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " (:token token-result))
                                     "accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    ;; Deactivate user
    (user-store/delete-user! ds (:id user))

    (testing "API token is rejected after user deactivation"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " (:token token-result))
                                     "accept" "application/json"}})]
        (is (= 401 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Test 5: JWT denied after password reset (session version mismatch)
;; ---------------------------------------------------------------------------

(deftest jwt-denied-after-password-reset-test
  (let [system (make-system {})
        ds (:db system)
        handler (make-handler system)
        user (user-store/create-user! ds {:username "eve" :password "password1" :role "developer"})
        db-user (user-store/get-user-by-username ds "eve")
        jwt-user {:id (:id user) :username "eve" :role "developer"
                  :session-version (:session-version db-user)}
        token (auth/generate-jwt jwt-user (:config system))]

    (testing "JWT works before password reset"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 200 (:status resp)))))

    ;; Reset password — bumps session_version
    (user-store/update-password! ds (:id user) "newpassword!")

    (testing "JWT is rejected after password reset"
      (let [resp (handler {:uri "/api/approvals/pending" :request-method :get
                           :headers {"authorization" (str "Bearer " token)
                                     "accept" "application/json"}})]
        (is (= 401 (:status resp)))))))
