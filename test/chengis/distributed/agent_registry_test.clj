(ns chengis.distributed.agent-registry-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.agent-registry :as agent-reg]))

(use-fixtures :each
  (fn [f]
    (agent-reg/reset-registry!)
    (f)
    (agent-reg/reset-registry!)))

(deftest register-agent-test
  (testing "registers a new agent"
    (let [agent (agent-reg/register-agent!
                  {:name "test-agent"
                   :url "http://localhost:9090"
                   :labels #{"linux" "docker"}
                   :max-builds 4})]
      (is (some? (:id agent)))
      (is (= "test-agent" (:name agent)))
      (is (= "http://localhost:9090" (:url agent)))
      (is (= #{"linux" "docker"} (:labels agent)))
      (is (= 4 (:max-builds agent)))
      (is (= :online (:status agent)))))

  (testing "generates default name if none provided"
    (let [agent (agent-reg/register-agent! {:url "http://localhost:9091"})]
      (is (some? (:name agent)))
      (is (clojure.string/starts-with? (:name agent) "agent-"))))

  (testing "list-agents returns all registered"
    (agent-reg/register-agent! {:name "a1" :url "http://a1:9090"})
    (agent-reg/register-agent! {:name "a2" :url "http://a2:9090"})
    ;; Previous testing blocks in this deftest also registered agents
    (is (>= (count (agent-reg/list-agents)) 2))))

(deftest heartbeat-test
  (testing "heartbeat updates agent"
    (let [agent (agent-reg/register-agent! {:name "hb-test" :url "http://hb:9090"})]
      (is (true? (agent-reg/heartbeat! (:id agent))))
      (is (= :online (:status (agent-reg/get-agent (:id agent)))))))

  (testing "heartbeat for unknown agent returns false"
    (is (false? (agent-reg/heartbeat! "nonexistent")))))

(deftest find-available-agent-test
  (testing "finds available agent with no labels"
    (agent-reg/register-agent! {:name "free" :url "http://free:9090"
                                :max-builds 2})
    (is (some? (agent-reg/find-available-agent #{}))))

  (testing "finds agent matching labels"
    (agent-reg/register-agent! {:name "docker-agent" :url "http://docker:9090"
                                :labels #{"docker" "linux"}
                                :max-builds 2})
    (is (some? (agent-reg/find-available-agent #{"docker"})))
    (is (some? (agent-reg/find-available-agent #{"docker" "linux"}))))

  (testing "returns nil when no agent matches labels"
    (is (nil? (agent-reg/find-available-agent #{"windows" "gpu"})))))

(deftest build-count-test
  (testing "increment and decrement builds"
    (let [agent (agent-reg/register-agent! {:name "counter" :url "http://c:9090"
                                            :max-builds 3})]
      (is (= 0 (:current-builds (agent-reg/get-agent (:id agent)))))
      (agent-reg/increment-builds! (:id agent))
      (is (= 1 (:current-builds (agent-reg/get-agent (:id agent)))))
      (agent-reg/increment-builds! (:id agent))
      (is (= 2 (:current-builds (agent-reg/get-agent (:id agent)))))
      (agent-reg/decrement-builds! (:id agent))
      (is (= 1 (:current-builds (agent-reg/get-agent (:id agent)))))))

  (testing "decrement doesn't go below zero"
    (let [agent (agent-reg/register-agent! {:name "floor" :url "http://f:9090"})]
      (agent-reg/decrement-builds! (:id agent))
      (is (= 0 (:current-builds (agent-reg/get-agent (:id agent))))))))

(deftest deregister-agent-test
  (testing "deregisters an agent"
    (let [agent (agent-reg/register-agent! {:name "temp" :url "http://temp:9090"})]
      (is (some? (agent-reg/get-agent (:id agent))))
      (agent-reg/deregister-agent! (:id agent))
      (is (nil? (agent-reg/get-agent (:id agent)))))))

(deftest registry-summary-test
  (testing "summary reflects registered agents"
    (agent-reg/register-agent! {:name "s1" :url "http://s1:9090" :max-builds 3})
    (agent-reg/register-agent! {:name "s2" :url "http://s2:9090" :max-builds 2})
    (let [summary (agent-reg/registry-summary)]
      (is (= 2 (:total summary)))
      (is (= 2 (:online summary)))
      (is (= 0 (:offline summary)))
      (is (= 5 (:total-capacity summary))))))

(deftest reset-registry-test
  (testing "reset clears all agents"
    (agent-reg/register-agent! {:name "x" :url "http://x:9090"})
    (agent-reg/reset-registry!)
    (is (empty? (agent-reg/list-agents)))))
