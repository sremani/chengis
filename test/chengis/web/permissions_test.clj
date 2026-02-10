(ns chengis.web.permissions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.db.permission-store :as permission-store]
            [chengis.web.permissions :as permissions]))

(def test-db-path "/tmp/chengis-web-permissions-test.db")

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

(defn- make-system
  "Create a minimal system map for testing."
  [ds & {:keys [feature-flag-on?] :or {feature-flag-on? false}}]
  {:db ds
   :config {:feature-flags {:fine-grained-rbac feature-flag-on?}}})

(defn- make-req
  "Create a minimal request map with a user."
  [user & {:keys [org-id] :or {org-id "org-1"}}]
  {:auth/user user
   :org/current {:id org-id}})

(defn- create-test-users!
  "Create test users and return their maps."
  [ds]
  (let [admin (user-store/create-user! ds {:username "admin" :password "password123" :role "admin"})
        dev (user-store/create-user! ds {:username "developer" :password "password123" :role "developer"})
        viewer (user-store/create-user! ds {:username "viewer" :password "password123" :role "viewer"})]
    {:admin admin :dev dev :viewer viewer}))

;; ---------------------------------------------------------------------------
;; has-permission? tests
;; ---------------------------------------------------------------------------

(deftest feature-flag-off-allows-all-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [viewer]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? false)
        req (make-req viewer)]
    (testing "has-permission? returns true when feature flag off"
      (is (true? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

(deftest admin-always-allowed-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [admin]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        req (make-req admin)]
    (testing "has-permission? returns true for admin user"
      (is (true? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

(deftest open-by-default-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [viewer]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        req (make-req viewer)]
    (testing "has-permission? returns true when no permissions configured (open-by-default)"
      ;; No permissions set on deploy-prod, so open-by-default applies
      (is (true? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

(deftest direct-permission-allows-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [dev]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        req (make-req dev)]
    (testing "has-permission? returns true when user has direct permission"
      ;; Grant permission to dev
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id (:id dev)
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"})
      (is (true? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

(deftest lacks-permission-denies-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [dev viewer]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        req (make-req viewer)]
    (testing "has-permission? returns false when user lacks permission"
      ;; Grant permission to dev (not viewer) to establish permissions on the resource
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id (:id dev)
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"})
      ;; Viewer has no permission
      (is (false? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

(deftest no-user-denies-test
  (let [ds (conn/create-datasource test-db-path)
        _ (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        req {:org/current {:id "org-1"}}]
    (testing "has-permission? returns false when no user in request"
      (is (false? (permissions/has-permission? system req "pipeline" "deploy-prod" "execute"))))))

;; ---------------------------------------------------------------------------
;; wrap-require-permission tests
;; ---------------------------------------------------------------------------

(deftest wrap-allows-admin-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [admin]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        handler (fn [req] {:status 200 :body "ok"})
        wrapped (permissions/wrap-require-permission
                  "pipeline" #(get-in % [:path-params :name]) "execute"
                  handler system)
        req (assoc (make-req admin) :path-params {:name "deploy-prod"})]
    (testing "wrap-require-permission allows admin through"
      (let [resp (wrapped req)]
        (is (= 200 (:status resp)))))))

(deftest wrap-blocks-unauthorized-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [dev viewer]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        handler (fn [req] {:status 200 :body "ok"})
        wrapped (permissions/wrap-require-permission
                  "pipeline" #(get-in % [:path-params :name]) "execute"
                  handler system)
        req (assoc (make-req viewer) :path-params {:name "deploy-prod"})]
    (testing "wrap-require-permission blocks unauthorized user"
      ;; Grant to dev to establish resource permissions, but not to viewer
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id (:id dev)
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"})
      (let [resp (wrapped req)]
        (is (= 403 (:status resp)))))))

(deftest wrap-allows-permitted-user-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [dev]} (create-test-users! ds)
        system (make-system ds :feature-flag-on? true)
        handler (fn [req] {:status 200 :body "ok"})
        wrapped (permissions/wrap-require-permission
                  "pipeline" #(get-in % [:path-params :name]) "execute"
                  handler system)
        req (assoc (make-req dev) :path-params {:name "deploy-prod"})]
    (testing "wrap-require-permission allows user with permission"
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id (:id dev)
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"})
      (let [resp (wrapped req)]
        (is (= 200 (:status resp)))))))
