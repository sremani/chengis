(ns chengis.db.build-attempt-test
  "Tests for build attempt tracking: attempt_number, root_build_id,
   list-attempts, and get-root-build-id."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-attempt-test.db")

(def test-pipeline
  {:pipeline-name "attempt-test-job"
   :description "Test pipeline for attempt tracking"
   :stages [{:stage-name "Build"
             :steps [{:step-name "Compile"
                      :type :shell
                      :command "echo ok"}]}]})

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest first-build-has-attempt-1-test
  (testing "first build has attempt_number=1 and no root_build_id"
    (let [ds (conn/create-datasource test-db-path)
          _ (job-store/create-job! ds test-pipeline)
          job (job-store/get-job ds "attempt-test-job")
          build (build-store/create-build! ds
                  {:job-id (:id job)
                   :trigger-type :manual})]
      (is (= 1 (:attempt-number build))
          "First build should be attempt #1")
      (is (nil? (:root-build-id build))
          "First build should have no root-build-id"))))

(deftest retry-sets-attempt-2-test
  (testing "retrying a build sets attempt_number=2 and root_build_id=original"
    (let [ds (conn/create-datasource test-db-path)
          _ (job-store/create-job! ds test-pipeline)
          job (job-store/get-job ds "attempt-test-job")
          build-1 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :manual})
          build-2 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-1)})]
      (is (= 2 (:attempt-number build-2))
          "Retry should be attempt #2")
      (is (= (:id build-1) (:root-build-id build-2))
          "Root build id should point to original build"))))

(deftest retry-of-retry-sets-attempt-3-test
  (testing "retrying a retry sets attempt_number=3 with same root_build_id"
    (let [ds (conn/create-datasource test-db-path)
          _ (job-store/create-job! ds test-pipeline)
          job (job-store/get-job ds "attempt-test-job")
          build-1 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :manual})
          build-2 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-1)})
          build-3 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-2)})]
      (is (= 3 (:attempt-number build-3))
          "Retry-of-retry should be attempt #3")
      (is (= (:id build-1) (:root-build-id build-3))
          "Root build id should still be the original build"))))

(deftest list-attempts-returns-all-in-order-test
  (testing "list-attempts returns all attempts ordered by attempt_number"
    (let [ds (conn/create-datasource test-db-path)
          _ (job-store/create-job! ds test-pipeline)
          job (job-store/get-job ds "attempt-test-job")
          build-1 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :manual})
          build-2 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-1)})
          build-3 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-2)})
          attempts (build-store/list-attempts ds (:id build-1))]
      (is (= 3 (count attempts))
          "Should return all 3 attempts")
      (is (= [1 2 3] (mapv :attempt-number attempts))
          "Attempts should be in ascending order")
      (is (= (:id build-1) (:id (first attempts)))
          "First attempt should be the original build")
      (is (= (:id build-3) (:id (last attempts)))
          "Last attempt should be the latest retry"))))

(deftest get-root-build-id-follows-chain-test
  (testing "get-root-build-id follows parent chain correctly"
    (let [ds (conn/create-datasource test-db-path)
          _ (job-store/create-job! ds test-pipeline)
          job (job-store/get-job ds "attempt-test-job")
          build-1 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :manual})
          build-2 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-1)})
          build-3 (build-store/create-build! ds
                    {:job-id (:id job)
                     :trigger-type :retry
                     :parent-build-id (:id build-2)})]
      ;; Root build has no root-build-id (it IS the root)
      (is (nil? (build-store/get-root-build-id ds (:id build-1)))
          "Root build should return nil")
      ;; Build-2 should resolve to build-1
      (is (= (:id build-1) (build-store/get-root-build-id ds (:id build-2)))
          "First retry should resolve to original build")
      ;; Build-3 should also resolve to build-1
      (is (= (:id build-1) (build-store/get-root-build-id ds (:id build-3)))
          "Retry-of-retry should resolve to original build"))))
