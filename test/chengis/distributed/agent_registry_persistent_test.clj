(ns chengis.distributed.agent-registry-persistent-test
  "Tests for persistent agent registry: write-through to DB, hydration,
   and graceful degradation when ds-ref is nil."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.agent-store :as agent-store]
            [chengis.distributed.agent-registry :as reg]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-agent-registry-persistent-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Reset registry state for each test
  (reg/reset-registry!)
  (reg/set-db! nil)
  (f)
  ;; Cleanup
  (reg/reset-registry!)
  (reg/set-db! nil)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest register-persists-to-db-test
  (testing "register-agent! persists the agent to the database"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      (let [agent (reg/register-agent!
                    {:name "test-builder"
                     :url "http://agent:9090"
                     :labels #{"linux"}
                     :max-builds 3
                     :org-id "org-1"})]
        ;; Verify in DB
        (let [db-agent (agent-store/get-agent-by-id ds (:id agent))]
          (is (some? db-agent)
              "Agent should exist in database after registration")
          (is (= "test-builder" (:name db-agent)))
          (is (= "http://agent:9090" (:url db-agent)))
          (is (= :online (:status db-agent)))
          (is (= 3 (:max-builds db-agent)))
          (is (= "org-1" (:org-id db-agent))))))))

(deftest heartbeat-persists-to-db-test
  (testing "heartbeat! persists heartbeat timestamp to the database"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      (let [agent (reg/register-agent!
                    {:name "hb-agent"
                     :url "http://agent:9090"})
            original-hb (:last-heartbeat agent)]
        ;; Wait briefly to ensure different timestamp
        (Thread/sleep 10)
        (is (true? (reg/heartbeat! (:id agent) {:current-builds 2}))
            "Heartbeat should return true for existing agent")
        (let [db-agent (agent-store/get-agent-by-id ds (:id agent))]
          (is (some? db-agent))
          (is (not= original-hb (:last-heartbeat db-agent))
              "DB heartbeat should be updated")
          (is (= 2 (:current-builds db-agent))
              "current-builds should be persisted"))))))

(deftest deregister-removes-from-db-test
  (testing "deregister-agent! removes the agent from the database"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      (let [agent (reg/register-agent!
                    {:name "removable"
                     :url "http://agent:9090"})]
        (is (some? (agent-store/get-agent-by-id ds (:id agent)))
            "Agent should exist in DB")
        (reg/deregister-agent! (:id agent))
        (is (nil? (agent-store/get-agent-by-id ds (:id agent)))
            "Agent should be removed from DB after deregister")
        (is (nil? (reg/get-agent (:id agent)))
            "Agent should be removed from atom after deregister")))))

(deftest increment-decrement-persist-to-db-test
  (testing "increment/decrement-builds! persists to the database"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      (let [agent (reg/register-agent!
                    {:name "build-counter"
                     :url "http://agent:9090"})]
        ;; Increment twice
        (reg/increment-builds! (:id agent))
        (reg/increment-builds! (:id agent))
        (let [db-agent (agent-store/get-agent-by-id ds (:id agent))]
          (is (= 2 (:current-builds db-agent))
              "DB should show 2 builds after two increments"))
        ;; Decrement once
        (reg/decrement-builds! (:id agent))
        (let [db-agent (agent-store/get-agent-by-id ds (:id agent))]
          (is (= 1 (:current-builds db-agent))
              "DB should show 1 build after decrement"))))))

(deftest check-health-persists-offline-status-test
  (testing "check-agent-health! persists offline status to the database"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      ;; Set very short timeout for test
      (reg/set-config! {:distributed {:heartbeat-timeout-ms 1}})
      (let [agent (reg/register-agent!
                    {:name "stale-agent"
                     :url "http://agent:9090"})]
        ;; Wait for heartbeat to expire
        (Thread/sleep 10)
        (let [went-offline (reg/check-agent-health!)]
          (is (pos? went-offline)
              "At least one agent should go offline")
          (let [db-agent (agent-store/get-agent-by-id ds (:id agent))]
            (is (= :offline (:status db-agent))
                "DB should reflect offline status"))))
      ;; Restore default config
      (reg/set-config! {}))))

(deftest restart-simulation-test
  (testing "agent state survives simulated master restart via hydrate-from-db!"
    (let [ds (conn/create-datasource test-db-path)]
      (reg/set-db! ds)
      ;; Register agents
      (let [a1 (reg/register-agent!
                 {:name "survivor-1"
                  :url "http://agent1:9090"
                  :labels #{"linux" "docker"}
                  :max-builds 4
                  :org-id "org-1"})
            a2 (reg/register-agent!
                 {:name "survivor-2"
                  :url "http://agent2:9090"
                  :labels #{"macos"}
                  :max-builds 2})]
        ;; Increment builds on first agent
        (reg/increment-builds! (:id a1))

        ;; Simulate restart: clear the atom (lose in-memory state)
        (reg/reset-registry!)
        (is (empty? (reg/list-agents))
            "Registry should be empty after reset")

        ;; Hydrate from DB (simulating startup)
        (reg/hydrate-from-db!)
        (let [agents (reg/list-agents)]
          (is (= 2 (count agents))
              "Should have 2 agents after hydration")
          ;; Verify first agent
          (let [restored-a1 (reg/get-agent (:id a1))]
            (is (some? restored-a1)
                "Agent 1 should be restored")
            (is (= "survivor-1" (:name restored-a1)))
            (is (= #{"linux" "docker"} (:labels restored-a1)))
            (is (= 4 (:max-builds restored-a1)))
            (is (= 1 (:current-builds restored-a1))
                "current-builds should persist across restart")
            (is (= "org-1" (:org-id restored-a1))))
          ;; Verify second agent
          (let [restored-a2 (reg/get-agent (:id a2))]
            (is (some? restored-a2)
                "Agent 2 should be restored")
            (is (= "survivor-2" (:name restored-a2)))
            (is (= #{"macos"} (:labels restored-a2)))))))))

(deftest graceful-degradation-without-db-test
  (testing "all operations work in atom-only mode when ds-ref is nil"
    ;; ds-ref is already nil from setup-db fixture
    (let [agent (reg/register-agent!
                  {:name "atom-only"
                   :url "http://agent:9090"
                   :labels #{"linux"}})]
      (is (some? agent)
          "Registration should work without DB")
      (is (true? (reg/heartbeat! (:id agent)))
          "Heartbeat should work without DB")
      (reg/increment-builds! (:id agent))
      (is (= 1 (:current-builds (reg/get-agent (:id agent))))
          "Increment should work without DB")
      (reg/decrement-builds! (:id agent))
      (is (= 0 (:current-builds (reg/get-agent (:id agent))))
          "Decrement should work without DB")
      (reg/deregister-agent! (:id agent))
      (is (nil? (reg/get-agent (:id agent)))
          "Deregister should work without DB"))))
