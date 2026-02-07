(ns chengis.distributed.orphan-monitor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.orphan-monitor :as orphan]
            [chengis.distributed.build-queue :as bq]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-orphan-monitor-test.db")
(def ^:dynamic *ds* nil)

(defn setup [f]
  ;; Fresh DB
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Clean agent registry
  (agent-reg/reset-registry!)
  (binding [*ds* (conn/create-datasource test-db-path)]
    (f))
  (agent-reg/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup)

;; ---------------------------------------------------------------------------
;; Helper: register an agent that is already "offline" (old heartbeat)
;; ---------------------------------------------------------------------------

(defn- register-offline-agent!
  "Register an agent and make its heartbeat stale so it appears offline."
  [name url]
  (let [agent (agent-reg/register-agent! {:name name :url url :max-builds 2})]
    ;; Manually set the heartbeat to a time far in the past
    ;; The registry uses an atom — we reach in to set stale heartbeat
    ;; so that check-agent-health! will mark it offline.
    (let [past-time (str (.minus (java.time.Instant/now) (java.time.Duration/ofSeconds 300)))]
      ;; Use heartbeat! with nil current-builds to just touch the agent,
      ;; then set the last-heartbeat to the past using swap!
      (swap! @#'agent-reg/agents
             assoc-in [(:id agent) :last-heartbeat] past-time))
    agent))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest no-orphans-when-no-offline-agents-test
  (testing "check-orphans! returns 0 when all agents are online"
    (agent-reg/register-agent! {:name "healthy" :url "http://h:9090" :max-builds 2})
    (let [result (orphan/check-orphans! {:db *ds* :metrics nil})]
      (is (= 0 result)))))

