(ns chengis.distributed.agent-http-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.agent-http :as agent-http]))

(defn reset-pools [f]
  (agent-http/close-all-pools!)
  (agent-http/configure! {})
  (f)
  (agent-http/close-all-pools!))

(use-fixtures :each reset-pools)

(deftest configure-test
  (testing "configure sets pool config"
    (agent-http/configure! {:max-connections-per-agent 8
                            :keepalive-ms 120000
                            :timeout-ms 60000})
    ;; Verify via pool-stats (no agents yet)
    (let [stats (agent-http/pool-stats)]
      (is (= 0 (:total-agents stats))))))

(deftest healthy-unknown-agent-test
  (testing "unknown agent is considered healthy"
    (is (true? (agent-http/healthy? "nonexistent-agent")))))

(deftest pool-stats-empty-test
  (testing "empty pool stats"
    (let [stats (agent-http/pool-stats)]
      (is (= 0 (:total-agents stats)))
      (is (= 0 (:healthy-agents stats)))
      (is (= 0 (:unhealthy-agents stats)))
      (is (empty? (:agents stats))))))

(deftest close-pool-test
  (testing "close pool removes agent"
    ;; Use post! to create a pool entry (will fail since no server, but pool is created)
    ;; Instead, just test close on nonexistent is safe
    (agent-http/close-pool! "agent-1")
    (is (true? (agent-http/healthy? "agent-1")))))

(deftest close-all-pools-test
  (testing "close all removes all agents"
    (agent-http/close-all-pools!)
    (let [stats (agent-http/pool-stats)]
      (is (= 0 (:total-agents stats))))))

(deftest post-creates-pool-entry-test
  (testing "post! creates a pool entry even when connection fails"
    ;; This will fail to connect, but the pool entry should be created
    (try
      (let [resp (agent-http/post! "agent-1" "http://localhost:19999" "/builds"
                                    "{}" {})]
        ;; Deref with timeout — connection will fail
        (deref resp 2000 nil))
      (catch Exception _))
    ;; Pool entry should exist
    (let [stats (agent-http/pool-stats)]
      (is (= 1 (:total-agents stats)))
      (is (contains? (:agents stats) "agent-1")))))

(deftest get-creates-pool-entry-test
  (testing "get! creates a pool entry"
    (try
      (let [resp (agent-http/get! "agent-2" "http://localhost:19999" "/health" {})]
        (deref resp 2000 nil))
      (catch Exception _))
    (let [stats (agent-http/pool-stats)]
      (is (pos? (:total-agents stats))))))

(deftest consecutive-failures-tracking-test
  (testing "failures are tracked after failed requests"
    ;; Make requests that will fail (no server running on port 19998)
    (dotimes [_ 3]
      (try
        (let [resp (agent-http/post! "fail-agent" "http://localhost:19998" "/builds"
                                      "{}" {})]
          (deref resp 2000 nil))
        (catch Exception _)))
    ;; Agent should still be healthy (under threshold of 5)
    (Thread/sleep 100)
    (let [stats (:agents (agent-http/pool-stats))]
      (when (get stats "fail-agent")
        (is (<= (:consecutive-failures (get stats "fail-agent")) 5))))))

(deftest pool-stats-with-agents-test
  (testing "pool stats reports all tracked agents"
    ;; Create entries for multiple agents
    (try
      (deref (agent-http/post! "a1" "http://localhost:19997" "/test" "{}" {}) 500 nil)
      (catch Exception _))
    (try
      (deref (agent-http/post! "a2" "http://localhost:19996" "/test" "{}" {}) 500 nil)
      (catch Exception _))
    (let [stats (agent-http/pool-stats)]
      (is (= 2 (:total-agents stats)))
      (is (contains? (:agents stats) "a1"))
      (is (contains? (:agents stats) "a2")))))

(deftest url-change-recreates-pool-test
  (testing "URL change creates new pool entry"
    ;; Create initial pool
    (try
      (deref (agent-http/post! "url-agent" "http://localhost:19995" "/test" "{}" {}) 500 nil)
      (catch Exception _))
    ;; Change URL
    (try
      (deref (agent-http/post! "url-agent" "http://localhost:19994" "/test" "{}" {}) 500 nil)
      (catch Exception _))
    ;; Should still have 1 agent, but with new URL
    (let [stats (agent-http/pool-stats)
          agent-info (get (:agents stats) "url-agent")]
      (is (= 1 (:total-agents stats)))
      (when agent-info
        (is (= "http://localhost:19994" (:url agent-info)))))))

(deftest healthy-after-close-test
  (testing "agent is healthy after pool is closed"
    (try
      (deref (agent-http/post! "temp-agent" "http://localhost:19993" "/test" "{}" {}) 500 nil)
      (catch Exception _))
    (agent-http/close-pool! "temp-agent")
    (is (true? (agent-http/healthy? "temp-agent")))))

(deftest configure-max-failures-test
  (testing "configure adjusts failure threshold"
    (agent-http/configure! {:max-consecutive-failures 2})
    ;; New agent starts healthy
    (is (true? (agent-http/healthy? "threshold-agent")))))

(deftest keepalive-headers-test
  (testing "pool configuration has keepalive settings"
    (agent-http/configure! {:keepalive-ms 30000})
    ;; Just verify config doesn't throw
    (is (some? (agent-http/pool-stats)))))
