(ns chengis.engine.approval-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.approval-store :as approval-store]
            [chengis.engine.approval :as approval]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-approval-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest check-stage-no-approval-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:approvals {:poll-interval-ms 100}}}
        build-ctx {:build-id "build-1" :cancelled? (atom false)}
        stage-def {:stage-name "Build" :steps []}]
    (testing "stage without approval config proceeds immediately"
      (let [result (approval/check-stage-approval! system build-ctx stage-def)]
        (is (true? (:proceed result)))))))

(deftest check-stage-approved-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:approvals {:poll-interval-ms 100}}}
        build-ctx {:build-id "build-approved"
                   :cancelled? (atom false)
                   :event-fn (fn [_] nil)}
        stage-def {:stage-name "Deploy"
                   :approval {:message "Approve?" :role :admin :timeout-minutes 60}
                   :steps []}]
    (testing "approved gate proceeds"
      ;; Start the approval check in a future (it will poll)
      (let [result-future (future (approval/check-stage-approval! system build-ctx stage-def))]
        ;; Give it a moment to create the gate
        (Thread/sleep 200)
        ;; Find and approve the gate
        (let [gate (approval-store/get-gate-for-build-stage ds "build-approved" "Deploy")]
          (is (some? gate))
          (is (= "pending" (:status gate)))
          (is (= "admin" (:required-role gate)))
          (approval-store/approve-gate! ds (:id gate) "alice"))
        ;; Get the result
        (let [result (deref result-future 5000 {:proceed false :reason "timeout"})]
          (is (true? (:proceed result))))))))

(deftest check-stage-rejected-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:approvals {:poll-interval-ms 100}}}
        build-ctx {:build-id "build-rejected"
                   :cancelled? (atom false)
                   :event-fn (fn [_] nil)}
        stage-def {:stage-name "Deploy"
                   :approval {:message "Approve?" :role :developer}
                   :steps []}]
    (testing "rejected gate does not proceed"
      (let [result-future (future (approval/check-stage-approval! system build-ctx stage-def))]
        (Thread/sleep 200)
        (let [gate (approval-store/get-gate-for-build-stage ds "build-rejected" "Deploy")]
          (approval-store/reject-gate! ds (:id gate) "bob"))
        (let [result (deref result-future 5000 {:proceed true})]
          (is (false? (:proceed result)))
          (is (re-find #"Rejected" (:reason result))))))))

(deftest check-stage-cancelled-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:approvals {:poll-interval-ms 100}}}
        cancelled? (atom false)
        build-ctx {:build-id "build-cancel"
                   :cancelled? cancelled?
                   :event-fn (fn [_] nil)}
        stage-def {:stage-name "Deploy"
                   :approval {:message "Approve?" :role :developer}
                   :steps []}]
    (testing "cancelling build during approval wait"
      (let [result-future (future (approval/check-stage-approval! system build-ctx stage-def))]
        (Thread/sleep 200)
        ;; Cancel the build
        (reset! cancelled? true)
        (let [result (deref result-future 5000 {:proceed true})]
          (is (false? (:proceed result)))
          (is (re-find #"cancelled" (or (:reason result) ""))))))))

(deftest wait-for-approval-approved-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "wait-for-approval returns approved when gate is approved"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "wait-1" :stage-name "S1"})
            result-future (future
                            (approval/wait-for-approval! ds gate-id
                              {:poll-interval-ms 100
                               :cancelled? (atom false)}))]
        (Thread/sleep 200)
        (approval-store/approve-gate! ds gate-id "alice")
        (let [result (deref result-future 5000 nil)]
          (is (true? (:approved result)))
          (is (= "alice" (:approved-by result))))))))

(deftest wait-for-approval-rejected-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "wait-for-approval returns rejected when gate is rejected"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "wait-2" :stage-name "S1"})
            result-future (future
                            (approval/wait-for-approval! ds gate-id
                              {:poll-interval-ms 100
                               :cancelled? (atom false)}))]
        (Thread/sleep 200)
        (approval-store/reject-gate! ds gate-id "bob")
        (let [result (deref result-future 5000 nil)]
          (is (false? (:approved result)))
          (is (re-find #"Rejected" (:reason result))))))))

(deftest wait-for-approval-timeout-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "wait-for-approval handles timed-out gates"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "wait-3" :stage-name "S1"
                       :timeout-minutes 0})  ;; 0 minutes = already timed out
            result-future (future
                            (approval/wait-for-approval! ds gate-id
                              {:poll-interval-ms 100
                               :cancelled? (atom false)}))]
        (let [result (deref result-future 5000 nil)]
          (is (false? (:approved result)))
          (is (re-find #"timed out" (or (:reason result) ""))))))))

(deftest check-stage-emits-event-test
  (let [ds (conn/create-datasource test-db-path)
        events (atom [])
        system {:db ds :config {:approvals {:poll-interval-ms 100}}}
        build-ctx {:build-id "build-event"
                   :cancelled? (atom false)
                   :event-fn (fn [evt] (swap! events conj evt))}
        stage-def {:stage-name "Deploy"
                   :approval {:message "Approve deploy?" :role :admin}
                   :steps []}]
    (testing "SSE event emitted when gate is created"
      (let [result-future (future (approval/check-stage-approval! system build-ctx stage-def))]
        (Thread/sleep 300)
        ;; Approve to end the wait
        (let [gate (approval-store/get-gate-for-build-stage ds "build-event" "Deploy")]
          (approval-store/approve-gate! ds (:id gate) "admin"))
        (deref result-future 5000 nil)
        ;; Check that an event was emitted
        (is (pos? (count @events)))
        (is (= :approval-requested (:type (first @events))))
        (is (= "Deploy" (:stage-name (first @events))))
        (is (= "Approve deploy?" (:message (first @events))))))))
