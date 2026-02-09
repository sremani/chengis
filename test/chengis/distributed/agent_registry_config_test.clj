(ns chengis.distributed.agent-registry-config-test
  "Tests for configurable heartbeat timeout in agent registry."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.agent-registry :as agent-reg]))

(use-fixtures :each
  (fn [f]
    (agent-reg/reset-registry!)
    (agent-reg/set-config! {})  ;; reset to empty config (uses defaults)
    (f)
    (agent-reg/reset-registry!)
    (agent-reg/set-config! {})))

(deftest default-heartbeat-timeout-test
  (testing "default heartbeat timeout is 90000ms"
    ;; Register an agent, set its heartbeat to 80s ago (still within 90s default)
    (let [agent (agent-reg/register-agent! {:name "default-timeout"
                                            :url "http://dt:9090"
                                            :max-builds 2})]
      ;; Agent should be online with fresh heartbeat
      (let [agents (agent-reg/list-agents)]
        (is (= :online (:status (first (filter #(= (:id %) (:id agent)) agents)))))))))

(deftest custom-heartbeat-timeout-test
  (testing "custom timeout from config is respected"
    ;; Set a very short heartbeat timeout (1ms)
    (agent-reg/set-config! {:distributed {:heartbeat-timeout-ms 1}})
    (let [agent (agent-reg/register-agent! {:name "short-timeout"
                                            :url "http://st:9090"
                                            :max-builds 2})]
      ;; Wait a tiny bit so the heartbeat becomes stale
      (Thread/sleep 10)
      ;; With 1ms timeout, agent should be offline
      (let [agents (agent-reg/list-agents)
            found (first (filter #(= (:id %) (:id agent)) agents))]
        (is (= :offline (:status found))
            "Agent should be offline with 1ms heartbeat timeout after 10ms")))))

(deftest agent-goes-offline-after-custom-timeout-test
  (testing "check-agent-health! uses custom timeout"
    ;; Set a very short heartbeat timeout
    (agent-reg/set-config! {:distributed {:heartbeat-timeout-ms 1}})
    (let [agent (agent-reg/register-agent! {:name "health-check"
                                            :url "http://hc:9090"
                                            :max-builds 2})]
      ;; Wait for heartbeat to expire
      (Thread/sleep 10)
      ;; check-agent-health! should mark the agent offline
      (let [went-offline (agent-reg/check-agent-health!)]
        (is (= 1 went-offline) "One agent should have gone offline"))
      ;; Verify the agent is now offline
      (let [found (agent-reg/get-agent (:id agent))]
        (is (= :offline (:status found)))))))

(deftest heartbeat-timeout-config-in-default-config-test
  (testing "heartbeat-timeout-ms is in the default config"
    (let [cfg chengis.config/default-config]
      (is (= 90000 (get-in cfg [:distributed :heartbeat-timeout-ms]))
          "Default config should include heartbeat-timeout-ms = 90000"))))
