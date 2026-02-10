(ns chengis.db.test-result-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.test-result-store :as trs]))

(def test-db-path "/tmp/chengis-test-result-store-test.db")

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
;; Save & query test results
;; ---------------------------------------------------------------------------

(deftest save-test-results-test
  (let [ds (test-ds)]
    (testing "save-test-results! stores results"
      (let [count (trs/save-test-results! ds "build-1"
                    {:job-id "job-1" :org-id "org-1" :stage-name "Test" :step-name "unit"}
                    [{:test-name "test_add" :test-suite "math" :status "pass" :duration-ms 50}
                     {:test-name "test_sub" :test-suite "math" :status "fail" :duration-ms 30
                      :error-msg "expected 1 got 2"}])]
        (is (= 2 count))
        (is (= 2 (trs/count-test-results ds)))))))

(deftest get-build-test-results-test
  (let [ds (test-ds)]
    (testing "get-build-test-results retrieves all results for a build"
      (trs/save-test-results! ds "build-1"
        {:job-id "job-1" :org-id "org-1"}
        [{:test-name "t1" :status "pass"}
         {:test-name "t2" :status "fail"}])
      (let [results (trs/get-build-test-results ds "build-1")]
        (is (= 2 (count results)))))))

(deftest get-test-history-test
  (let [ds (test-ds)]
    (testing "get-test-history returns history for a specific test"
      (trs/save-test-results! ds "build-1"
        {:job-id "job-1" :org-id "org-1"}
        [{:test-name "my-test" :status "pass"}])
      (trs/save-test-results! ds "build-2"
        {:job-id "job-1" :org-id "org-1"}
        [{:test-name "my-test" :status "fail"}])
      (let [history (trs/get-test-history ds "job-1" "my-test" :org-id "org-1")]
        (is (= 2 (count history)))))))

;; ---------------------------------------------------------------------------
;; Flaky test detection
;; ---------------------------------------------------------------------------

(deftest compute-flaky-tests-test
  (let [ds (test-ds)]
    (testing "compute-flaky-tests! detects flaky tests"
      ;; Create test results with mixed pass/fail
      (dotimes [i 5]
        (trs/save-test-results! ds (str "build-" i)
          {:job-id "job-1" :org-id "org-1"}
          [{:test-name "stable-test" :status "pass"}
           {:test-name "flaky-test" :status (if (even? i) "pass" "fail")}]))
      (let [flaky-count (trs/compute-flaky-tests! ds "job-1"
                          {:org-id "org-1"
                           :lookback-builds 30
                           :min-runs 3
                           :flakiness-threshold 0.15})]
        ;; flaky-test has 3 pass / 2 fail = 60% success → flakiness 0.2
        (is (pos? flaky-count))
        (let [flaky (trs/list-flaky-tests ds :org-id "org-1")]
          (is (seq flaky))
          (is (= "flaky-test" (:test-name (first flaky)))))))))

(deftest compute-flaky-tests-stable-test
  (let [ds (test-ds)]
    (testing "compute-flaky-tests! ignores stable tests"
      (dotimes [i 5]
        (trs/save-test-results! ds (str "build-" i)
          {:job-id "job-1" :org-id "org-1"}
          [{:test-name "always-pass" :status "pass"}]))
      (let [flaky-count (trs/compute-flaky-tests! ds "job-1"
                          {:org-id "org-1" :min-runs 3 :flakiness-threshold 0.15})]
        (is (= 0 flaky-count))))))

(deftest list-flaky-tests-test
  (let [ds (test-ds)]
    (testing "list-flaky-tests returns flaky tests ordered by score"
      ;; Manually insert flaky test records
      (dotimes [i 10]
        (trs/save-test-results! ds (str "build-" i)
          {:job-id "job-1" :org-id "org-1"}
          [{:test-name "very-flaky" :status (if (even? i) "pass" "fail")}
           {:test-name "somewhat-flaky" :status (if (< i 2) "fail" "pass")}]))
      (trs/compute-flaky-tests! ds "job-1"
        {:org-id "org-1" :min-runs 5 :flakiness-threshold 0.1})
      (let [flaky (trs/list-flaky-tests ds :org-id "org-1")]
        (is (seq flaky))
        ;; very-flaky (50/50) should have higher score
        (when (> (count flaky) 1)
          (is (>= (:flakiness-score (first flaky))
                  (:flakiness-score (second flaky)))))))))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(deftest cleanup-old-test-results-test
  (let [ds (test-ds)]
    (testing "cleanup-old-test-results! removes old records"
      (trs/save-test-results! ds "build-1"
        {:job-id "job-1" :org-id "org-1"}
        [{:test-name "t1" :status "pass"}])
      (is (= 1 (trs/count-test-results ds)))
      (let [deleted (trs/cleanup-old-test-results! ds 0)]
        (is (pos? deleted))
        (is (= 0 (trs/count-test-results ds)))))))

;; ---------------------------------------------------------------------------
;; Count helpers
;; ---------------------------------------------------------------------------

(deftest count-helpers-test
  (let [ds (test-ds)]
    (testing "count helpers work"
      (is (= 0 (trs/count-test-results ds)))
      (is (= 0 (trs/count-flaky-tests ds)))
      (trs/save-test-results! ds "build-1"
        {:job-id "job-1" :org-id "org-1"}
        [{:test-name "t1" :status "pass"}])
      (is (= 1 (trs/count-test-results ds)))
      (is (= 1 (trs/count-test-results ds :org-id "org-1")))
      (is (= 0 (trs/count-test-results ds :org-id "other"))))))
