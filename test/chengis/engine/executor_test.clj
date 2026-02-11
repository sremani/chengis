(ns chengis.engine.executor-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]
            [chengis.engine.executor :as executor]
            [chengis.engine.workspace :as workspace]
            [chengis.plugin.loader :as plugin-loader]))

(use-fixtures :once (fn [f] (plugin-loader/load-plugins!) (f)))

(def test-system
  {:config {:workspace {:root "/tmp/chengis-test-workspaces"}}})

(defn cleanup-test-workspaces []
  (workspace/cleanup-workspace "/tmp/chengis-test-workspaces"))

(deftest run-build-simple
  (testing "simple pipeline with echo commands succeeds"
    (let [pipeline (dsl/defpipeline test-simple
                     (dsl/stage "Hello"
                       (dsl/step "Greet" (dsl/sh "echo 'hello world'"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 1 (count (:stage-results result))))
      (is (= :success (-> result :stage-results first :stage-status)))
      (is (= "hello world\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

(deftest run-build-multi-stage
  (testing "multi-stage pipeline runs stages sequentially"
    (let [pipeline (dsl/defpipeline test-multi
                     (dsl/stage "One"
                       (dsl/step "S1" (dsl/sh "echo 'stage one'")))
                     (dsl/stage "Two"
                       (dsl/step "S2" (dsl/sh "echo 'stage two'"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 2 (count (:stage-results result))))
      (is (every? #(= :success (:stage-status %)) (:stage-results result)))
      (cleanup-test-workspaces))))

(deftest run-build-failure-stops-pipeline
  (testing "stage failure stops the pipeline"
    (let [pipeline (dsl/defpipeline test-fail
                     (dsl/stage "Pass"
                       (dsl/step "OK" (dsl/sh "echo ok")))
                     (dsl/stage "Fail"
                       (dsl/step "Bad" (dsl/sh "exit 1")))
                     (dsl/stage "Never"
                       (dsl/step "Skip" (dsl/sh "echo never"))))
          result (executor/run-build test-system pipeline {})]
      (is (= :failure (:build-status result)))
      ;; Only 2 stages ran (third was skipped due to pipeline stop)
      (is (= 2 (count (:stage-results result))))
      (is (= :success (-> result :stage-results first :stage-status)))
      (is (= :failure (-> result :stage-results second :stage-status)))
      (cleanup-test-workspaces))))

(deftest run-build-parallel-steps
  (testing "parallel steps run concurrently"
    (let [pipeline (dsl/defpipeline test-parallel
                     (dsl/stage "Parallel"
                       (dsl/parallel
                         (dsl/step "A" (dsl/sh "echo a && sleep 0.2"))
                         (dsl/step "B" (dsl/sh "echo b && sleep 0.2"))
                         (dsl/step "C" (dsl/sh "echo c && sleep 0.2")))))
          start (System/currentTimeMillis)
          result (executor/run-build test-system pipeline {})
          duration (- (System/currentTimeMillis) start)]
      (is (= :success (:build-status result)))
      (is (= 3 (count (-> result :stage-results first :step-results))))
      ;; All three steps should complete; if truly parallel,
      ;; total time should be closer to 200ms than 600ms
      (is (< duration 2000) "Parallel steps should not take 3x sequential time")
      (cleanup-test-workspaces))))

(deftest run-build-with-env
  (testing "environment variables are passed to steps"
    (let [pipeline (dsl/defpipeline test-env
                     (dsl/stage "Env"
                       (dsl/step "Check" (dsl/sh "echo $MY_VAR"
                                           :env {"MY_VAR" "chengis"}))))
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= "chengis\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

(deftest run-build-with-condition
  (testing "conditional step skips when condition not met"
    (let [pipeline (dsl/defpipeline test-cond
                     (dsl/stage "Deploy"
                       (dsl/when-branch "release"
                         (dsl/step "Deploy" (dsl/sh "echo deploying")))))
          result (executor/run-build test-system pipeline
                                     {:parameters {:branch "develop"}})]
      ;; Step should be skipped because branch != "release"
      (is (= :skipped (-> result :stage-results first :step-results first :step-status)))
      (cleanup-test-workspaces)))

  (testing "conditional step runs when condition met"
    (let [pipeline (dsl/defpipeline test-cond-pass
                     (dsl/stage "Deploy"
                       (dsl/when-branch "main"
                         (dsl/step "Deploy" (dsl/sh "echo deploying")))))
          result (executor/run-build test-system pipeline
                                     {:parameters {:branch "main"}})]
      (is (= :success (-> result :stage-results first :step-results first :step-status)))
      (is (= "deploying\n"
             (-> result :stage-results first :step-results first :stdout)))
      (cleanup-test-workspaces))))

;; ---------------------------------------------------------------------------
;; Phase 3c: Executor condition evaluation, cancellation, event-fn tests
;; ---------------------------------------------------------------------------

(deftest evaluate-condition-nil-returns-true-test
  (testing "nil condition evaluates to true (unconditional)"
    (is (true? (#'executor/evaluate-condition nil {}))
        "nil condition should return true")))

(deftest evaluate-condition-always-type-test
  (testing ":always condition type returns true"
    (is (true? (#'executor/evaluate-condition {:type :always} {}))
        ":always should return true")))

(deftest evaluate-condition-unknown-type-test
  (testing "unknown condition type defaults to true"
    (is (true? (#'executor/evaluate-condition {:type :unknown-xyz} {}))
        "Unknown condition type should default to true")))

(deftest evaluate-condition-branch-default-main-test
  (testing "branch condition defaults to 'main' when no :branch param"
    (is (true? (#'executor/evaluate-condition
                 {:type :branch :value "main"} {}))
        "Should match default 'main' when no branch param provided")
    (is (false? (#'executor/evaluate-condition
                  {:type :branch :value "release"} {}))
        "Should not match 'release' against default 'main'")))

(deftest evaluate-condition-param-type-test
  (testing ":param condition matches parameter value"
    (is (true? (#'executor/evaluate-condition
                 {:type :param :param "env" :value "production"}
                 {:parameters {:env "production"}})))
    (is (false? (#'executor/evaluate-condition
                  {:type :param :param "env" :value "production"}
                  {:parameters {:env "staging"}})))))

(deftest cancelled-build-aborts-step-test
  (testing "cancelled build returns :aborted step status"
    (let [cancelled (atom true)
          build-ctx {:build-id "cancel-test"
                     :cancelled? cancelled
                     :current-stage "Deploy"}
          step-def {:step-name "should-abort" :command "echo never"}
          result (executor/run-step build-ctx step-def)]
      (is (= :aborted (:step-status result)))
      (is (= -2 (:exit-code result))))))

(deftest event-fn-receives-events-test
  (testing "event-fn is called with step-started and step-completed events"
    (let [events (atom [])
          build-ctx {:build-id "evt-test"
                     :current-stage "Build"
                     :event-fn (fn [evt] (swap! events conj evt))
                     :workspace "/tmp"
                     :env {}}
          step-def {:step-name "echo-hi" :command "echo hi" :type :shell}]
      (executor/run-step build-ctx step-def)
      ;; Should have at least step-started and step-completed
      (let [types (set (map :event-type @events))]
        (is (contains? types :step-started)
            "Should emit :step-started event")
        (is (contains? types :step-completed)
            "Should emit :step-completed event")
        ;; Each event should have build-id and timestamp
        (doseq [evt @events]
          (is (= "evt-test" (:build-id evt)))
          (is (some? (:timestamp evt))))))))

(deftest step-condition-skip-emits-completed-test
  (testing "skipped step still emits step-completed event"
    (let [events (atom [])
          build-ctx {:build-id "skip-test"
                     :current-stage "Deploy"
                     :event-fn (fn [evt] (swap! events conj evt))
                     :parameters {:branch "develop"}}
          step-def {:step-name "deploy"
                    :command "echo deploy"
                    :condition {:type :branch :value "release"}}
          result (executor/run-step build-ctx step-def)]
      (is (= :skipped (:step-status result)))
      (is (some #(= :step-completed (:event-type %)) @events)
          "Skipped step should still emit step-completed"))))
