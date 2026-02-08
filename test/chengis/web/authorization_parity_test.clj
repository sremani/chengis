(ns chengis.web.authorization-parity-test
  "Table-driven tests ensuring API and UI routes enforce the same minimum role.
   Catches policy drift between UI and API endpoints."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [chengis.web.routes :as routes]
            [chengis.metrics :as metrics]
            [clojure.core.async :as async]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-authz-parity.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- make-system []
  (let [ds (conn/create-datasource test-db-path)
        registry (try (metrics/init-registry) (catch Exception _ nil))
        audit-ch (async/chan 16)]
    {:db ds
     :config {:database {:path test-db-path}
              :auth {:enabled true
                     :jwt-secret "test-authz-parity-32-chars!!!!!!!!"}
              :distributed {:enabled false}
              :metrics {:enabled true :auth-required false}}
     :metrics registry
     :audit-writer {:channel audit-ch}}))

(defn- make-handler [system]
  (routes/app-routes system))

(defn- jwt-for [ds system username role]
  (let [db-user (user-store/get-user-by-username ds username)
        user {:id (:id db-user) :username username :role role
              :session-version (:session-version db-user)}]
    (auth/generate-jwt user (:config system))))

(defn- request-with-jwt [handler uri method jwt]
  (handler {:uri uri :request-method method
            :headers {"authorization" (str "Bearer " jwt)
                      "accept" "application/json"}}))

;; ---------------------------------------------------------------------------
;; Route matrix: {path method min-role}
;; ---------------------------------------------------------------------------

(def admin-routes
  [{:uri "/admin" :method :get}
   {:uri "/admin/audit" :method :get}
   {:uri "/admin/webhooks" :method :get}
   {:uri "/admin/users" :method :get}])

(def developer-routes
  [{:uri "/approvals" :method :get}
   {:uri "/api/approvals/pending" :method :get}])

(def viewer-routes
  [{:uri "/settings/tokens" :method :get}])

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest admin-only-routes-reject-developer-test
  (let [system (make-system)
        ds (:db system)
        handler (make-handler system)]
    (user-store/create-user! ds {:username "dev1" :password "password1" :role "developer"})
    (let [jwt (jwt-for ds system "dev1" "developer")]
      (testing "developer cannot access admin-only routes"
        (doseq [{:keys [uri method]} admin-routes]
          (let [resp (request-with-jwt handler uri method jwt)]
            (is (= 403 (:status resp))
                (str "Developer should be denied " method " " uri))))))))

(deftest developer-routes-reject-viewer-test
  (let [system (make-system)
        ds (:db system)
        handler (make-handler system)]
    (user-store/create-user! ds {:username "viewer1" :password "password1" :role "viewer"})
    (let [jwt (jwt-for ds system "viewer1" "viewer")]
      (testing "viewer cannot access developer routes"
        (doseq [{:keys [uri method]} developer-routes]
          (let [resp (request-with-jwt handler uri method jwt)]
            (is (= 403 (:status resp))
                (str "Viewer should be denied " method " " uri))))))))

(deftest viewer-routes-allow-viewer-test
  (let [system (make-system)
        ds (:db system)
        handler (make-handler system)]
    (user-store/create-user! ds {:username "viewer2" :password "password1" :role "viewer"})
    (let [jwt (jwt-for ds system "viewer2" "viewer")]
      (testing "viewer can access viewer-level routes"
        (doseq [{:keys [uri method]} viewer-routes]
          (let [resp (request-with-jwt handler uri method jwt)]
            (is (= 200 (:status resp))
                (str "Viewer should be allowed " method " " uri))))))))

(deftest approvals-api-matches-ui-policy-test
  (let [system (make-system)
        ds (:db system)
        handler (make-handler system)]
    (user-store/create-user! ds {:username "dev2" :password "password1" :role "developer"})
    (user-store/create-user! ds {:username "viewer3" :password "password1" :role "viewer"})
    (let [dev-jwt (jwt-for ds system "dev2" "developer")
          viewer-jwt (jwt-for ds system "viewer3" "viewer")]

      (testing "developer can access both UI and API approvals"
        (let [ui-resp (request-with-jwt handler "/approvals" :get dev-jwt)
              api-resp (request-with-jwt handler "/api/approvals/pending" :get dev-jwt)]
          (is (= 200 (:status ui-resp)))
          (is (= 200 (:status api-resp)))))

      (testing "viewer is denied both UI and API approvals equally"
        (let [ui-resp (request-with-jwt handler "/approvals" :get viewer-jwt)
              api-resp (request-with-jwt handler "/api/approvals/pending" :get viewer-jwt)]
          (is (= 403 (:status ui-resp)))
          (is (= 403 (:status api-resp))))))))
