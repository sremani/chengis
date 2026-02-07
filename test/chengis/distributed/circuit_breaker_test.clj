(ns chengis.distributed.circuit-breaker-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.circuit-breaker :as cb]))

(use-fixtures :each
  (fn [f]
    (cb/reset-all!)
    (f)
    (cb/reset-all!)))

;; ---------------------------------------------------------------------------
;; State transitions
;; ---------------------------------------------------------------------------

(deftest initial-state-test
  (testing "unknown agent starts in closed state"
    (let [state (cb/get-state "new-agent")]
      (is (= :closed (:state state)))
      (is (= 0 (:failures state)))
      (is (nil? (:last-failure state))))))

(deftest record-success-test
  (testing "success resets to closed"
    (cb/record-failure! "agent-1" 5)
    (cb/record-failure! "agent-1" 5)
    (cb/record-success! "agent-1")
    (let [state (cb/get-state "agent-1")]
      (is (= :closed (:state state)))
      (is (= 0 (:failures state))))))

(deftest record-failure-test
  (testing "failures below threshold keep circuit closed"
    (cb/record-failure! "agent-2" 3)
    (let [state (cb/get-state "agent-2")]
      (is (= :closed (:state state)))
      (is (= 1 (:failures state)))
      (is (some? (:last-failure state)))))

  (testing "failures at threshold open circuit"
    (cb/record-failure! "agent-2" 3)
    (let [result (cb/record-failure! "agent-2" 3)]
      (is (= :open result))
      (let [state (cb/get-state "agent-2")]
        (is (= :open (:state state)))
        (is (= 3 (:failures state)))
        (is (some? (:opened-at state)))))))

(deftest open-circuit-blocks-requests-test
  (testing "open circuit blocks requests"
    ;; Open the circuit (threshold = 2)
    (cb/record-failure! "agent-3" 2)
    (cb/record-failure! "agent-3" 2)
    (is (= :open (:state (cb/get-state "agent-3"))))
    ;; Should block with a long reset time
    (is (false? (cb/allow-request? "agent-3" 60000)))))

(deftest half-open-transition-test
  (testing "circuit transitions to half-open after reset timeout"
    ;; Open the circuit
    (cb/record-failure! "agent-4" 1)
    (is (= :open (:state (cb/get-state "agent-4"))))
    ;; With 0ms reset timeout, should immediately transition to half-open
    (is (true? (cb/allow-request? "agent-4" 0)))
    (is (= :half-open (:state (cb/get-state "agent-4"))))))

(deftest half-open-allows-requests-test
  (testing "half-open state allows requests"
    (cb/record-failure! "agent-5" 1)
    ;; Transition to half-open
    (cb/allow-request? "agent-5" 0)
    (is (true? (cb/allow-request? "agent-5" 60000)))))

(deftest half-open-success-closes-circuit-test
  (testing "success in half-open closes circuit"
    (cb/record-failure! "agent-6" 1)
    (cb/allow-request? "agent-6" 0)
    (is (= :half-open (:state (cb/get-state "agent-6"))))
    (cb/record-success! "agent-6")
    (is (= :closed (:state (cb/get-state "agent-6"))))))

(deftest half-open-failure-reopens-circuit-test
  (testing "failure in half-open reopens circuit"
    (cb/record-failure! "agent-7" 1)
    (cb/allow-request? "agent-7" 0)
    (is (= :half-open (:state (cb/get-state "agent-7"))))
    (cb/record-failure! "agent-7" 1)
    (is (= :open (:state (cb/get-state "agent-7"))))))

;; ---------------------------------------------------------------------------
;; Closed circuit allows requests
;; ---------------------------------------------------------------------------

(deftest closed-allows-requests-test
  (testing "closed circuit allows requests"
    (is (true? (cb/allow-request? "agent-8" 60000)))))

;; ---------------------------------------------------------------------------
;; Count and query
;; ---------------------------------------------------------------------------

(deftest count-open-test
  (testing "counts open breakers"
    (is (= 0 (cb/count-open)))
    (cb/record-failure! "open-1" 1)
    (is (= 1 (cb/count-open)))
    (cb/record-failure! "open-2" 1)
    (is (= 2 (cb/count-open)))
    ;; Close one
    (cb/record-success! "open-1")
    (is (= 1 (cb/count-open)))))

(deftest get-all-states-test
  (testing "returns all states"
    (cb/record-failure! "all-1" 3)
    (cb/record-failure! "all-2" 1)
    (let [states (cb/get-all-states)]
      (is (= :closed (:state (get states "all-1"))))
      (is (= :open (:state (get states "all-2")))))))

;; ---------------------------------------------------------------------------
;; Admin reset
;; ---------------------------------------------------------------------------

(deftest reset-test
  (testing "reset clears individual agent breaker"
    (cb/record-failure! "reset-agent" 1)
    (is (= :open (:state (cb/get-state "reset-agent"))))
    (cb/reset-agent! "reset-agent")
    (is (= :closed (:state (cb/get-state "reset-agent"))))
    (is (= 0 (:failures (cb/get-state "reset-agent"))))))

(deftest reset-all-test
  (testing "reset-all clears all breakers"
    (cb/record-failure! "r1" 1)
    (cb/record-failure! "r2" 1)
    (cb/reset-all!)
    (is (empty? (cb/get-all-states)))))
