(ns chengis.db.build-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-test.db")

(def test-pipeline
  {:pipeline-name "test-app"
   :description "Test pipeline"
   :stages [{:stage-name "Build"
             :parallel? false
             :steps [{:step-name "Compile"
                      :type :shell
                      :command "echo compile"}]}]})

(defn setup-db [f]
  ;; Clean up any existing test DB
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  ;; Run migrations
  (migrate/migrate! test-db-path)
  (f)
  ;; Cleanup
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest job-crud-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and retrieve a job"
      (let [job (job-store/create-job! ds test-pipeline)]
        (is (some? (:id job)))
        (is (= "test-app" (:name job)))

        (let [retrieved (job-store/get-job ds "test-app")]
          (is (some? retrieved))
          (is (= "test-app" (:name retrieved)))
          (is (= "Test pipeline" (get-in retrieved [:pipeline :description]))))))

    (testing "list jobs"
      (let [jobs (job-store/list-jobs ds)]
        (is (= 1 (count jobs)))
        (is (= "test-app" (:name (first jobs))))))

    (testing "delete job"
      (is (true? (job-store/delete-job! ds "test-app")))
      (is (nil? (job-store/get-job ds "test-app"))))))

(deftest build-lifecycle-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Create a job first
    (let [job (job-store/create-job! ds test-pipeline)
          job-id (:id job)]

      (testing "create a build"
        (let [build (build-store/create-build! ds {:job-id job-id
                                                    :trigger-type :manual
                                                    :parameters {:branch "main"}})]
          (is (some? (:id build)))
          (is (= 1 (:build-number build)))
          (is (= :queued (:status build)))))

      (testing "build numbers auto-increment"
        (let [build2 (build-store/create-build! ds {:job-id job-id
                                                     :trigger-type :manual})]
          (is (= 2 (:build-number build2)))))

      (testing "update build status"
        (let [builds (build-store/list-builds ds job-id)
              build-id (:id (first builds))]
          (build-store/update-build-status! ds build-id :running
                                             :started-at "2025-01-01T00:00:00Z")
          (let [updated (build-store/get-build ds build-id)]
            (is (= :running (:status updated)))
            (is (= "2025-01-01T00:00:00Z" (:started-at updated))))))

      (testing "save build result with stages and steps"
        (let [builds (build-store/list-builds ds job-id)
              build-id (:id (first builds))
              build-result {:build-id build-id
                            :build-status :success
                            :started-at "2025-01-01T00:00:00Z"
                            :completed-at "2025-01-01T00:01:00Z"
                            :stage-results [{:stage-name "Build"
                                             :stage-status :success
                                             :started-at "2025-01-01T00:00:00Z"
                                             :completed-at "2025-01-01T00:01:00Z"
                                             :step-results [{:step-name "Compile"
                                                             :step-status :success
                                                             :exit-code 0
                                                             :stdout "compiled!\n"
                                                             :stderr ""
                                                             :duration-ms 500
                                                             :started-at "2025-01-01T00:00:00Z"
                                                             :completed-at "2025-01-01T00:01:00Z"}]}]}]
          (build-store/save-build-result! ds build-result)

          ;; Verify stages
          (let [stages (build-store/get-build-stages ds build-id)]
            (is (= 1 (count stages)))
            (is (= "Build" (:stage-name (first stages))))
            (is (= :success (:status (first stages)))))

          ;; Verify steps
          (let [steps (build-store/get-build-steps ds build-id)]
            (is (= 1 (count steps)))
            (is (= "Compile" (:step-name (first steps))))
            (is (= 0 (:exit-code (first steps))))
            (is (= "compiled!\n" (:stdout (first steps)))))))

      (testing "build logs"
        (let [builds (build-store/list-builds ds job-id)
              build-id (:id (first builds))]
          (build-store/add-build-log! ds build-id :info "Build" "Starting build")
          (build-store/add-build-log! ds build-id :error "Test" "Test failed")
          (let [logs (build-store/get-build-logs ds build-id)]
            (is (= 2 (count logs)))
            (is (= "Starting build" (:message (first logs))))
            (is (= "error" (:level (second logs))))))))))
