(ns chengis.db.cron-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.cron-store :as cron-store]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-cron-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Schedule CRUD
;; ---------------------------------------------------------------------------

(deftest create-and-list-schedules
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can create and list cron schedules"
      (cron-store/create-schedule! ds
        {:job-id "job-1" :org-id "org-A"
         :cron-expression "0 2 * * *"
         :description "Nightly build"})
      (cron-store/create-schedule! ds
        {:job-id "job-2" :org-id "org-A"
         :cron-expression "*/15 * * * *"
         :description "Every 15 min"})

      (let [schedules (cron-store/list-schedules ds)]
        (is (= 2 (count schedules)))))))

(deftest list-schedules-filtered
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-schedules filters by job-id and org-id"
      (cron-store/create-schedule! ds
        {:job-id "job-1" :org-id "org-A" :cron-expression "0 * * * *"})
      (cron-store/create-schedule! ds
        {:job-id "job-2" :org-id "org-B" :cron-expression "0 * * * *"})

      (is (= 1 (count (cron-store/list-schedules ds :job-id "job-1"))))
      (is (= 1 (count (cron-store/list-schedules ds :org-id "org-A"))))
      (is (= 0 (count (cron-store/list-schedules ds :org-id "org-C")))))))

(deftest get-schedule
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can get a schedule by ID"
      (let [sched (cron-store/create-schedule! ds
                    {:job-id "job-1" :org-id "org-A"
                     :cron-expression "0 2 * * *"
                     :description "Nightly"
                     :timezone "US/Eastern"
                     :parameters {:env "prod"}})]
        (let [retrieved (cron-store/get-schedule ds (:id sched))]
          (is (some? retrieved))
          (is (= "0 2 * * *" (:cron-expression retrieved)))
          (is (= "US/Eastern" (:timezone retrieved)))
          (is (= {:env "prod"} (:parameters retrieved)))
          (is (true? (:enabled retrieved))))))))

(deftest update-schedule
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can update schedule properties"
      (let [sched (cron-store/create-schedule! ds
                    {:job-id "job-1" :org-id "org-A"
                     :cron-expression "0 2 * * *"})]
        (cron-store/update-schedule! ds (:id sched)
          {:cron-expression "0 3 * * *" :enabled false})
        (let [updated (cron-store/get-schedule ds (:id sched))]
          (is (= "0 3 * * *" (:cron-expression updated)))
          (is (false? (:enabled updated))))))))

(deftest delete-schedule
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can delete a schedule"
      (let [sched (cron-store/create-schedule! ds
                    {:job-id "job-1" :org-id "org-A"
                     :cron-expression "0 2 * * *"})]
        (is (true? (cron-store/delete-schedule! ds (:id sched))))
        (is (nil? (cron-store/get-schedule ds (:id sched))))))))

(deftest delete-schedule-respects-org
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete-schedule! with wrong org fails"
      (let [sched (cron-store/create-schedule! ds
                    {:job-id "job-1" :org-id "org-A"
                     :cron-expression "0 2 * * *"})]
        (is (false? (cron-store/delete-schedule! ds (:id sched) :org-id "org-B")))
        (is (some? (cron-store/get-schedule ds (:id sched))))))))

;; ---------------------------------------------------------------------------
;; Due schedule retrieval
;; ---------------------------------------------------------------------------

(deftest get-due-schedules
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns only enabled schedules with past next-run-at"
      (let [past-time "2024-01-01T00:00:00Z"
            future-time "2099-01-01T00:00:00Z"]
        ;; Due schedule
        (cron-store/create-schedule! ds
          {:job-id "job-1" :org-id "org-A"
           :cron-expression "0 * * * *"
           :next-run-at past-time})
        ;; Future schedule
        (cron-store/create-schedule! ds
          {:job-id "job-2" :org-id "org-A"
           :cron-expression "0 * * * *"
           :next-run-at future-time})
        ;; Disabled schedule
        (cron-store/create-schedule! ds
          {:job-id "job-3" :org-id "org-A"
           :cron-expression "0 * * * *"
           :enabled false
           :next-run-at past-time})

        (let [due (cron-store/get-due-schedules ds "2025-01-01T00:00:00Z")]
          (is (= 1 (count due)))
          (is (= "job-1" (:job-id (first due)))))))))

;; ---------------------------------------------------------------------------
;; Run history
;; ---------------------------------------------------------------------------

(deftest record-and-list-runs
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can record and list cron runs"
      (cron-store/record-cron-run! ds
        {:schedule-id "sched-1" :job-id "job-1" :build-id "build-1"
         :org-id "org-A" :scheduled-at "2024-01-01T00:00:00Z"
         :triggered-at "2024-01-01T00:00:01Z" :status :triggered})
      (cron-store/record-cron-run! ds
        {:schedule-id "sched-1" :job-id "job-1"
         :org-id "org-A" :scheduled-at "2024-01-01T01:00:00Z"
         :status :missed :missed true :error "Missed threshold"})

      (let [runs (cron-store/list-cron-runs ds "sched-1")]
        (is (= 2 (count runs)))))))

;; ---------------------------------------------------------------------------
;; Phase 3h: Cron store nil-guard and partial update tests
;; ---------------------------------------------------------------------------

(deftest update-schedule-partial-nil-fields-test
  (testing "update with only cron-expression changes only that field"
    (let [ds (conn/create-datasource test-db-path)]
      (cron-store/create-schedule! ds
        {:job-id "partial-job" :cron-expression "0 * * * *"
         :description "original" :org-id "org-1"})
      (let [schedules (cron-store/list-schedules ds :job-id "partial-job")
            sched-id (:id (first schedules))]
        ;; Update only description, leaving cron-expression as nil
        (cron-store/update-schedule! ds sched-id
          {:cron-expression nil :description "updated" :enabled nil
           :parameters nil :next-run-at nil})
        (let [updated (cron-store/get-schedule ds sched-id)]
          (is (= "updated" (:description updated)))
          (is (= "0 * * * *" (:cron-expression updated))
              "cron-expression should be unchanged when nil"))))))

(deftest update-schedule-with-org-id-test
  (testing "update with org-id only affects matching org"
    (let [ds (conn/create-datasource test-db-path)]
      (cron-store/create-schedule! ds
        {:job-id "org-job" :cron-expression "0 0 * * *"
         :description "org sched" :org-id "org-X"})
      (let [schedules (cron-store/list-schedules ds :job-id "org-job")
            sched-id (:id (first schedules))]
        ;; Update with correct org-id
        (cron-store/update-schedule! ds sched-id
          {:description "org updated"} :org-id "org-X")
        (let [updated (cron-store/get-schedule ds sched-id)]
          (is (= "org updated" (:description updated))))))))
