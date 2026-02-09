(ns chengis.db.plugin-policy-store-test
  "Tests for plugin trust policy store: CRUD, allowlist check, org isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.plugin-policy-store :as store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-plugin-policy-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest set-and-get-plugin-policy-test
  (testing "set and retrieve a plugin policy"
    (let [ds (conn/create-datasource test-db-path)]
      (store/set-plugin-policy! ds
        {:org-id "org-1"
         :plugin-name "my-notifier"
         :trust-level "trusted"
         :allowed true
         :created-by "admin"})
      (let [policy (store/get-plugin-policy ds "my-notifier" :org-id "org-1")]
        (is (some? policy)
            "Policy should be retrievable")
        (is (= "my-notifier" (:plugin-name policy))
            "Plugin name should match")
        (is (= "trusted" (:trust-level policy))
            "Trust level should match")
        (is (= 1 (:allowed policy))
            "Allowed should be 1 (truthy) in raw DB result")))))

(deftest plugin-allowed-returns-false-for-untrusted-test
  (testing "plugin-allowed? returns false when policy exists but allowed=false"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Set policy with allowed=false
      (store/set-plugin-policy! ds
        {:org-id "org-1"
         :plugin-name "evil-plugin"
         :trust-level "untrusted"
         :allowed false})
      (is (false? (store/plugin-allowed? ds "evil-plugin" :org-id "org-1"))
          "Untrusted plugin should not be allowed"))))

(deftest plugin-allowed-returns-true-for-allowed-test
  (testing "plugin-allowed? returns true for explicitly allowed plugins"
    (let [ds (conn/create-datasource test-db-path)]
      (store/set-plugin-policy! ds
        {:org-id "org-1"
         :plugin-name "good-plugin"
         :trust-level "trusted"
         :allowed true})
      (is (true? (store/plugin-allowed? ds "good-plugin" :org-id "org-1"))
          "Allowed plugin should return true"))))

(deftest plugin-allowed-returns-false-when-no-policy-test
  (testing "plugin-allowed? returns false when no policy exists"
    (let [ds (conn/create-datasource test-db-path)]
      (is (false? (store/plugin-allowed? ds "nonexistent-plugin" :org-id "org-1"))
          "Plugin with no policy should not be allowed"))))

(deftest org-scoped-isolation-test
  (testing "plugin policies are scoped to org"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Allow plugin for org-1 only
      (store/set-plugin-policy! ds
        {:org-id "org-1"
         :plugin-name "shared-plugin"
         :trust-level "trusted"
         :allowed true})
      (is (true? (store/plugin-allowed? ds "shared-plugin" :org-id "org-1"))
          "Plugin should be allowed for org-1")
      (is (false? (store/plugin-allowed? ds "shared-plugin" :org-id "org-2"))
          "Plugin should NOT be allowed for org-2")
      ;; List policies per org
      (let [org1-policies (store/list-plugin-policies ds :org-id "org-1")
            org2-policies (store/list-plugin-policies ds :org-id "org-2")]
        (is (= 1 (count org1-policies))
            "Org-1 should have 1 policy")
        (is (= 0 (count org2-policies))
            "Org-2 should have 0 policies")))))

(deftest update-existing-policy-test
  (testing "setting a policy again updates it (upsert)"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Create initial policy with allowed=false
      (store/set-plugin-policy! ds
        {:org-id "org-1" :plugin-name "evolving-plugin"
         :trust-level "untrusted" :allowed false})
      (is (false? (store/plugin-allowed? ds "evolving-plugin" :org-id "org-1"))
          "Initially should be blocked")
      ;; Update to allowed=true
      (store/set-plugin-policy! ds
        {:org-id "org-1" :plugin-name "evolving-plugin"
         :trust-level "reviewed" :allowed true})
      (is (true? (store/plugin-allowed? ds "evolving-plugin" :org-id "org-1"))
          "After update should be allowed"))))
