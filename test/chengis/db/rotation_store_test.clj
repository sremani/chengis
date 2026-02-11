(ns chengis.db.rotation-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.rotation-store :as rotation-store]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-rotation-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

;; ---------------------------------------------------------------------------
;; Policy CRUD
;; ---------------------------------------------------------------------------

(deftest create-policy-test
  (let [ds (test-ds)]
    (testing "create-policy! creates a policy and returns it"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1"
                      :secret-name "API_KEY"
                      :secret-scope "global"
                      :rotation-interval-days 30
                      :max-versions 5
                      :notify-days-before 7
                      :created-by "user-1"})]
        (is (some? (:id policy)))
        (is (= "org-1" (:org-id policy)))
        (is (= "API_KEY" (:secret-name policy)))
        (is (= "global" (:secret-scope policy)))
        (is (= 30 (:rotation-interval-days policy)))
        (is (= 5 (:max-versions policy)))
        (is (= 7 (:notify-days-before policy)))
        (is (= 1 (:enabled policy)))
        (is (some? (:next-rotation-at policy)))))))

(deftest get-policy-test
  (let [ds (test-ds)]
    (testing "get-policy retrieves a created policy"
      (let [created (rotation-store/create-policy! ds
                      {:org-id "org-1"
                       :secret-name "DB_PASSWORD"
                       :secret-scope "global"
                       :rotation-interval-days 60})
            retrieved (rotation-store/get-policy ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))
        (is (= "DB_PASSWORD" (:secret-name retrieved)))))

    (testing "get-policy with org-id scoping"
      (let [created (rotation-store/create-policy! ds
                      {:org-id "org-2"
                       :secret-name "TOKEN"
                       :secret-scope "global"
                       :rotation-interval-days 45})]
        ;; Should find with correct org
        (is (some? (rotation-store/get-policy ds (:id created) :org-id "org-2")))
        ;; Should not find with wrong org
        (is (nil? (rotation-store/get-policy ds (:id created) :org-id "org-other")))))))

(deftest list-policies-test
  (let [ds (test-ds)]
    (testing "list-policies returns correct policies"
      (rotation-store/create-policy! ds
        {:org-id "org-1" :secret-name "KEY_A" :secret-scope "global"
         :rotation-interval-days 30})
      (rotation-store/create-policy! ds
        {:org-id "org-1" :secret-name "KEY_B" :secret-scope "global"
         :rotation-interval-days 60})
      (rotation-store/create-policy! ds
        {:org-id "org-2" :secret-name "KEY_C" :secret-scope "global"
         :rotation-interval-days 90})

      ;; All policies
      (is (= 3 (count (rotation-store/list-policies ds))))
      ;; Org-scoped
      (is (= 2 (count (rotation-store/list-policies ds :org-id "org-1"))))
      (is (= 1 (count (rotation-store/list-policies ds :org-id "org-2")))))))

(deftest update-policy-test
  (let [ds (test-ds)]
    (testing "update-policy! updates fields"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "SECRET_X" :secret-scope "global"
                      :rotation-interval-days 30 :max-versions 3})]
        (rotation-store/update-policy! ds (:id policy)
          {:rotation-interval-days 60 :max-versions 10 :enabled 0})
        (let [updated (rotation-store/get-policy ds (:id policy))]
          (is (= 60 (:rotation-interval-days updated)))
          (is (= 10 (:max-versions updated)))
          (is (= 0 (:enabled updated))))))))

(deftest delete-policy-test
  (let [ds (test-ds)]
    (testing "delete-policy! removes policy"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "TO_DELETE" :secret-scope "global"
                      :rotation-interval-days 30})]
        (is (true? (rotation-store/delete-policy! ds (:id policy))))
        (is (nil? (rotation-store/get-policy ds (:id policy))))))

    (testing "delete-policy! with wrong org fails"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "KEEP_ME" :secret-scope "global"
                      :rotation-interval-days 30})]
        (is (false? (rotation-store/delete-policy! ds (:id policy) :org-id "org-other")))
        (is (some? (rotation-store/get-policy ds (:id policy))))))))

;; ---------------------------------------------------------------------------
;; Version history
;; ---------------------------------------------------------------------------

(deftest record-and-list-versions-test
  (let [ds (test-ds)]
    (testing "record-version! and list-versions work correctly"
      (rotation-store/record-version! ds
        {:org-id "org-1" :secret-name "API_KEY" :secret-scope "global"
         :version 1 :rotated-by "user-1" :rotation-reason "scheduled"
         :previous-value-hash "abc123"})
      (rotation-store/record-version! ds
        {:org-id "org-1" :secret-name "API_KEY" :secret-scope "global"
         :version 2 :rotated-by "user-1" :rotation-reason "manual"
         :previous-value-hash "def456"})

      (let [versions (rotation-store/list-versions ds "org-1" "API_KEY")]
        (is (= 2 (count versions)))
        ;; Ordered by version desc
        (is (= 2 (:version (first versions))))
        (is (= 1 (:version (second versions))))))))

