(ns chengis.distributed.leader-election-test
  "Tests for leader election via advisory locks.
   Uses SQLite (always grants locks) to verify the leader loop mechanics."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.distributed.leader-election :as leader]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-leader-election-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest sqlite-detection-test
  (testing "sqlite? returns true for SQLite datasource"
    (let [ds (conn/create-datasource test-db-path)]
      (is (true? (leader/sqlite? ds))
          "SQLite datasource should be detected"))))

(deftest sqlite-always-acquires-test
  (testing "try-acquire! always returns true for SQLite"
    (let [ds (conn/create-datasource test-db-path)]
      (is (true? (leader/try-acquire! ds 100001))
          "SQLite should always acquire lock")
      (is (true? (leader/try-acquire! ds 100002))
          "SQLite should acquire any lock-id"))))

(deftest release-idempotent-test
  (testing "release! is a no-op for SQLite (no error)"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Should not throw
      (leader/release! ds 100001)
      (leader/release! ds 999999)
      (is true "release! should not throw for SQLite"))))

(deftest leader-loop-calls-start-fn-test
  (testing "start-leader-loop! calls start-fn when leadership is acquired"
    (let [ds (conn/create-datasource test-db-path)
          started? (atom false)
          loop-handle (leader/start-leader-loop!
                        ds 100001 "test-service"
                        #(reset! started? true)
                        #(reset! started? false)
                        50)]
      ;; Wait for the loop to acquire and call start-fn
      (Thread/sleep 200)
      (is (true? @started?)
          "start-fn should have been called")
      (is (true? @(:leading? loop-handle))
          "leading? atom should be true")
      ;; Cleanup
      (leader/stop-leader-loop! loop-handle)
      (Thread/sleep 100))))

(deftest leader-loop-calls-stop-fn-on-shutdown-test
  (testing "stop-leader-loop! calls stop-fn on shutdown"
    (let [ds (conn/create-datasource test-db-path)
          stopped? (atom false)
          loop-handle (leader/start-leader-loop!
                        ds 100002 "test-service-2"
                        (fn [])
                        #(reset! stopped? true)
                        50)]
      ;; Wait for leadership to be acquired
      (Thread/sleep 200)
      ;; Stop the loop
      (leader/stop-leader-loop! loop-handle)
      (Thread/sleep 200)
      (is (true? @stopped?)
          "stop-fn should be called when loop is stopped")
      (is (false? @(:leading? loop-handle))
          "leading? should be false after shutdown"))))

(deftest multiple-leader-loops-independent-test
  (testing "multiple leader loops with different lock-ids run independently"
    (let [ds (conn/create-datasource test-db-path)
          svc1-started? (atom false)
          svc2-started? (atom false)
          loop1 (leader/start-leader-loop!
                  ds 100001 "service-1"
                  #(reset! svc1-started? true)
                  #(reset! svc1-started? false)
                  50)
          loop2 (leader/start-leader-loop!
                  ds 100002 "service-2"
                  #(reset! svc2-started? true)
                  #(reset! svc2-started? false)
                  50)]
      (Thread/sleep 200)
      (is (true? @svc1-started?)
          "Service 1 should be started")
      (is (true? @svc2-started?)
          "Service 2 should be started independently")
      ;; Stop only service 1
      (leader/stop-leader-loop! loop1)
      (Thread/sleep 100)
      (is (true? @svc2-started?)
          "Service 2 should still be running after stopping service 1")
      ;; Cleanup
      (leader/stop-leader-loop! loop2)
      (Thread/sleep 100))))

(deftest try-acquire-returns-boolean-test
  (testing "try-acquire! returns a boolean value"
    (let [ds (conn/create-datasource test-db-path)
          result (leader/try-acquire! ds 123456)]
      (is (boolean? result)
          "Result should be a boolean")
      (is (true? result)
          "SQLite should always return true"))))

(deftest leader-loop-handle-structure-test
  (testing "start-leader-loop! returns a map with :stop! and :leading?"
    (let [ds (conn/create-datasource test-db-path)
          handle (leader/start-leader-loop!
                   ds 100001 "test-svc"
                   (fn []) (fn []) 50)]
      (is (fn? (:stop! handle))
          "Handle should contain :stop! function")
      (is (instance? clojure.lang.Atom (:leading? handle))
          "Handle should contain :leading? atom")
      ;; Cleanup
      (leader/stop-leader-loop! handle)
      (Thread/sleep 100))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation remediation: verify stop/failure boolean state changes
;; ---------------------------------------------------------------------------

(deftest stop-leader-loop-clears-leading-test
  (testing "stop-leader-loop! sets leading? to false and stops running"
    (let [ds (conn/create-datasource test-db-path)
          handle (leader/start-leader-loop!
                   ds 100099 "test-stop-svc"
                   (fn []) (fn []) 50)]
      ;; Wait for leadership
      (Thread/sleep 200)
      (is (true? @(:leading? handle))
          "Should be leading before stop")
      ;; Stop
      (leader/stop-leader-loop! handle)
      (Thread/sleep 200)
      (is (false? @(:leading? handle))
          "leading? should be false after stop"))))

(deftest start-fn-exception-reverts-leadership-test
  (testing "if start-fn throws, leading? stays false"
    (let [ds (conn/create-datasource test-db-path)
          error-thrown? (atom false)
          handle (leader/start-leader-loop!
                   ds 100100 "test-error-svc"
                   (fn [] (reset! error-thrown? true)
                          (throw (Exception. "start-fn boom")))
                   (fn []) 50)]
      ;; Wait for the loop to try acquiring and calling start-fn
      (Thread/sleep 300)
      (is (true? @error-thrown?)
          "start-fn should have been called")
      ;; Since start-fn threw, leading? should be false
      (is (false? @(:leading? handle))
          "leading? should be false when start-fn throws")
      ;; Cleanup
      (leader/stop-leader-loop! handle)
      (Thread/sleep 100))))
