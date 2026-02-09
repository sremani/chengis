(ns chengis.db.org-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.org-store :as org-store]
            [chengis.db.user-store :as user-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-org-store-test.db")

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
;; Organization CRUD
;; ---------------------------------------------------------------------------

(deftest create-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create organization with all fields"
      (let [org (org-store/create-org! ds {:name "Acme Corp"
                                           :slug "acme"
                                           :description "Test org"})]
        (is (some? (:id org)))
        (is (= "Acme Corp" (:name org)))
        (is (= "acme" (:slug org)))
        (is (= "Test org" (:description org)))))

    (testing "create organization without description"
      (let [org (org-store/create-org! ds {:name "Beta Inc"
                                           :slug "beta"})]
        (is (some? (:id org)))
        (is (= "Beta Inc" (:name org)))
        (is (nil? (:description org)))))

    (testing "duplicate slug throws"
      (is (thrown? Exception
            (org-store/create-org! ds {:name "Acme Duplicate"
                                       :slug "acme"}))))))

(deftest get-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get org by ID"
      (let [created (org-store/create-org! ds {:name "Test Org" :slug "test-get"})
            fetched (org-store/get-org ds (:id created))]
        (is (some? fetched))
        (is (= "Test Org" (:name fetched)))
        (is (= "test-get" (:slug fetched)))))

    (testing "get org by slug"
      (let [fetched (org-store/get-org-by-slug ds "test-get")]
        (is (some? fetched))
        (is (= "Test Org" (:name fetched)))))

    (testing "get nonexistent org returns nil"
      (is (nil? (org-store/get-org ds "nonexistent-id")))
      (is (nil? (org-store/get-org-by-slug ds "nonexistent-slug"))))))

(deftest list-orgs-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list includes seeded default org"
      (let [orgs (org-store/list-orgs ds)]
        ;; Migration 025 seeds the default org
        (is (>= (count orgs) 1))
        (is (some #(= "default-org" (:id %)) orgs))))

    (testing "list includes newly created orgs"
      (org-store/create-org! ds {:name "Org A" :slug "org-a"})
      (org-store/create-org! ds {:name "Org B" :slug "org-b"})
      (let [orgs (org-store/list-orgs ds)]
        (is (>= (count orgs) 3))
        (is (some #(= "org-a" (:slug %)) orgs))
        (is (some #(= "org-b" (:slug %)) orgs))))))

(deftest update-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "update org name and description"
      (let [org (org-store/create-org! ds {:name "Old Name" :slug "update-test"})]
        (org-store/update-org! ds (:id org) {:name "New Name"
                                              :description "Updated desc"})
        (let [updated (org-store/get-org ds (:id org))]
          (is (= "New Name" (:name updated)))
          (is (= "Updated desc" (:description updated))))))))

(deftest delete-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete removes org and memberships"
      (let [org (org-store/create-org! ds {:name "Delete Me" :slug "delete-me"})
            user (user-store/create-user! ds {:username "del-user"
                                              :password "pass123"
                                              :role "developer"})]
        (org-store/add-member! ds {:org-id (:id org)
                                   :user-id (:id user)
                                   :role "developer"})
        ;; Verify member exists
        (is (some? (org-store/get-membership ds (:id org) (:id user))))

        ;; Delete org
        (is (true? (org-store/delete-org! ds (:id org))))

        ;; Org gone
        (is (nil? (org-store/get-org ds (:id org))))
        ;; Membership gone
        (is (nil? (org-store/get-membership ds (:id org) (:id user))))))

    (testing "delete nonexistent org returns false"
      (is (false? (org-store/delete-org! ds "nonexistent"))))))

;; ---------------------------------------------------------------------------
;; Membership management
;; ---------------------------------------------------------------------------

(deftest add-member-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "Member Test" :slug "member-test"})
        user (user-store/create-user! ds {:username "member1"
                                          :password "pass123"
                                          :role "developer"})]
    (testing "add member with explicit role"
      (let [mem (org-store/add-member! ds {:org-id (:id org)
                                           :user-id (:id user)
                                           :role "admin"})]
        (is (some? (:id mem)))
        (is (= (:id org) (:org-id mem)))
        (is (= (:id user) (:user-id mem)))
        (is (= "admin" (:role mem)))))

    (testing "add member with default role"
      (let [user2 (user-store/create-user! ds {:username "member2"
                                               :password "pass123"
                                               :role "viewer"})
            mem (org-store/add-member! ds {:org-id (:id org)
                                           :user-id (:id user2)})]
        (is (= "viewer" (:role mem)))))

    (testing "duplicate membership throws"
      (is (thrown? Exception
            (org-store/add-member! ds {:org-id (:id org)
                                       :user-id (:id user)
                                       :role "viewer"}))))))

(deftest remove-member-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "Remove Test" :slug "remove-test"})
        user (user-store/create-user! ds {:username "removable"
                                          :password "pass123"
                                          :role "developer"})]
    (org-store/add-member! ds {:org-id (:id org)
                               :user-id (:id user)
                               :role "developer"})
    (testing "remove existing member"
      (is (true? (org-store/remove-member! ds (:id org) (:id user))))
      (is (nil? (org-store/get-membership ds (:id org) (:id user)))))

    (testing "remove nonexistent member returns false"
      (is (false? (org-store/remove-member! ds (:id org) "nonexistent"))))))

