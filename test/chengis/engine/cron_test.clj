(ns chengis.engine.cron-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.cron-store :as cron-store]
            [chengis.db.job-store :as job-store]
            [chengis.engine.cron :as cron]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-cron-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Cron expression parsing
;; ---------------------------------------------------------------------------

(deftest parse-cron-every-minute
  (testing "* * * * * matches every minute"
    (let [parsed (cron/parse-cron-expression "* * * * *")]
      (is (some? parsed))
      (is (= 60 (count (:minute parsed))))
      (is (= 24 (count (:hour parsed)))))))

(deftest parse-cron-specific-time
  (testing "0 2 * * * matches 2:00 AM daily"
    (let [parsed (cron/parse-cron-expression "0 2 * * *")]
      (is (= #{0} (:minute parsed)))
      (is (= #{2} (:hour parsed))))))

(deftest parse-cron-step
  (testing "*/15 * * * * matches every 15 minutes"
    (let [parsed (cron/parse-cron-expression "*/15 * * * *")]
      (is (= #{0 15 30 45} (:minute parsed))))))

(deftest parse-cron-range
  (testing "0 9-17 * * * matches 9 AM to 5 PM"
    (let [parsed (cron/parse-cron-expression "0 9-17 * * *")]
      (is (= #{9 10 11 12 13 14 15 16 17} (:hour parsed))))))

(deftest parse-cron-comma
  (testing "0 0 * * 1,3,5 matches Mon, Wed, Fri"
    (let [parsed (cron/parse-cron-expression "0 0 * * 1,3,5")]
      (is (= #{1 3 5} (:dow parsed))))))

(deftest parse-cron-invalid
  (testing "invalid expression returns nil"
    (is (nil? (cron/parse-cron-expression "invalid")))
    (is (nil? (cron/parse-cron-expression "1 2 3")))
    (is (nil? (cron/parse-cron-expression "")))))

;; ---------------------------------------------------------------------------
;; Cron matching
;; ---------------------------------------------------------------------------

(deftest cron-matches-specific-time
  (testing "matches a specific datetime against cron"
    (let [parsed (cron/parse-cron-expression "30 14 * * *")
          ;; 2024-06-15 is a Saturday (dow=6)
          dt (java.time.ZonedDateTime/of 2024 6 15 14 30 0 0 (java.time.ZoneId/of "UTC"))]
      (is (true? (cron/cron-matches? parsed dt))))))

(deftest cron-does-not-match-wrong-time
  (testing "does not match wrong minute"
    (let [parsed (cron/parse-cron-expression "30 14 * * *")
          dt (java.time.ZonedDateTime/of 2024 6 15 14 31 0 0 (java.time.ZoneId/of "UTC"))]
      (is (false? (cron/cron-matches? parsed dt))))))

;; ---------------------------------------------------------------------------
;; Next run time
;; ---------------------------------------------------------------------------

(deftest next-run-time-basic
  (testing "calculates next run time for simple cron"
    (let [result (cron/next-run-time "0 * * * *" "2024-06-15T14:30:00Z" "UTC")]
      (is (some? result))
      ;; Next hour mark after 14:30 should be 15:00
      (is (= "2024-06-15T15:00:00Z" result)))))

(deftest next-run-time-specific-hour
  (testing "calculates next run at specific hour"
    (let [result (cron/next-run-time "0 2 * * *" "2024-06-15T03:00:00Z" "UTC")]
      ;; Next 2:00 AM is next day
      (is (= "2024-06-16T02:00:00Z" result)))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest validate-valid-expression
  (testing "valid expressions pass validation"
    (is (true? (:valid? (cron/validate-cron-expression "0 2 * * *"))))
    (is (true? (:valid? (cron/validate-cron-expression "*/15 * * * *"))))
    (is (true? (:valid? (cron/validate-cron-expression "0 9-17 * * 1-5"))))))

(deftest validate-invalid-expression
  (testing "invalid expressions fail validation"
    (is (false? (:valid? (cron/validate-cron-expression ""))))
    (is (false? (:valid? (cron/validate-cron-expression "bad"))))
    (is (false? (:valid? (cron/validate-cron-expression "1 2 3"))))))

;; ---------------------------------------------------------------------------
;; Schedule processing
;; ---------------------------------------------------------------------------

(deftest process-due-schedules-flag-disabled
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:cron-scheduling false}}}]
    (testing "no-op when flag disabled"
      (is (nil? (cron/process-due-schedules! system))))))

(deftest process-due-schedules-triggers-build
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:cron-scheduling true}
                                :cron {:missed-run-threshold-minutes 10}}}]
    (testing "triggers build for due schedule"
      ;; Create job
      (let [job (job-store/create-job! ds
                  {:pipeline-name "cron-test-job"
                   :stages [{:stage-name "Build" :steps []}]}
                  :org-id "org-A")
            ;; Create schedule with past next-run-at (due now)
            sched (cron-store/create-schedule! ds
                    {:job-id (:id job) :org-id "org-A"
                     :cron-expression "0 * * * *"
                     :next-run-at (str (.minus (java.time.Instant/now)
                                               (java.time.Duration/ofMinutes 1)))})]
        ;; Process
        (let [processed (cron/process-due-schedules! system)]
          (is (= 1 processed))
          ;; Verify run was recorded
          (let [runs (cron-store/list-cron-runs ds (:id sched))]
            (is (= 1 (count runs)))
            (is (= "triggered" (:status (first runs))))))))))

(deftest process-due-schedules-missed
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:cron-scheduling true}
                                :cron {:missed-run-threshold-minutes 5}}}]
    (testing "marks as missed when past threshold"
      (let [job (job-store/create-job! ds
                  {:pipeline-name "missed-job"
                   :stages [{:stage-name "Build" :steps []}]}
                  :org-id "org-A")
            sched (cron-store/create-schedule! ds
                    {:job-id (:id job) :org-id "org-A"
                     :cron-expression "0 * * * *"
                     ;; 20 minutes ago (well past 5 min threshold)
                     :next-run-at (str (.minus (java.time.Instant/now)
                                               (java.time.Duration/ofMinutes 20)))})]
        (cron/process-due-schedules! system)
        (let [runs (cron-store/list-cron-runs ds (:id sched))]
          (is (= 1 (count runs)))
          (is (= "missed" (:status (first runs)))))))))
