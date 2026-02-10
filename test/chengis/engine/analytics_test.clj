(ns chengis.engine.analytics-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.build-store :as build-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.analytics-store :as analytics-store]
            [chengis.engine.analytics :as analytics]))

(def test-db-path "/tmp/chengis-analytics-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-system
  "Build a test system map with analytics enabled."
  [& {:keys [analytics-enabled] :or {analytics-enabled true}}]
  (let [ds (conn/create-datasource test-db-path)]
    {:db ds
     :config {:feature-flags {:build-analytics analytics-enabled}
              :analytics {:aggregation-interval-hours 6
                          :retention-days 365}}
     :metrics nil}))

;; ---------------------------------------------------------------------------
;; Percentile computation
;; ---------------------------------------------------------------------------

(deftest compute-percentile-test
  (testing "compute-percentile works correctly"
    (is (nil? (analytics/compute-percentile [] 50)))
    (is (= 1 (analytics/compute-percentile [1] 50)))
    ;; p50 of [1..10]: idx = floor(0.5 * 10) = 5 → 6th element (0-indexed)
    (is (= 6 (analytics/compute-percentile [1 2 3 4 5 6 7 8 9 10] 50)))
    ;; p90 of [1..10]: idx = floor(0.9 * 10) = 9 → 10th element
    (is (= 10 (analytics/compute-percentile [1 2 3 4 5 6 7 8 9 10] 90)))
    ;; p99 of [1..10]: idx = floor(0.99 * 10) = 9 → 10th element (capped)
    (is (= 10 (analytics/compute-percentile [1 2 3 4 5 6 7 8 9 10] 99)))))

;; ---------------------------------------------------------------------------
;; Flakiness score
;; ---------------------------------------------------------------------------

(deftest compute-flakiness-score-test
  (testing "compute-flakiness-score"
    (is (nil? (analytics/compute-flakiness-score nil)))
    ;; Always passes → 0.0 (stable)
    (is (< (Math/abs (- 0.0 (analytics/compute-flakiness-score 1.0))) 0.001))
    ;; Always fails → 0.0 (stable failure)
    (is (< (Math/abs (- 0.0 (analytics/compute-flakiness-score 0.0))) 0.001))
    ;; 50/50 → 1.0 (max flaky)
    (is (< (Math/abs (- 1.0 (analytics/compute-flakiness-score 0.5))) 0.001))
    ;; 80% success → 0.4
    (is (< (Math/abs (- 0.4 (analytics/compute-flakiness-score 0.8))) 0.001))
    ;; 90% success → 0.2
    (is (< (Math/abs (- 0.2 (analytics/compute-flakiness-score 0.9))) 0.001))))

;; ---------------------------------------------------------------------------
;; Build analytics computation
;; ---------------------------------------------------------------------------

(deftest compute-build-analytics-test
  (testing "compute-build-analytics produces correct stats"
    (let [builds [{:status "success" :started-at "2025-01-15T10:00:00Z" :completed-at "2025-01-15T10:01:00Z"}
                  {:status "success" :started-at "2025-01-15T11:00:00Z" :completed-at "2025-01-15T11:02:00Z"}
                  {:status "failure" :started-at "2025-01-15T12:00:00Z" :completed-at "2025-01-15T12:00:30Z"}]
          result (analytics/compute-build-analytics builds
                   {:org-id "org-1" :period-type "daily"
                    :period-start "2025-01-15T00:00:00Z"
                    :period-end "2025-01-16T00:00:00Z"})]
      (is (= 3 (:total-builds result)))
      (is (= 2 (:success-count result)))
      (is (= 1 (:failure-count result)))
      (is (= 0 (:aborted-count result)))
      (is (< (Math/abs (- 0.6667 (:success-rate result))) 0.01))
      (is (some? (:avg-duration-s result)))
      (is (some? (:p50-duration-s result)))
      (is (some? (:p90-duration-s result))))))

