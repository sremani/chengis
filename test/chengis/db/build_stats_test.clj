(ns chengis.db.build-stats-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [next.jdbc.result-set :as rs]))

(def test-db-path "/tmp/chengis-stats-test.db")

(def test-pipeline
  {:pipeline-name "stats-app"
   :description "Pipeline for stats testing"
   :stages [{:stage-name "Build"
             :parallel? false
             :steps [{:step-name "Compile"
                      :type :shell
                      :command "echo compile"}]}]})

(def test-pipeline-2
  {:pipeline-name "stats-app-2"
   :description "Second pipeline"
   :stages [{:stage-name "Test"
             :parallel? false
             :steps [{:step-name "Run"
                      :type :shell
                      :command "echo test"}]}]})

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- create-build-with-status!
  "Helper: create a build and immediately set its status."
  [ds job-id status]
  (let [build (build-store/create-build! ds {:job-id job-id
                                               :trigger-type :manual})]
    (build-store/update-build-status! ds (:id build) status
                                       :started-at "2025-01-01T00:00:00Z"
                                       :completed-at "2025-01-01T00:01:00Z")
    build))

;; ---------------------------------------------------------------------------
;; get-build-stats
;; ---------------------------------------------------------------------------

(deftest build-stats-empty-db
  (let [ds (conn/create-datasource test-db-path)]
    (testing "stats with no builds"
      (let [stats (build-store/get-build-stats ds)]
        (is (= 0 (:total stats)))
        (is (= 0 (:success stats)))
        (is (= 0 (:failure stats)))
        (is (= 0 (:aborted stats)))
        (is (= 0.0 (:success-rate stats)))))))

(deftest build-stats-with-builds
  (let [ds (conn/create-datasource test-db-path)
        job (job-store/create-job! ds test-pipeline)
        job-id (:id job)]
    (testing "stats with mixed statuses"
      ;; 3 success, 1 failure, 1 aborted
      (create-build-with-status! ds job-id :success)
      (create-build-with-status! ds job-id :success)
      (create-build-with-status! ds job-id :success)
      (create-build-with-status! ds job-id :failure)
      (create-build-with-status! ds job-id :aborted)

      (let [stats (build-store/get-build-stats ds job-id)]
        (is (= 5 (:total stats)))
        (is (= 3 (:success stats)))
        (is (= 1 (:failure stats)))
        (is (= 1 (:aborted stats)))
        (is (= 0.6 (:success-rate stats)))))))

(deftest build-stats-global
  (let [ds (conn/create-datasource test-db-path)
        job1 (job-store/create-job! ds test-pipeline)
        job2 (job-store/create-job! ds test-pipeline-2)]
    (testing "global stats across multiple jobs"
      (create-build-with-status! ds (:id job1) :success)
      (create-build-with-status! ds (:id job1) :failure)
      (create-build-with-status! ds (:id job2) :success)
      (create-build-with-status! ds (:id job2) :success)

      (let [stats (build-store/get-build-stats ds)]
        (is (= 4 (:total stats)))
        (is (= 3 (:success stats)))
        (is (= 1 (:failure stats)))
        (is (= 0.75 (:success-rate stats)))))))

;; ---------------------------------------------------------------------------
;; get-recent-build-history
;; ---------------------------------------------------------------------------

(deftest recent-build-history
  (let [ds (conn/create-datasource test-db-path)
        job (job-store/create-job! ds test-pipeline)
        job-id (:id job)]
    (testing "returns limited history ordered by created_at desc"
      ;; Create 8 builds
      (dotimes [_ 8]
        (create-build-with-status! ds job-id :success))

      (let [history (build-store/get-recent-build-history ds job-id 5)]
        (is (= 5 (count history)))
        ;; Each entry has expected keys
        (doseq [entry history]
          (is (some? (:id entry)))
          (is (some? (:build-number entry)))
          (is (keyword? (:status entry))))
        ;; All returned builds should have build-number > 0
        (is (every? #(pos? (:build-number %)) history))))))

;; ---------------------------------------------------------------------------
;; create-build with parent-build-id (retry lineage)
;; ---------------------------------------------------------------------------

(deftest create-build-with-parent-id
  (let [ds (conn/create-datasource test-db-path)
        job (job-store/create-job! ds test-pipeline)
        job-id (:id job)]
    (testing "build with parent-build-id for retry tracking"
      (let [original (build-store/create-build! ds {:job-id job-id
                                                      :trigger-type :manual})
            retry (build-store/create-build! ds {:job-id job-id
                                                   :trigger-type :retry
                                                   :parent-build-id (:id original)})]
        ;; Basic properties
        (is (some? (:id retry)))
        (is (= 2 (:build-number retry)))
        ;; Verify parent-build-id via raw SQL (since get-build may not include it)
        (let [raw (jdbc/execute-one! ds
                    (sql/format {:select [:parent-build-id]
                                 :from :builds
                                 :where [:= :id (:id retry)]})
                    {:builder-fn rs/as-unqualified-kebab-maps})]
          (is (= (:id original) (:parent-build-id raw))))))))
