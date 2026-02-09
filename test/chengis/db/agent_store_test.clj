(ns chengis.db.agent-store-test
  "Tests for agent store: CRUD, JSON serialization, heartbeat, status, builds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.agent-store :as store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-agent-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(def test-agent
  {:id "agent-001"
   :name "builder-1"
   :url "http://agent1:9090"
   :labels #{"linux" "docker"}
   :status :online
   :max-builds 4
   :system-info {:os "Linux" :cpus 8 :memory-mb 16384}
   :last-heartbeat "2025-01-15T10:00:00Z"
   :registered-at "2025-01-15T09:00:00Z"
   :current-builds 1
   :org-id "org-1"})

(deftest upsert-and-get-agent-test
  (testing "upsert inserts a new agent and retrieves it"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (let [loaded (store/get-agent-by-id ds "agent-001")]
        (is (some? loaded)
            "Agent should be retrievable after upsert")
        (is (= "builder-1" (:name loaded)))
        (is (= "http://agent1:9090" (:url loaded)))
        (is (= :online (:status loaded))
            "Status should be a keyword")
        (is (= 4 (:max-builds loaded)))
        (is (= 1 (:current-builds loaded)))
        (is (= "org-1" (:org-id loaded)))))))

(deftest upsert-updates-existing-test
  (testing "upsert updates an existing agent on conflict"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Insert initial
      (store/upsert-agent! ds test-agent)
      ;; Update with new values
      (store/upsert-agent! ds (assoc test-agent
                                 :name "builder-1-updated"
                                 :max-builds 8
                                 :current-builds 3))
      (let [loaded (store/get-agent-by-id ds "agent-001")]
        (is (= "builder-1-updated" (:name loaded))
            "Name should be updated")
        (is (= 8 (:max-builds loaded))
            "max-builds should be updated")
        (is (= 3 (:current-builds loaded))
            "current-builds should be updated")))))

(deftest update-heartbeat-test
  (testing "update-agent-heartbeat! updates timestamp and optional fields"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (store/update-agent-heartbeat! ds "agent-001"
        {:current-builds 2
         :system-info {:os "Linux" :cpus 16}})
      (let [loaded (store/get-agent-by-id ds "agent-001")]
        (is (some? (:last-heartbeat loaded))
            "Heartbeat should be updated")
        (is (not= "2025-01-15T10:00:00Z" (:last-heartbeat loaded))
            "Heartbeat should differ from original")
        (is (= :online (:status loaded))
            "Status should be set to online")
        (is (= 2 (:current-builds loaded))
            "current-builds should be updated")
        (is (= 16 (get-in loaded [:system-info :cpus]))
            "system-info should be updated")))))

(deftest update-status-test
  (testing "update-agent-status! persists status transitions"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      ;; Transition to offline
      (store/update-agent-status! ds "agent-001" :offline)
      (is (= :offline (:status (store/get-agent-by-id ds "agent-001")))
          "Status should be offline")
      ;; Transition to draining
      (store/update-agent-status! ds "agent-001" :draining)
      (is (= :draining (:status (store/get-agent-by-id ds "agent-001")))
          "Status should be draining")
      ;; Back to online
      (store/update-agent-status! ds "agent-001" :online)
      (is (= :online (:status (store/get-agent-by-id ds "agent-001")))
          "Status should be online"))))

(deftest update-builds-test
  (testing "update-agent-builds! persists current_builds count"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (store/update-agent-builds! ds "agent-001" 5)
      (is (= 5 (:current-builds (store/get-agent-by-id ds "agent-001")))
          "current-builds should be 5")
      (store/update-agent-builds! ds "agent-001" 0)
      (is (= 0 (:current-builds (store/get-agent-by-id ds "agent-001")))
          "current-builds should be 0"))))

(deftest delete-agent-test
  (testing "delete-agent! removes the agent row"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (is (some? (store/get-agent-by-id ds "agent-001"))
          "Agent should exist before delete")
      (store/delete-agent! ds "agent-001")
      (is (nil? (store/get-agent-by-id ds "agent-001"))
          "Agent should be nil after delete"))))

(deftest load-all-agents-test
  (testing "load-all-agents returns all agents with correct deserialization"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (store/upsert-agent! ds (assoc test-agent
                                 :id "agent-002"
                                 :name "builder-2"
                                 :url "http://agent2:9090"
                                 :labels #{"macos"}
                                 :org-id "org-2"))
      (let [all-agents (store/load-all-agents ds)]
        (is (= 2 (count all-agents))
            "Should load 2 agents")
        ;; Sorted by name ASC
        (is (= "builder-1" (:name (first all-agents))))
        (is (= "builder-2" (:name (second all-agents))))))))

(deftest json-roundtrip-test
  (testing "labels set and system-info map roundtrip through JSON"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds test-agent)
      (let [loaded (store/get-agent-by-id ds "agent-001")]
        ;; Labels: set ↔ JSON array
        (is (set? (:labels loaded))
            "Labels should be a set after deserialization")
        (is (= #{"linux" "docker"} (:labels loaded))
            "Labels should roundtrip correctly")
        ;; System info: map ↔ JSON object
        (is (map? (:system-info loaded))
            "System info should be a map after deserialization")
        (is (= "Linux" (get-in loaded [:system-info :os]))
            "System info :os should roundtrip")
        (is (= 8 (get-in loaded [:system-info :cpus]))
            "System info :cpus should roundtrip"))))

  (testing "nil labels and system-info are handled gracefully"
    (let [ds (conn/create-datasource test-db-path)]
      (store/upsert-agent! ds (assoc test-agent
                                 :id "agent-nil"
                                 :labels nil
                                 :system-info nil))
      (let [loaded (store/get-agent-by-id ds "agent-nil")]
        (is (= #{} (:labels loaded))
            "Nil labels should deserialize to empty set")
        (is (nil? (:system-info loaded))
            "Nil system-info should stay nil")))))