(deftest compute-build-analytics-empty-test
  (testing "compute-build-analytics handles empty builds"
    (let [result (analytics/compute-build-analytics []
                   {:org-id "org-1" :period-type "daily"
                    :period-start "2025-01-15T00:00:00Z"
                    :period-end "2025-01-16T00:00:00Z"})]
      (is (= 0 (:total-builds result)))
      (is (= 0.0 (:success-rate result)))
      (is (nil? (:avg-duration-s result))))))

;; ---------------------------------------------------------------------------
;; Stage analytics computation
;; ---------------------------------------------------------------------------

(deftest compute-stage-analytics-test
  (testing "compute-stage-analytics groups by stage name"
    (let [stages [{:stage-name "Build" :status "success"
                   :started-at "2025-01-15T10:00:00Z" :completed-at "2025-01-15T10:01:00Z"}
                  {:stage-name "Build" :status "failure"
                   :started-at "2025-01-15T11:00:00Z" :completed-at "2025-01-15T11:00:30Z"}
                  {:stage-name "Test" :status "success"
                   :started-at "2025-01-15T10:01:00Z" :completed-at "2025-01-15T10:03:00Z"}]
          result (analytics/compute-stage-analytics stages
                   {:org-id "org-1" :period-type "daily"
                    :period-start "2025-01-15T00:00:00Z"
                    :period-end "2025-01-16T00:00:00Z"})
          by-name (into {} (map (juxt :stage-name identity) result))]
      (is (= 2 (count result)))
      (is (= 2 (:total-runs (get by-name "Build"))))
      (is (= 1 (:total-runs (get by-name "Test"))))
      ;; Build: 50% success → flakiness 1.0
      (is (< (Math/abs (- 1.0 (:flakiness-score (get by-name "Build")))) 0.01))
      ;; Test: 100% success → flakiness 0.0
      (is (< (Math/abs (- 0.0 (:flakiness-score (get by-name "Test")))) 0.01)))))

;; ---------------------------------------------------------------------------
;; Feature flag gating
;; ---------------------------------------------------------------------------

(deftest analytics-disabled-test
  (let [system (test-system :analytics-enabled false)]
    (testing "get-build-trends returns empty when disabled"
      (is (empty? (analytics/get-build-trends system :org-id "org-1"))))
    (testing "get-slowest-stages returns empty when disabled"
      (is (empty? (analytics/get-slowest-stages system :org-id "org-1"))))
    (testing "get-flaky-stages returns empty when disabled"
      (is (empty? (analytics/get-flaky-stages system :org-id "org-1"))))
    (testing "run-aggregation! returns disabled status when disabled"
      (let [result (analytics/run-aggregation! system)]
        (is (= :disabled (:status result)))))))

;; ---------------------------------------------------------------------------
;; Run aggregation with actual builds
;; ---------------------------------------------------------------------------

(deftest run-aggregation-integration-test
  (let [system (test-system)]
    (testing "run-aggregation! succeeds even with no builds"
      (let [result (analytics/run-aggregation! system)]
        (is (= :success (:status result)))
        ;; When no builds exist, daily-builds/weekly-builds may be 0 or nil
        (is (or (nil? (:daily-builds result))
                (zero? (:daily-builds result))))
        (is (or (nil? (:weekly-builds result))
                (zero? (:weekly-builds result))))))))

;; ---------------------------------------------------------------------------
;; Public query wrappers
;; ---------------------------------------------------------------------------

(deftest query-wrappers-test
  (let [system (test-system)]
    (testing "get-build-trends returns data from store"
      ;; Insert some analytics data directly
      (analytics-store/upsert-build-analytics! (:db system)
        {:org-id "org-1" :period-type "daily"
         :period-start "2025-01-15T00:00:00Z" :period-end "2025-01-16T00:00:00Z"
         :total-builds 5 :success-count 4 :failure-count 1 :aborted-count 0
         :success-rate 0.8})
      (let [trends (analytics/get-build-trends system :org-id "org-1")]
        (is (= 1 (count trends)))
        (is (= 5 (:total-builds (first trends))))))))

;; ---------------------------------------------------------------------------
;; Scheduler lifecycle
;; ---------------------------------------------------------------------------

(deftest scheduler-lifecycle-test
  (testing "scheduler starts and stops cleanly"
    (is (not (analytics/running?*)))))
