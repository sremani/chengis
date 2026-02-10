(ns chengis.db.permission-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.db.permission-store :as permission-store]))

(def test-db-path "/tmp/chengis-permission-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Helper: create test users (FK constraint requires existing users)
;; ---------------------------------------------------------------------------

(defn- create-test-users!
  "Create test users and return their IDs."
  [ds]
  (let [alice (user-store/create-user! ds {:username "alice" :password "password123" :role "developer"})
        bob (user-store/create-user! ds {:username "bob" :password "password123" :role "viewer"})
        admin (user-store/create-user! ds {:username "adminuser" :password "password123" :role "admin"})]
    {:alice-id (:id alice) :bob-id (:id bob) :admin-id (:id admin)}))

;; ---------------------------------------------------------------------------
;; Direct permission tests
;; ---------------------------------------------------------------------------

(deftest grant-permission-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id admin-id]} (create-test-users! ds)]
    (testing "grant-permission! creates a permission"
      (let [perm (permission-store/grant-permission! ds
                   {:org-id "org-1"
                    :user-id alice-id
                    :resource-type "pipeline"
                    :resource-id "deploy-prod"
                    :action "execute"
                    :granted-by admin-id})]
        (is (some? (:id perm)))
        (is (= "pipeline" (:resource-type perm)))
        (is (= "deploy-prod" (:resource-id perm)))
        (is (= "execute" (:action perm)))))))

(deftest grant-permission-idempotent-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id admin-id]} (create-test-users! ds)]
    (testing "grant-permission! is idempotent (duplicate = no error)"
      (let [grant-data {:org-id "org-1"
                        :user-id alice-id
                        :resource-type "pipeline"
                        :resource-id "deploy-prod"
                        :action "execute"
                        :granted-by admin-id}]
        (permission-store/grant-permission! ds grant-data)
        ;; Second grant should not throw
        (permission-store/grant-permission! ds grant-data)
        ;; Should still have only one permission
        (let [perms (permission-store/list-user-permissions ds alice-id :org-id "org-1")]
          (is (= 1 (count perms))))))))

(deftest check-permission-granted-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id]} (create-test-users! ds)]
    (testing "check-permission returns true for granted permission"
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id alice-id
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"})
      (is (true? (permission-store/check-permission
                   ds "org-1" alice-id "pipeline" "deploy-prod" "execute"))))))

(deftest check-permission-not-granted-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [bob-id]} (create-test-users! ds)]
    (testing "check-permission returns false for non-granted permission"
      (is (false? (permission-store/check-permission
                    ds "org-1" bob-id "pipeline" "deploy-prod" "execute"))))))

(deftest revoke-permission-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id]} (create-test-users! ds)]
    (testing "revoke-permission! removes the permission"
      (let [perm (permission-store/grant-permission! ds
                   {:org-id "org-1"
                    :user-id alice-id
                    :resource-type "pipeline"
                    :resource-id "deploy-prod"
                    :action "execute"})]
        (is (true? (permission-store/check-permission
                     ds "org-1" alice-id "pipeline" "deploy-prod" "execute")))
        (permission-store/revoke-permission! ds (:id perm))
        (is (false? (permission-store/check-permission
                      ds "org-1" alice-id "pipeline" "deploy-prod" "execute")))))))

(deftest list-user-permissions-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id]} (create-test-users! ds)]
    (testing "list-user-permissions returns correct list"
      (permission-store/grant-permission! ds
        {:org-id "org-1" :user-id alice-id
         :resource-type "pipeline" :resource-id "deploy-prod" :action "execute"})
      (permission-store/grant-permission! ds
        {:org-id "org-1" :user-id alice-id
         :resource-type "secret" :resource-id "db-password" :action "read"})
      (permission-store/grant-permission! ds
        {:org-id "org-2" :user-id alice-id
         :resource-type "pipeline" :resource-id "other" :action "read"})
      (let [all-perms (permission-store/list-user-permissions ds alice-id)
            org1-perms (permission-store/list-user-permissions ds alice-id :org-id "org-1")
            secret-perms (permission-store/list-user-permissions ds alice-id
                           :org-id "org-1" :resource-type "secret")]
        (is (= 3 (count all-perms)))
        (is (= 2 (count org1-perms)))
        (is (= 1 (count secret-perms)))))))

(deftest list-resource-permissions-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id bob-id]} (create-test-users! ds)]
    (testing "list-resource-permissions returns correct resources"
      (permission-store/grant-permission! ds
        {:org-id "org-1" :user-id alice-id
         :resource-type "pipeline" :resource-id "deploy-prod" :action "execute"})
      (permission-store/grant-permission! ds
        {:org-id "org-1" :user-id bob-id
         :resource-type "pipeline" :resource-id "deploy-prod" :action "read"})
      (let [perms (permission-store/list-resource-permissions
                    ds "pipeline" "deploy-prod" :org-id "org-1")]
        (is (= 2 (count perms)))))))

