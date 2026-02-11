(ns chengis.distributed.queue-processor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.queue-processor :as qp]
            [chengis.distributed.build-queue :as bq]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-qproc-test.db")
(def ^:dynamic *ds* nil)
(def ^:dynamic *system* nil)

(defn setup [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds
                :config {:distributed {:enabled true
                                       :auth-token nil
                                       :dispatch {:fallback-local true
                                                  :queue-enabled true
                                                  :max-retries 3
                                                  :retry-backoff-ms 100
                                                  :circuit-breaker-threshold 3
                                                  :circuit-breaker-reset-ms 60000}}}
                :metrics nil}]
    (agent-reg/reset-registry!)
    (cb/reset-all!)
    (binding [*ds* ds *system* system]
      (f)))
  (agent-reg/reset-registry!)
  (cb/reset-all!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup)

;; ---------------------------------------------------------------------------
;; process-one! tests
;; ---------------------------------------------------------------------------

(deftest process-one-empty-queue-test
  (testing "returns nil on empty queue"
    (is (nil? (qp/process-one! *system*)))))

(deftest process-one-no-agent-test
  (testing "returns :no-agent when no agents registered"
    (bq/enqueue! *ds* "build-1" "job-1"
      {:pipeline {:stages []} :build-id "build-1"} nil)
    (let [result (qp/process-one! *system*)]
      (is (= :no-agent result))
      ;; Item should be back in queue (retrying)
      (let [item (bq/get-queue-item-by-build-id *ds* "build-1")]
        (is (= :pending (:status item)))
        (is (= 1 (:retry-count item)))))))

(deftest process-one-circuit-breaker-test
  (testing "circuit breaker filters out bad agents"
    ;; Register an agent
    (agent-reg/register-agent! {:name "bad-agent"
                                 :url "http://localhost:1"
                                 :labels #{"linux"}
                                 :max-builds 5})
    ;; Open circuit breaker for this agent
    (let [agents (agent-reg/list-agents)
          agent-id (:id (first agents))]
      (dotimes [_ 3]
        (cb/record-failure! agent-id 3))
      (is (= :open (:state (cb/get-state agent-id)))))

    ;; Enqueue a build
    (bq/enqueue! *ds* "build-cb" "job-1"
      {:pipeline {:stages []} :build-id "build-cb"} nil)
    ;; Process — should fail because agent's circuit is open
    (let [result (qp/process-one! *system*)]
      (is (= :no-agent result)))))

;; ---------------------------------------------------------------------------
;; Start/stop processor tests
;; ---------------------------------------------------------------------------

(deftest start-stop-processor-test
  (testing "processor starts and stops cleanly"
    (let [stop-fn (qp/start-processor! *system* {:poll-interval-ms 100})]
      (is (true? (qp/running?*)))
      (Thread/sleep 200) ;; Let it poll a few times
      (stop-fn)
      (Thread/sleep 100)
      (is (false? (qp/running?*))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation remediation: test boolean defaults
;; ---------------------------------------------------------------------------

(deftest process-one-no-labels-filter-test
  (testing "agents with no required labels are accepted (default true)"
    ;; Register an agent with labels, enqueue a build with NO required labels
    (agent-reg/register-agent! {:name "labeled-agent"
                                 :url "http://localhost:1"
                                 :labels #{"linux" "docker"}
                                 :max-builds 5})
    (bq/enqueue! *ds* "build-nolabel" "job-1"
      {:pipeline {:stages []} :build-id "build-nolabel"} nil)
    ;; process-one! should find the agent (empty labels → all agents match)
    ;; It will fail on HTTP dispatch, but the agent should be FOUND first,
    ;; so the result won't be :no-agent
    (let [result (qp/process-one! *system*)]
      (is (not= :no-agent result)
          "An agent should be found when no labels are required"))))

(deftest processor-not-running-initially-test
  (testing "processor is not running before start"
    (is (false? (qp/running?*))
        "Processor should not be running before start-processor! is called")))
