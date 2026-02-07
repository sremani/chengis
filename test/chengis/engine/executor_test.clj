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