(deftest update-member-role-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "Role Test" :slug "role-test"})
        user (user-store/create-user! ds {:username "role-user"
                                          :password "pass123"
                                          :role "viewer"})]
    (org-store/add-member! ds {:org-id (:id org)
                               :user-id (:id user)
                               :role "viewer"})
    (testing "update role from viewer to admin"
      (org-store/update-member-role! ds (:id org) (:id user) "admin")
      (let [mem (org-store/get-membership ds (:id org) (:id user))]
        (is (= "admin" (:role mem)))))))

(deftest get-membership-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "Get Mem Test" :slug "get-mem"})
        user (user-store/create-user! ds {:username "mem-get-user"
                                          :password "pass123"
                                          :role "developer"})]
    (org-store/add-member! ds {:org-id (:id org)
                               :user-id (:id user)
                               :role "developer"})
    (testing "get existing membership"
      (let [mem (org-store/get-membership ds (:id org) (:id user))]
        (is (some? mem))
        (is (= "developer" (:role mem)))
        (is (= (:id org) (:org-id mem)))
        (is (= (:id user) (:user-id mem)))))

    (testing "get nonexistent membership returns nil"
      (is (nil? (org-store/get-membership ds (:id org) "nonexistent-user"))))))

(deftest list-members-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "List Mem" :slug "list-mem"})
        u1 (user-store/create-user! ds {:username "lm-user1"
                                        :password "pass123"
                                        :role "admin"})
        u2 (user-store/create-user! ds {:username "lm-user2"
                                        :password "pass123"
                                        :role "developer"})]
    (org-store/add-member! ds {:org-id (:id org) :user-id (:id u1) :role "admin"})
    (org-store/add-member! ds {:org-id (:id org) :user-id (:id u2) :role "developer"})

    (testing "list all members of org"
      (let [members (org-store/list-members ds (:id org))]
        (is (= 2 (count members)))
        ;; Verify joined username
        (is (some #(= "lm-user1" (:username %)) members))
        (is (some #(= "lm-user2" (:username %)) members))))

    (testing "list members of empty org"
      (let [empty-org (org-store/create-org! ds {:name "Empty" :slug "empty"})
            members (org-store/list-members ds (:id empty-org))]
        (is (= 0 (count members)))))))

(deftest list-user-orgs-test
  (let [ds (conn/create-datasource test-db-path)
        org1 (org-store/create-org! ds {:name "User Org 1" :slug "user-org-1"})
        org2 (org-store/create-org! ds {:name "User Org 2" :slug "user-org-2"})
        user (user-store/create-user! ds {:username "multi-org-user"
                                          :password "pass123"
                                          :role "developer"})]
    (org-store/add-member! ds {:org-id (:id org1) :user-id (:id user) :role "admin"})
    (org-store/add-member! ds {:org-id (:id org2) :user-id (:id user) :role "viewer"})

    (testing "user sees both orgs with correct roles"
      (let [orgs (org-store/list-user-orgs ds (:id user))]
        (is (= 2 (count orgs)))
        (let [o1 (first (filter #(= (:org-id %) (:id org1)) orgs))
              o2 (first (filter #(= (:org-id %) (:id org2)) orgs))]
          (is (= "admin" (:role o1)))
          (is (= "User Org 1" (:org-name o1)))
          (is (= "user-org-1" (:org-slug o1)))
          (is (= "viewer" (:role o2)))
          (is (= "User Org 2" (:org-name o2))))))

    (testing "user with no memberships returns empty"
      (let [lonely (user-store/create-user! ds {:username "lonely-user"
                                                :password "pass123"
                                                :role "viewer"})
            orgs (org-store/list-user-orgs ds (:id lonely))]
        (is (= 0 (count orgs)))))))

(deftest count-members-test
  (let [ds (conn/create-datasource test-db-path)
        org (org-store/create-org! ds {:name "Count Test" :slug "count-test"})
        u1 (user-store/create-user! ds {:username "cnt-user1" :password "p" :role "viewer"})
        u2 (user-store/create-user! ds {:username "cnt-user2" :password "p" :role "viewer"})]
    (testing "empty org has 0 members"
      (is (= 0 (org-store/count-members ds (:id org)))))

    (testing "count increases with members"
      (org-store/add-member! ds {:org-id (:id org) :user-id (:id u1) :role "viewer"})
      (is (= 1 (org-store/count-members ds (:id org))))
      (org-store/add-member! ds {:org-id (:id org) :user-id (:id u2) :role "developer"})
      (is (= 2 (org-store/count-members ds (:id org)))))))

;; ---------------------------------------------------------------------------
;; Default org helpers
;; ---------------------------------------------------------------------------

(deftest default-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "default org exists from migration"
      (let [org (org-store/get-org ds org-store/default-org-id)]
        (is (some? org))
        (is (= "Default" (:name org)))
        (is (= "default" (:slug org)))))

    (testing "ensure-default-org! is idempotent"
      (org-store/ensure-default-org! ds)
      (let [orgs (org-store/list-orgs ds)
            defaults (filter #(= "default-org" (:id %)) orgs)]
        (is (= 1 (count defaults)))))))

(deftest ensure-user-has-org-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "orphan-user"
                                          :password "pass123"
                                          :role "developer"})]
    (testing "user with no orgs gets assigned to default"
      (is (= 0 (count (org-store/list-user-orgs ds (:id user)))))
      (org-store/ensure-user-has-org! ds (:id user) "developer")
      (let [orgs (org-store/list-user-orgs ds (:id user))]
        (is (= 1 (count orgs)))
        (is (= "default-org" (:org-id (first orgs))))
        (is (= "developer" (:role (first orgs))))))

    (testing "user with existing org is not re-assigned"
      (org-store/ensure-user-has-org! ds (:id user) "viewer")
      (let [orgs (org-store/list-user-orgs ds (:id user))]
        ;; Still just 1 membership, not 2
        (is (= 1 (count orgs)))
        ;; Role unchanged (still developer, not viewer)
        (is (= "developer" (:role (first orgs))))))))

;; ---------------------------------------------------------------------------
;; Migration 026: Existing users assigned to default org
;; ---------------------------------------------------------------------------

(deftest migration-assigns-users-to-default-org-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Migration 026 runs INSERT INTO org_memberships ... FROM users
    ;; But in tests, users are created AFTER migration.
    ;; This test verifies that the admin seed user (created during auth migration)
    ;; was assigned to default org by migration 026.
    (testing "admin seed user has default org membership"
      (let [admin (user-store/get-user-by-username ds "admin")]
        (when admin
          (let [mem (org-store/get-membership ds "default-org" (:id admin))]
            (is (some? mem) "Admin should have default org membership")))))))
