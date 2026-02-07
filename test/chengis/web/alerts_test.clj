(ns chengis.web.alerts-test
  (:require [clojure.test :refer :all]
            [chengis.web.alerts :as alerts]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [clojure.data.json :as json]))

(defn- test-system
  "Create a temporary test system with in-memory DB."
  []
  (let [db-path (str "/tmp/chengis-alerts-test-" (System/currentTimeMillis) ".db")
        _ (migrate/migrate! db-path)
        ds (conn/create-datasource db-path)]
    {:db ds :db-path db-path :config {:database {:path db-path}}}))

(defn- cleanup-system [system]
  (try
    (.delete (java.io.File. (:db-path system)))
    (catch Exception _)))

;; ---------------------------------------------------------------------------
;; Alert check tests
;; ---------------------------------------------------------------------------

(deftest check-alerts-no-builds-test
  (testing "No alerts when there are no builds"
    (let [system (test-system)]
      (try
        (let [alerts (alerts/check-alerts system)]
          (is (vector? alerts))
          (is (empty? alerts)))
        (finally
          (cleanup-system system))))))

(deftest check-alerts-healthy-builds-test
  (testing "No alerts when all builds succeed"
    (let [system (test-system)
          ds (:db system)]
      (try
        ;; Create a job and some successful builds
        (job-store/create-job! ds {:pipeline-name "healthy-job"
                                   :stages [{:stage-name "build"
                                             :steps [{:step-name "echo"
                                                      :command "echo ok"}]}]})
        (let [job (job-store/get-job ds "healthy-job")]
          (dotimes [_ 6]
            (let [build (build-store/create-build! ds {:job-id (:id job)
                                                       :trigger-type :manual})]
              (build-store/save-build-result! ds
                {:build-id (:id build)
                 :build-status :success
                 :stage-results []
                 :started-at "2025-01-01T00:00:00Z"
                 :completed-at "2025-01-01T00:01:00Z"}))))
        (let [alerts (alerts/check-alerts system)]
          (is (empty? alerts) "No alerts expected for healthy builds"))
        (finally
          (cleanup-system system))))))

(deftest check-alerts-high-failure-rate-test
  (testing "Critical alert when failure rate exceeds threshold"
    (let [system (test-system)
          ds (:db system)]
      (try
        (job-store/create-job! ds {:pipeline-name "failing-job"
                                   :stages [{:stage-name "build"
                                             :steps [{:step-name "echo"
                                                      :command "echo ok"}]}]})
        (let [job (job-store/get-job ds "failing-job")]
          ;; Create 10 builds, 8 failed
          (dotimes [i 10]
            (let [build (build-store/create-build! ds {:job-id (:id job)
                                                       :trigger-type :manual})
                  status (if (< i 8) :failure :success)]
              (build-store/save-build-result! ds
                {:build-id (:id build)
                 :build-status status
                 :stage-results []
                 :started-at "2025-01-01T00:00:00Z"
                 :completed-at "2025-01-01T00:01:00Z"}))))
        (let [alerts (alerts/check-alerts system)
              failure-alert (first (filter #(= "build-failure-rate" (:metric %)) alerts))]
          (is (some? failure-alert) "Should have failure rate alert")
          (is (= :critical (:level failure-alert)) "Should be critical level"))
        (finally
          (cleanup-system system))))))

;; ---------------------------------------------------------------------------
;; Handler tests
;; ---------------------------------------------------------------------------

(deftest alerts-handler-test
  (testing "Alerts handler returns valid JSON"
    (let [system (test-system)]
      (try
        (let [handler (alerts/alerts-handler system)
              resp (handler {:uri "/api/alerts" :request-method :get})]
          (is (= 200 (:status resp)))
          (is (= "application/json" (get-in resp [:headers "Content-Type"])))
          (let [body (json/read-str (:body resp) :key-fn keyword)]
            (is (vector? (:alerts body)))))
        (finally
          (cleanup-system system))))))

(deftest alerts-fragment-handler-test
  (testing "Fragment handler returns HTML"
    (let [system (test-system)]
      (try
        (let [handler (alerts/alerts-fragment-handler system)
              resp (handler {:uri "/api/alerts/fragment" :request-method :get})]
          (is (= 200 (:status resp)))
          (is (= "text/html" (get-in resp [:headers "Content-Type"])))
          (is (string? (:body resp))))
        (finally
          (cleanup-system system))))))