(deftest latest-version-test
  (let [ds (test-ds)]
    (testing "latest-version returns correct version number"
      ;; No versions yet
      (is (= 0 (rotation-store/latest-version ds "org-1" "NEW_SECRET" "global")))

      ;; Add versions
      (rotation-store/record-version! ds
        {:org-id "org-1" :secret-name "NEW_SECRET" :secret-scope "global"
         :version 1 :rotated-by "system"})
      (is (= 1 (rotation-store/latest-version ds "org-1" "NEW_SECRET" "global")))

      (rotation-store/record-version! ds
        {:org-id "org-1" :secret-name "NEW_SECRET" :secret-scope "global"
         :version 2 :rotated-by "system"})
      (is (= 2 (rotation-store/latest-version ds "org-1" "NEW_SECRET" "global"))))))

;; ---------------------------------------------------------------------------
;; Due for rotation
;; ---------------------------------------------------------------------------

(deftest policies-due-for-rotation-test
  (let [ds (test-ds)]
    (testing "policies-due-for-rotation returns policies with past next_rotation_at"
      ;; Create a policy, then manually set next_rotation_at to the past
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "OVERDUE" :secret-scope "global"
                      :rotation-interval-days 1})]
        ;; Force next_rotation_at into the past
        (next.jdbc/execute-one! ds
          ["UPDATE secret_rotation_policies SET next_rotation_at = '2020-01-01T00:00:00' WHERE id = ?"
           (:id policy)])
        ;; Create another policy that is NOT due (far future)
        (rotation-store/create-policy! ds
          {:org-id "org-1" :secret-name "NOT_DUE" :secret-scope "global"
           :rotation-interval-days 9999})

        (let [due (rotation-store/policies-due-for-rotation ds)]
          (is (= 1 (count due)))
          (is (= "OVERDUE" (:secret-name (first due)))))))))

;; ---------------------------------------------------------------------------
;; Cleanup old versions
;; ---------------------------------------------------------------------------

(deftest cleanup-old-versions-test
  (let [ds (test-ds)]
    (testing "cleanup-old-versions! removes excess versions"
      ;; Insert 5 versions
      (doseq [v (range 1 6)]
        (rotation-store/record-version! ds
          {:org-id "org-1" :secret-name "ROTATE_ME" :secret-scope "global"
           :version v :rotated-by "system"}))

      ;; Should have 5 versions
      (is (= 5 (count (rotation-store/list-versions ds "org-1" "ROTATE_ME"))))

      ;; Cleanup keeping max 3
      (rotation-store/cleanup-old-versions! ds "org-1" "ROTATE_ME" "global" 3)

      ;; Should have 3 versions remaining (versions 3, 4, 5)
      (let [remaining (rotation-store/list-versions ds "org-1" "ROTATE_ME")]
        (is (= 3 (count remaining)))
        (is (= 5 (:version (first remaining))))
        (is (= 3 (:version (last remaining)))))))

;; ---------------------------------------------------------------------------
;; Phase 2d: or-fallback defaults for rotation policy creation
;; ---------------------------------------------------------------------------

(deftest create-policy-or-fallback-defaults-test
  (let [ds (test-ds)]
    (testing "rotation-interval-days defaults to 90 when not specified"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "S1" :created-by "admin"})]
        (is (= 90 (:rotation-interval-days policy)))))

    (testing "secret-scope defaults to 'global' when not specified"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "S2" :created-by "admin"})]
        (is (= "global" (:secret-scope policy)))))

    (testing "max-versions defaults to 3 when not specified"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "S3" :created-by "admin"})]
        (is (= 3 (:max-versions policy)))))

    (testing "notify-days-before defaults to 7 when not specified"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "S4" :created-by "admin"})]
        (is (= 7 (:notify-days-before policy)))))

    (testing "explicit values override defaults"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1" :secret-name "S5" :created-by "admin"
                      :rotation-interval-days 30 :secret-scope "project"
                      :max-versions 10 :notify-days-before 14})]
        (is (= 30 (:rotation-interval-days policy)))
        (is (= "project" (:secret-scope policy)))
        (is (= 10 (:max-versions policy)))
        (is (= 14 (:notify-days-before policy))))))))
