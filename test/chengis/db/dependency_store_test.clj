(ns chengis.db.dependency-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.dependency-store :as dep-store]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-dependency-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Dependency CRUD
;; ---------------------------------------------------------------------------

(deftest create-and-list-dependencies
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can create and list job dependencies"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (dep-store/create-dependency! ds
        {:job-id "job-C" :depends-on-job-id "job-A" :org-id "org-1"})

      (let [deps (dep-store/list-dependencies ds "job-B")]
        (is (= 1 (count deps)))
        (is (= "job-A" (:depends-on-job-id (first deps)))))

      ;; B and C both depend on A
      (let [dependents (dep-store/list-dependents ds "job-A")]
        (is (= 2 (count dependents)))
        (is (= #{"job-B" "job-C"} (set (map :job-id dependents))))))))

(deftest list-dependencies-org-scoped
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-dependents with org-id scopes correctly"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (dep-store/create-dependency! ds
        {:job-id "job-D" :depends-on-job-id "job-A" :org-id "org-2"})

      (is (= 1 (count (dep-store/list-dependents ds "job-A" :org-id "org-1"))))
      (is (= 1 (count (dep-store/list-dependents ds "job-A" :org-id "org-2"))))
      (is (= 2 (count (dep-store/list-dependents ds "job-A")))))))

(deftest delete-dependency
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can delete a dependency"
      (let [dep (dep-store/create-dependency! ds
                  {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})]
        (is (= 1 (count (dep-store/list-dependents ds "job-A"))))
        (is (true? (dep-store/delete-dependency! ds (:id dep))))
        (is (= 0 (count (dep-store/list-dependents ds "job-A"))))))))

(deftest delete-dependency-respects-org
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete-dependency! with wrong org fails"
      (let [dep (dep-store/create-dependency! ds
                  {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})]
        (is (false? (dep-store/delete-dependency! ds (:id dep) :org-id "org-2")))
        (is (= 1 (count (dep-store/list-dependents ds "job-A"))))))))

(deftest delete-job-dependencies-both-directions
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete-job-dependencies! removes both directions"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (dep-store/create-dependency! ds
        {:job-id "job-A" :depends-on-job-id "job-C" :org-id "org-1"})

      ;; Delete all deps involving job-A
      (dep-store/delete-job-dependencies! ds "job-A")

      (is (= 0 (count (dep-store/list-dependencies ds "job-B"))))
      (is (= 0 (count (dep-store/list-dependencies ds "job-A")))))))

;; ---------------------------------------------------------------------------
;; Cycle detection
;; ---------------------------------------------------------------------------

(deftest cycle-detection-direct
  (let [ds (conn/create-datasource test-db-path)]
    (testing "detects direct cycle A->B->A"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      ;; Adding A depends on B would create A->B->A cycle
      (is (true? (dep-store/has-cycle? ds "job-A" "job-B"))))))

(deftest cycle-detection-transitive
  (let [ds (conn/create-datasource test-db-path)]
    (testing "detects transitive cycle A->B->C->A"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (dep-store/create-dependency! ds
        {:job-id "job-C" :depends-on-job-id "job-B" :org-id "org-1"})
      ;; Adding A depends on C would create A->B->C->A cycle
      (is (true? (dep-store/has-cycle? ds "job-A" "job-C"))))))

(deftest no-false-cycle
  (let [ds (conn/create-datasource test-db-path)]
    (testing "does not flag valid dependency as cycle"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      ;; C depends on B is fine (A->B->C, no cycle)
      (is (false? (dep-store/has-cycle? ds "job-C" "job-B"))))))

;; ---------------------------------------------------------------------------
;; Trigger tracking
;; ---------------------------------------------------------------------------

(deftest record-and-get-triggers
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can record and retrieve dependency triggers"
      (dep-store/record-trigger! ds
        {:source-build-id "build-1" :source-job-id "job-A"
         :target-build-id "build-2" :target-job-id "job-B"
         :org-id "org-1" :trigger-status "success"})

      (let [triggers (dep-store/get-triggered-builds ds "build-1")]
        (is (= 1 (count triggers)))
        (is (= "build-2" (:target-build-id (first triggers)))))

      (let [chain (dep-store/get-trigger-chain ds "build-2")]
        (is (= 1 (count chain)))
        (is (= "build-1" (:source-build-id (first chain))))))))

(deftest trigger-on-values
  (let [ds (conn/create-datasource test-db-path)]
    (testing "trigger-on field stored correctly"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"
         :trigger-on "failure"})
      (let [dep (first (dep-store/list-dependencies ds "job-B"))]
        (is (= "failure" (:trigger-on dep)))))))
