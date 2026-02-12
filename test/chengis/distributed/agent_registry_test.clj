(ns chengis.distributed.agent-registry-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
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

  (testing "heartbeat stores string timestamps (JSON-safe)"
    (let [agent (agent-reg/register-agent! {:name "ts-test" :url "http://ts:9090"})]
      (agent-reg/heartbeat! (:id agent))
      (is (string? (:last-heartbeat (agent-reg/get-agent (:id agent)))))
      (is (string? (:registered-at (agent-reg/get-agent (:id agent)))))))

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

;; ---------------------------------------------------------------------------
;; Phase 2b: Collection ordering — agent selection with multiple agents
;; Phase 2c: Boundary tests for capacity checks
;; ---------------------------------------------------------------------------

(deftest find-agent-selects-least-loaded-test
  (testing "with two agents, least-loaded (lower current-builds) is selected"
    (agent-reg/register-agent! {:name "busy" :url "http://busy:9090"
                                 :labels #{} :max-builds 5})
    (agent-reg/register-agent! {:name "idle" :url "http://idle:9090"
                                 :labels #{} :max-builds 5})
    ;; Increment builds on "busy" agent
    (let [agents (agent-reg/list-agents)
          busy-id (:id (first (filter #(= "busy" (:name %)) agents)))]
      (agent-reg/increment-builds! busy-id)
      (agent-reg/increment-builds! busy-id))
    (let [selected (agent-reg/find-available-agent #{})]
      (is (some? selected))
      (is (= "idle" (:name selected))
          "Should select the agent with fewer current builds"))))

(deftest agent-at-max-capacity-excluded-test
  (testing "agent at max-builds is not available (< not <=)"
    (agent-reg/register-agent! {:name "full" :url "http://full:9090"
                                 :labels #{} :max-builds 2})
    (let [agents (agent-reg/list-agents)
          agent-id (:id (first agents))]
      ;; Fill to max
      (agent-reg/increment-builds! agent-id)
      (agent-reg/increment-builds! agent-id)
      ;; At max-builds=2, current-builds=2: (< 2 2) is false
      (is (nil? (agent-reg/find-available-agent #{}))
          "Agent at max capacity should not be selected")))

  (testing "agent at max-builds minus 1 is still available"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "almost" :url "http://almost:9090"
                                 :labels #{} :max-builds 2})
    (let [agents (agent-reg/list-agents)
          agent-id (:id (first agents))]
      (agent-reg/increment-builds! agent-id)
      ;; current-builds=1, max-builds=2: (< 1 2) is true
      (is (some? (agent-reg/find-available-agent #{}))
          "Agent below max capacity should be available"))))

;; ---------------------------------------------------------------------------
;; Phase 3b: Agent registry scoring, filtering, health tests
;; ---------------------------------------------------------------------------

(deftest check-agent-health-timeout-test
  (testing "agent with stale heartbeat is marked offline"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "stale" :url "http://stale:9090"
                                 :labels #{} :max-builds 2})
    (let [agents (agent-reg/list-agents)
          agent-id (:id (first agents))]
      ;; Manually set last-heartbeat to an old timestamp
      (swap! @(resolve 'chengis.distributed.agent-registry/agents)
             update agent-id assoc :last-heartbeat
             (str (.minus (java.time.Instant/now) 300 java.time.temporal.ChronoUnit/SECONDS)))
      (let [offline-count (agent-reg/check-agent-health!)]
        (is (pos? offline-count) "Should detect at least 1 offline agent")
        ;; Agent should now be offline
        (let [updated (agent-reg/get-agent agent-id)]
          (is (= :offline (:status updated))))))))

(deftest get-offline-agent-ids-test
  (testing "returns IDs of offline agents only"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "online-a" :url "http://oa:9090"
                                 :labels #{} :max-builds 2})
    (agent-reg/register-agent! {:name "offline-a" :url "http://ofa:9090"
                                 :labels #{} :max-builds 2})
    (let [agents (agent-reg/list-agents)
          offline-id (:id (first (filter #(= "offline-a" (:name %)) agents)))]
      ;; Set one agent offline manually
      (swap! @(resolve 'chengis.distributed.agent-registry/agents)
             update offline-id assoc :status :offline)
      (let [offline-ids (agent-reg/get-offline-agent-ids)]
        (is (= 1 (count offline-ids)))
        (is (= offline-id (first offline-ids)))))))

(deftest agent-draining-test
  (testing "draining agent is not available for builds"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "drain-me" :url "http://drain:9090"
                                 :labels #{} :max-builds 5})
    (let [agents (agent-reg/list-agents)
          agent-id (:id (first agents))]
      (is (some? (agent-reg/find-available-agent #{}))
          "Agent should be available before draining")
      (agent-reg/set-agent-draining! agent-id)
      (let [updated (agent-reg/get-agent agent-id)]
        (is (= :draining (:status updated))
            "Agent status should be :draining"))
      ;; Draining agents should NOT be available
      (is (nil? (agent-reg/find-available-agent #{}))
          "Draining agent should not be selected"))))

(deftest registry-summary-counts-test
  (testing "registry-summary returns correct counts for all fields"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "s1" :url "http://s1:9090"
                                 :labels #{} :max-builds 3})
    (agent-reg/register-agent! {:name "s2" :url "http://s2:9090"
                                 :labels #{} :max-builds 3})
    (let [agents (agent-reg/list-agents)
          id1 (:id (first agents))]
      (agent-reg/increment-builds! id1)
      (let [summary (agent-reg/registry-summary)]
        (is (= 2 (:total summary)))
        (is (= 2 (:online summary)))
        (is (= 0 (:offline summary)))
        (is (= 1 (:total-active summary)))))))