;; ---------------------------------------------------------------------------
;; Permission group tests
;; ---------------------------------------------------------------------------

(deftest group-lifecycle-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id bob-id admin-id]} (create-test-users! ds)]
    (testing "create-group!, add-group-entry!, add-group-member!"
      (let [group (permission-store/create-group! ds
                    {:org-id "org-1"
                     :name "backend-devs"
                     :description "Backend developers"
                     :created-by admin-id})]
        (is (some? (:id group)))
        (is (= "backend-devs" (:name group)))

        ;; Add entries
        (let [entry (permission-store/add-group-entry! ds
                      {:group-id (:id group)
                       :resource-type "pipeline"
                       :resource-id "deploy-prod"
                       :action "execute"})]
          (is (some? (:id entry))))

        ;; Add members
        (let [member (permission-store/add-group-member! ds
                       {:group-id (:id group)
                        :user-id alice-id
                        :assigned-by admin-id})]
          (is (some? (:id member))))

        ;; Verify entries and members
        (let [entries (permission-store/list-group-entries ds (:id group))
              members (permission-store/list-group-members ds (:id group))]
          (is (= 1 (count entries)))
          (is (= 1 (count members))))

        ;; Get group
        (let [retrieved (permission-store/get-group ds (:id group))]
          (is (= "backend-devs" (:name retrieved))))

        ;; List groups
        (let [groups (permission-store/list-groups ds :org-id "org-1")]
          (is (= 1 (count groups))))

        ;; Delete group (should cascade)
        (permission-store/delete-group! ds (:id group))
        (is (nil? (permission-store/get-group ds (:id group))))
        (is (empty? (permission-store/list-group-entries ds (:id group))))
        (is (empty? (permission-store/list-group-members ds (:id group))))))))

(deftest check-permission-via-group-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id admin-id]} (create-test-users! ds)]
    (testing "check-permission works through groups"
      ;; Alice has no direct permission
      (is (false? (permission-store/check-permission
                    ds "org-1" alice-id "pipeline" "deploy-staging" "execute")))

      ;; Create group, add entry, add Alice
      (let [group (permission-store/create-group! ds
                    {:org-id "org-1" :name "staging-deployers" :created-by admin-id})]
        (permission-store/add-group-entry! ds
          {:group-id (:id group)
           :resource-type "pipeline"
           :resource-id "deploy-staging"
           :action "execute"})
        (permission-store/add-group-member! ds
          {:group-id (:id group)
           :user-id alice-id
           :assigned-by admin-id})

        ;; Now check should pass via group
        (is (true? (permission-store/check-permission
                     ds "org-1" alice-id "pipeline" "deploy-staging" "execute")))

        ;; But not for a different action
        (is (false? (permission-store/check-permission
                      ds "org-1" alice-id "pipeline" "deploy-staging" "delete")))))))

(deftest effective-permissions-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id admin-id]} (create-test-users! ds)]
    (testing "effective-permissions merges direct + group permissions"
      ;; Direct permission
      (permission-store/grant-permission! ds
        {:org-id "org-1" :user-id alice-id
         :resource-type "pipeline" :resource-id "deploy-prod" :action "execute"})

      ;; Group-based permission
      (let [group (permission-store/create-group! ds
                    {:org-id "org-1" :name "secret-readers" :created-by admin-id})]
        (permission-store/add-group-entry! ds
          {:group-id (:id group)
           :resource-type "secret"
           :resource-id "db-password"
           :action "read"})
        (permission-store/add-group-member! ds
          {:group-id (:id group)
           :user-id alice-id
           :assigned-by admin-id}))

      (let [effective (permission-store/effective-permissions ds "org-1" alice-id)]
        (is (= 2 (count effective)))
        (is (some #(and (= "pipeline" (:resource-type %))
                        (= "direct" (:source %)))
                  effective))
        (is (some #(and (= "secret" (:resource-type %))
                        (= "group" (:source %)))
                  effective))))))

(deftest expired-permission-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [alice-id]} (create-test-users! ds)]
    (testing "expired permissions are not returned by check-permission"
      ;; Grant with an already-expired timestamp
      (permission-store/grant-permission! ds
        {:org-id "org-1"
         :user-id alice-id
         :resource-type "pipeline"
         :resource-id "deploy-prod"
         :action "execute"
         :expires-at "2020-01-01 00:00:00"})
      (is (false? (permission-store/check-permission
                    ds "org-1" alice-id "pipeline" "deploy-prod" "execute"))))))
