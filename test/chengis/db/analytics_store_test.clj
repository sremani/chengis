(ns chengis.db.analytics-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.analytics-store :as analytics-store]))

(def test-db-path "/tmp/chengis-analytics-store-test.db")

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
;; Build Analytics CRUD
;; ---------------------------------------------------------------------------

(deftest upsert-build-analytics-test
  (let [ds (test-ds)]
    (testing "upsert-build-analytics! creates a record"
      (let [row (analytics-store/upsert-build-analytics! ds
                  {:org-id "org-1"
                   :job-id "job-1"
                   :period-type "daily"
                   :period-start "2025-01-15T00:00:00Z"
                   :period-end "2025-01-16T00:00:00Z"
                   :total-builds 10
                   :success-count 8
                   :failure-count 1
                   :aborted-count 1
                   :success-rate 0.8
                   :avg-duration-s 45.5
                   :p50-duration-s 30.0
                   :p90-duration-s 90.0
                   :p99-duration-s 120.0
                   :max-duration-s 150.0})]
        (is (some? (:id row)))
        (is (= "daily" (:period-type row)))
        (is (= 10 (:total-builds row)))))))

(deftest get-build-trends-test
  (let [ds (test-ds)]
    (testing "get-build-trends returns records"
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :job-id "job-1" :period-type "daily"
         :period-start "2025-01-14T00:00:00Z" :period-end "2025-01-15T00:00:00Z"
         :total-builds 5 :success-count 4 :failure-count 1 :aborted-count 0
         :success-rate 0.8 :avg-duration-s 30.0
         :p50-duration-s 25.0 :p90-duration-s 60.0 :p99-duration-s 80.0 :max-duration-s 100.0})
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :job-id "job-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 8 :success-count 7 :failure-count 1 :aborted-count 0
         :success-rate 0.875 :avg-duration-s 40.0
         :p50-duration-s 35.0 :p90-duration-s 70.0 :p99-duration-s 90.0 :max-duration-s 110.0})
      (let [trends (analytics-store/get-build-trends ds :org-id "org-1" :period-type "daily")]
        (is (= 2 (count trends)))
        ;; Should be ordered by period_start desc
        (is (= 8 (:total-builds (first trends))))))))

(deftest get-build-trends-org-scoped-test
  (let [ds (test-ds)]
    (testing "get-build-trends is org-scoped"
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 5 :success-count 5 :failure-count 0 :aborted-count 0
         :success-rate 1.0})
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-2" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 3 :success-count 2 :failure-count 1 :aborted-count 0
         :success-rate 0.667})
      (let [org1 (analytics-store/get-build-trends ds :org-id "org-1" :period-type "daily")
            org2 (analytics-store/get-build-trends ds :org-id "org-2" :period-type "daily")]
        (is (= 1 (count org1)))
        (is (= 5 (:total-builds (first org1))))
        (is (= 1 (count org2)))
        (is (= 3 (:total-builds (first org2))))))))

(deftest get-latest-build-analytics-test
  (let [ds (test-ds)]
    (testing "get-latest-build-analytics returns most recent"
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-14T00:00:00Z" :period-end "2025-01-15T00:00:00Z"
         :total-builds 5 :success-count 3 :failure-count 2 :aborted-count 0
         :success-rate 0.6})
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 10 :success-count 9 :failure-count 1 :aborted-count 0
         :success-rate 0.9})
      (let [latest (analytics-store/get-latest-build-analytics ds :org-id "org-1")]
        (is (some? latest))
        (is (= 10 (:total-builds latest)))))))

;; ---------------------------------------------------------------------------
;; Stage Analytics CRUD
;; ---------------------------------------------------------------------------

(deftest upsert-stage-analytics-test
  (let [ds (test-ds)]
    (testing "upsert-stage-analytics! creates a record"
      (let [row (analytics-store/upsert-stage-analytics! ds
                  {:org-id "org-1"
                   :job-id "job-1"
                   :stage-name "Build"
                   :period-type "daily"
                   :period-start "2025-01-15T00:00:00Z"
                   :period-end "2025-01-16T00:00:00Z"
                   :total-runs 10
                   :success-count 9
                   :failure-count 1
                   :avg-duration-s 20.0
                   :p90-duration-s 45.0
                   :max-duration-s 60.0
                   :flakiness-score 0.18})]
        (is (some? (:id row)))
        (is (= "Build" (:stage-name row)))))))

(deftest get-slowest-stages-test
  (let [ds (test-ds)]
    (testing "get-slowest-stages returns stages ordered by p90"
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Build" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 10 :success-count 9 :failure-count 1
         :avg-duration-s 20.0 :p90-duration-s 45.0 :max-duration-s 60.0
         :flakiness-score 0.18})
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Test" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 10 :success-count 10 :failure-count 0
         :avg-duration-s 60.0 :p90-duration-s 120.0 :max-duration-s 180.0
         :flakiness-score 0.0})
      (let [slowest (analytics-store/get-slowest-stages ds :org-id "org-1")]
        (is (= 2 (count slowest)))
        ;; Test should be first (higher p90)
        (is (= "Test" (:stage-name (first slowest))))))))

(deftest get-flaky-stages-test
  (let [ds (test-ds)]
    (testing "get-flaky-stages returns stages above threshold"
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Stable" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 10 :success-count 10 :failure-count 0
         :flakiness-score 0.0})
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Flaky" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 10 :success-count 5 :failure-count 5
         :flakiness-score 1.0})
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Moderate" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 10 :success-count 8 :failure-count 2
         :flakiness-score 0.4})
      (let [flaky (analytics-store/get-flaky-stages ds :org-id "org-1" :threshold 0.15)]
        (is (= 2 (count flaky)))
        ;; Flaky (1.0) should be first, Moderate (0.4) second
        (is (= "Flaky" (:stage-name (first flaky))))
        (is (= "Moderate" (:stage-name (second flaky))))))))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(deftest cleanup-old-analytics-test
  (let [ds (test-ds)]
    (testing "cleanup-old-analytics! removes old records"
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 5 :success-count 5 :failure-count 0 :aborted-count 0
         :success-rate 1.0})
      (is (= 1 (analytics-store/count-build-analytics ds)))
      ;; Cleanup with 0 days retention (removes everything)
      (let [deleted (analytics-store/cleanup-old-analytics! ds 0)]
        (is (pos? deleted))
        (is (= 0 (analytics-store/count-build-analytics ds)))))))

;; ---------------------------------------------------------------------------
;; Count helpers
;; ---------------------------------------------------------------------------

(deftest count-analytics-test
  (let [ds (test-ds)]
    (testing "count helpers work"
      (is (= 0 (analytics-store/count-build-analytics ds)))
      (is (= 0 (analytics-store/count-stage-analytics ds)))
      (analytics-store/upsert-build-analytics! ds
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 1 :success-count 1 :failure-count 0 :aborted-count 0
         :success-rate 1.0})
      (analytics-store/upsert-stage-analytics! ds
        {:org-id "org-1" :stage-name "Test" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-runs 1 :success-count 1 :failure-count 0
         :flakiness-score 0.0})
      (is (= 1 (analytics-store/count-build-analytics ds)))
      (is (= 1 (analytics-store/count-stage-analytics ds)))
      ;; Org-scoped counts
      (is (= 1 (analytics-store/count-build-analytics ds :org-id "org-1")))
      (is (= 0 (analytics-store/count-build-analytics ds :org-id "org-other"))))))
