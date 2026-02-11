(ns chengis.properties.analytics-properties-test
  "Property-based tests for build analytics computation.
   Verifies percentile ordering, flakiness score range and symmetry,
   build analytics count partitioning, and stage analytics grouping."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.analytics :as analytics]))

;; ---------------------------------------------------------------------------
;; compute-percentile — ordering and range invariants
;; ---------------------------------------------------------------------------

(defspec percentile-empty-returns-nil 50
  (prop/for-all [p (gen/choose 0 100)]
    (nil? (analytics/compute-percentile [] p))))

(defspec percentile-single-element-returns-element 100
  (prop/for-all [v (gen/double* {:min 0.1 :max 1000.0 :NaN? false :infinite? false})
                 p (gen/choose 0 100)]
    (= v (analytics/compute-percentile [v] p))))

(defspec percentile-within-range 200
  (prop/for-all [sorted-vals (gen/such-that seq cgen/gen-sorted-durations)]
    (let [result (analytics/compute-percentile sorted-vals 50)]
      (and (>= result (first sorted-vals))
           (<= result (last sorted-vals))))))

(defspec percentile-monotonic 200
  (prop/for-all [sorted-vals (gen/such-that #(> (count %) 1) cgen/gen-sorted-durations)]
    ;; p50 <= p90 <= p99
    (let [p50 (analytics/compute-percentile sorted-vals 50)
          p90 (analytics/compute-percentile sorted-vals 90)
          p99 (analytics/compute-percentile sorted-vals 99)]
      (and (<= p50 p90)
           (<= p90 p99)))))

(defspec percentile-p0-is-minimum 100
  (prop/for-all [sorted-vals (gen/such-that seq cgen/gen-sorted-durations)]
    (= (first sorted-vals)
       (analytics/compute-percentile sorted-vals 0))))

;; ---------------------------------------------------------------------------
;; compute-flakiness-score — range and symmetry
;; ---------------------------------------------------------------------------

(defspec flakiness-score-always-in-0-1 200
  (prop/for-all [rate cgen/gen-success-rate]
    (let [score (analytics/compute-flakiness-score rate)]
      (and (>= score 0.0)
           (<= score 1.0)))))

(defspec flakiness-score-symmetric 200
  (prop/for-all [rate cgen/gen-success-rate]
    ;; f(x) = f(1-x): a 20% success rate is as flaky as 80%
    (let [score-a (analytics/compute-flakiness-score rate)
          score-b (analytics/compute-flakiness-score (- 1.0 rate))]
      (< (Math/abs (- score-a score-b)) 0.0001))))

(defspec flakiness-score-extremes-are-zero 20
  (prop/for-all [_ (gen/return nil)]
    (and (< (analytics/compute-flakiness-score 0.0) 0.0001)
         (< (analytics/compute-flakiness-score 1.0) 0.0001))))

(defspec flakiness-score-half-is-max 20
  (prop/for-all [_ (gen/return nil)]
    (< (Math/abs (- 1.0 (analytics/compute-flakiness-score 0.5))) 0.0001)))

(defspec flakiness-score-nil-returns-nil 20
  (prop/for-all [_ (gen/return nil)]
    (nil? (analytics/compute-flakiness-score nil))))

;; ---------------------------------------------------------------------------
;; compute-build-analytics — count partitioning
;; ---------------------------------------------------------------------------

(defspec build-analytics-total-is-sum 200
  (prop/for-all [builds cgen/gen-build-records]
    (let [opts {:org-id "test" :job-id "j1" :period-type "daily"
                :period-start "2024-01-01" :period-end "2024-01-02"}
          result (analytics/compute-build-analytics builds opts)]
      (= (:total-builds result)
         (+ (:success-count result)
            (:failure-count result)
            (:aborted-count result))))))

(defspec build-analytics-success-rate-in-range 200
  (prop/for-all [builds cgen/gen-build-records]
    (let [opts {:org-id "test" :job-id "j1" :period-type "daily"
                :period-start "2024-01-01" :period-end "2024-01-02"}
          result (analytics/compute-build-analytics builds opts)]
      (and (>= (:success-rate result) 0.0)
           (<= (:success-rate result) 1.0)))))

(defspec build-analytics-empty-gives-zeros 20
  (prop/for-all [_ (gen/return nil)]
    (let [opts {:org-id "test" :job-id "j1" :period-type "daily"
                :period-start "2024-01-01" :period-end "2024-01-02"}
          result (analytics/compute-build-analytics [] opts)]
      (and (= 0 (:total-builds result))
           (= 0 (:success-count result))
           (= 0 (:failure-count result))
           (= 0 (:aborted-count result))
           (= 0.0 (:success-rate result))))))

(defspec build-analytics-preserves-period-info 100
  (prop/for-all [builds cgen/gen-build-records]
    (let [opts {:org-id "org-1" :job-id "j-2" :period-type "weekly"
                :period-start "2024-01-01" :period-end "2024-01-08"}
          result (analytics/compute-build-analytics builds opts)]
      (and (= "org-1" (:org-id result))
           (= "j-2" (:job-id result))
           (= "weekly" (:period-type result))
           (= "2024-01-01" (:period-start result))
           (= "2024-01-08" (:period-end result))))))

;; ---------------------------------------------------------------------------
;; compute-stage-analytics — grouping
;; ---------------------------------------------------------------------------

(defspec stage-analytics-groups-by-name 100
  (prop/for-all [n (gen/choose 1 3)]
    (let [stages (for [i (range n)
                       _ (range 3)]
                   {:stage-name (str "stage-" i)
                    :status "success"
                    :started-at "2024-01-01T10:00:00Z"
                    :completed-at "2024-01-01T10:01:00Z"})
          opts {:org-id "test" :job-id "j1" :period-type "daily"
                :period-start "2024-01-01" :period-end "2024-01-02"}
          result (analytics/compute-stage-analytics stages opts)]
      (= n (count result)))))

(defspec stage-analytics-flakiness-in-range 100
  (prop/for-all [_ (gen/return nil)]
    (let [stages [{:stage-name "build" :status "success"
                   :started-at "2024-01-01T10:00:00Z"
                   :completed-at "2024-01-01T10:01:00Z"}
                  {:stage-name "build" :status "failure"
                   :started-at "2024-01-01T11:00:00Z"
                   :completed-at "2024-01-01T11:01:00Z"}]
          opts {:org-id "test" :job-id "j1" :period-type "daily"
                :period-start "2024-01-01" :period-end "2024-01-02"}
          result (first (analytics/compute-stage-analytics stages opts))
          score (:flakiness-score result)]
      (and (>= score 0.0)
           (<= score 1.0)))))