(deftest detect-orphans-requeue-test
  (testing "builds on offline agent are re-queued"
    (let [agent (register-offline-agent! "dead-agent" "http://dead:9090")
          agent-id (:id agent)]
      ;; Enqueue and dispatch a build to this agent
      (let [item (bq/enqueue! *ds* "build-orph-1" "job-1"
                               {:pipeline {:stages []} :build-id "build-orph-1"}
                               #{"linux"})]
        ;; Manually move it to dispatched for this agent
        (let [dequeued (bq/dequeue-next! *ds*)]
          (bq/mark-dispatched! *ds* (:id dequeued) agent-id)))

      ;; Now check-orphans! should detect and re-queue it
      (let [recovered (orphan/check-orphans! {:db *ds* :metrics nil})]
        (is (= 1 recovered))
        ;; The item should be back in pending status
        (let [item (bq/get-queue-item-by-build-id *ds* "build-orph-1")]
          (is (= :pending (:status item)))
          (is (= 1 (:retry-count item))))))))

(deftest multiple-orphans-on-same-agent-test
  (testing "multiple builds on dead agent are all recovered"
    (let [agent (register-offline-agent! "dead-multi" "http://dead:9091")
          agent-id (:id agent)]
      ;; Enqueue 3 builds and dispatch all to the dead agent
      (doseq [i (range 1 4)]
        (let [build-id (str "build-multi-" i)]
          (bq/enqueue! *ds* build-id "job-1"
                       {:pipeline {:stages []} :build-id build-id}
                       #{})
          (let [dequeued (bq/dequeue-next! *ds*)]
            (bq/mark-dispatched! *ds* (:id dequeued) agent-id))))

      (let [recovered (orphan/check-orphans! {:db *ds* :metrics nil})]
        (is (= 3 recovered))
        ;; All should be pending again
        (doseq [i (range 1 4)]
          (let [item (bq/get-queue-item-by-build-id *ds* (str "build-multi-" i))]
            (is (= :pending (:status item)))))))))

(deftest orphan-dead-letter-when-max-retries-test
  (testing "orphaned build that has already exhausted retries goes to dead-letter"
    (let [agent (register-offline-agent! "dead-dl" "http://dead:9092")
          agent-id (:id agent)]
      ;; Enqueue with max-retries=1
      (bq/enqueue! *ds* "build-dl-orph" "job-1"
                   {:pipeline {:stages []} :build-id "build-dl-orph"}
                   #{}
                   {:max-retries 1})
      (let [dequeued (bq/dequeue-next! *ds*)]
        (bq/mark-dispatched! *ds* (:id dequeued) agent-id))

      ;; First orphan recovery: retry-count=0 < max-retries=1, so re-enqueues to pending
      (let [recovered (orphan/check-orphans! {:db *ds* :metrics nil})]
        (is (= 1 recovered))
        (let [item (bq/get-queue-item-by-build-id *ds* "build-dl-orph")]
          (is (= :pending (:status item)))
          (is (= 1 (:retry-count item)))))

      ;; Simulate: item gets dequeued and dispatched to same dead agent again
      (let [dequeued (bq/dequeue-next! *ds*)]
        (bq/mark-dispatched! *ds* (:id dequeued) agent-id))

      ;; Second orphan recovery: retry-count=1 >= max-retries=1 → dead_letter
      (let [recovered (orphan/check-orphans! {:db *ds* :metrics nil})]
        (is (= 1 recovered))
        (let [item (bq/get-queue-item-by-build-id *ds* "build-dl-orph")]
          (is (= :dead_letter (:status item))))))))

(deftest only-offline-agents-checked-test
  (testing "online agents' dispatched builds are not touched"
    (let [online-agent (agent-reg/register-agent!
                         {:name "online" :url "http://alive:9090" :max-builds 2})
          dead-agent (register-offline-agent! "offline" "http://dead:9093")]
      ;; Dispatch to online agent
      (bq/enqueue! *ds* "build-online" "job-1"
                   {:pipeline {:stages []} :build-id "build-online"}
                   #{})
      (let [d1 (bq/dequeue-next! *ds*)]
        (bq/mark-dispatched! *ds* (:id d1) (:id online-agent)))

      ;; Dispatch to dead agent
      (bq/enqueue! *ds* "build-dead" "job-1"
                   {:pipeline {:stages []} :build-id "build-dead"}
                   #{})
      (let [d2 (bq/dequeue-next! *ds*)]
        (bq/mark-dispatched! *ds* (:id d2) (:id dead-agent)))

      (let [recovered (orphan/check-orphans! {:db *ds* :metrics nil})]
        (is (= 1 recovered))
        ;; Online agent's build untouched
        (let [online-item (bq/get-queue-item-by-build-id *ds* "build-online")]
          (is (= :dispatched (:status online-item))))
        ;; Dead agent's build re-queued
        (let [dead-item (bq/get-queue-item-by-build-id *ds* "build-dead")]
          (is (= :pending (:status dead-item))))))))

(deftest check-orphans-no-db-test
  (testing "check-orphans! returns nil when no DB"
    (is (nil? (orphan/check-orphans! {:db nil :metrics nil})))))

;; ---------------------------------------------------------------------------
;; Agent registry additions
;; ---------------------------------------------------------------------------

(deftest get-offline-agent-ids-test
  (testing "get-offline-agent-ids returns only offline agents"
    (agent-reg/register-agent! {:name "online-1" :url "http://a:9090"})
    (register-offline-agent! "offline-1" "http://b:9090")
    (register-offline-agent! "offline-2" "http://c:9090")
    ;; Update health to mark stale agents offline
    (agent-reg/check-agent-health!)
    (let [offline-ids (agent-reg/get-offline-agent-ids)]
      (is (= 2 (count offline-ids))))))

(deftest set-agent-draining-test
  (testing "draining agent status is set"
    (let [agent (agent-reg/register-agent! {:name "drain-me" :url "http://d:9090"})]
      (is (true? (agent-reg/set-agent-draining! (:id agent))))
      (is (= :draining (:status (agent-reg/get-agent (:id agent)))))
      ;; Draining agent should not appear in find-available-agent
      (is (nil? (agent-reg/find-available-agent #{}))))))

(deftest set-agent-draining-nonexistent-test
  (testing "draining nonexistent agent returns false"
    (is (false? (agent-reg/set-agent-draining! "nonexistent-id")))))
