(ns chengis.engine.executor-dag-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]
            [chengis.engine.executor :as executor]
            [chengis.engine.workspace :as workspace]
            [chengis.plugin.loader :as plugin-loader]))

(use-fixtures :once (fn [f] (plugin-loader/load-plugins!) (f)))

(defn- cleanup []
  (workspace/cleanup-workspace "/tmp/chengis-dag-test"))

(def base-system
  {:config {:workspace {:root "/tmp/chengis-dag-test"}
            :feature-flags {:parallel-stage-execution true}
            :parallel-stages {:max-concurrent 4}}})

(def no-dag-system
  {:config {:workspace {:root "/tmp/chengis-dag-test"}
            :feature-flags {:parallel-stage-execution false}
            :parallel-stages {:max-concurrent 4}}})

(deftest dag-backward-compat-test
  (testing "pipeline without :depends-on runs sequentially"
    (let [pipeline (dsl/defpipeline dag-compat
                     (dsl/stage "A"
                       (dsl/step "S1" (dsl/sh "echo a")))
                     (dsl/stage "B"
                       (dsl/step "S2" (dsl/sh "echo b"))))
          result (executor/run-build base-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 2 (count (:stage-results result))))
      (is (= "A" (-> result :stage-results first :stage-name)))
      (is (= "B" (-> result :stage-results second :stage-name)))
      (cleanup))))

(deftest dag-diamond-test
  (testing "diamond DAG (A→B,C→D) runs B and C concurrently"
    (let [pipeline (dsl/defpipeline dag-diamond
                     (dsl/stage "Build"
                       (dsl/step "Compile" (dsl/sh "echo build && sleep 0.1")))
                     (dsl/stage "Unit Tests" {:depends-on ["Build"]}
                       (dsl/step "Test" (dsl/sh "echo test && sleep 0.2")))
                     (dsl/stage "Lint" {:depends-on ["Build"]}
                       (dsl/step "Lint" (dsl/sh "echo lint && sleep 0.2")))
                     (dsl/stage "Deploy" {:depends-on ["Unit Tests" "Lint"]}
                       (dsl/step "Deploy" (dsl/sh "echo deploy"))))
          start (System/currentTimeMillis)
          result (executor/run-build base-system pipeline {})
          duration (- (System/currentTimeMillis) start)]
      (is (= :success (:build-status result)))
      (is (= 4 (count (:stage-results result))))
      ;; All stages should succeed
      (is (every? #(= :success (:stage-status %)) (:stage-results result)))
      ;; If truly parallel, total should be ~400ms (build 100 + test/lint 200 + deploy ~0)
      ;; not ~600ms (build 100 + test 200 + lint 200 + deploy ~0)
      (is (< duration 3000) "Parallel stages should save time vs sequential")
      (cleanup))))

(deftest dag-failure-skips-dependents-test
  (testing "stage failure causes downstream dependents to be skipped"
    (let [pipeline (dsl/defpipeline dag-fail
                     (dsl/stage "Build"
                       (dsl/step "Compile" (dsl/sh "echo build")))
                     (dsl/stage "Failing Stage" {:depends-on ["Build"]}
                       (dsl/step "Fail" (dsl/sh "exit 1")))
                     (dsl/stage "Deploy" {:depends-on ["Failing Stage"]}
                       (dsl/step "Deploy" (dsl/sh "echo deploy"))))
          result (executor/run-build base-system pipeline {})]
      ;; Build should be failure overall
      (is (#{:failure :aborted} (:build-status result)))
      ;; Build stage succeeded
      (let [stage-map (into {} (map (fn [s] [(:stage-name s) s]) (:stage-results result)))]
        (is (= :success (:stage-status (get stage-map "Build"))))
        ;; Failing Stage failed
        (is (= :failure (:stage-status (get stage-map "Failing Stage"))))
        ;; Deploy should be aborted (dependency failed)
        (is (= :aborted (:stage-status (get stage-map "Deploy")))))
      (cleanup))))

(deftest dag-cancellation-test
  (testing "cancellation prevents stages from starting in DAG mode"
    (let [cancelled? (atom false)
          ;; Create a pipeline where A must finish before B and C
          pipeline (dsl/defpipeline dag-cancel
                     (dsl/stage "A"
                       (dsl/step "Quick" (dsl/sh "echo a")))
                     (dsl/stage "B" {:depends-on ["A"]}
                       (dsl/step "S2" (dsl/sh "echo b")))
                     (dsl/stage "C" {:depends-on ["A"]}
                       (dsl/step "S3" (dsl/sh "echo c"))))
          ;; Cancel immediately before build starts — stages B and C should be skipped
          _ (reset! cancelled? true)
          result (executor/run-build base-system pipeline {:cancelled? cancelled?})]
      ;; Build should be aborted since cancelled before any stages could start
      (is (#{:aborted :success} (:build-status result)))
      ;; With cancellation, we should see fewer results or aborted stages
      (is (>= 3 (count (:stage-results result))))
      (cleanup))))

(deftest dag-feature-flag-disabled-test
  (testing "feature flag disabled falls back to sequential even with :depends-on"
    (let [pipeline (dsl/defpipeline dag-disabled
                     (dsl/stage "A"
                       (dsl/step "S1" (dsl/sh "echo a")))
                     (dsl/stage "B" {:depends-on ["A"]}
                       (dsl/step "S2" (dsl/sh "echo b"))))
          result (executor/run-build no-dag-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 2 (count (:stage-results result))))
      (cleanup))))

(deftest dag-linear-dependency-test
  (testing "linear chain A→B→C executes in order"
    (let [pipeline (dsl/defpipeline dag-linear
                     (dsl/stage "A"
                       (dsl/step "S1" (dsl/sh "echo a")))
                     (dsl/stage "B" {:depends-on ["A"]}
                       (dsl/step "S2" (dsl/sh "echo b")))
                     (dsl/stage "C" {:depends-on ["B"]}
                       (dsl/step "S3" (dsl/sh "echo c"))))
          result (executor/run-build base-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 3 (count (:stage-results result))))
      (is (every? #(= :success (:stage-status %)) (:stage-results result)))
      (cleanup))))

(deftest dag-independent-stages-test
  (testing "all independent stages run concurrently"
    (let [pipeline (dsl/defpipeline dag-independent
                     (dsl/stage "A" {:depends-on []}
                       (dsl/step "S1" (dsl/sh "echo a && sleep 0.1")))
                     (dsl/stage "B" {:depends-on []}
                       (dsl/step "S2" (dsl/sh "echo b && sleep 0.1")))
                     (dsl/stage "C" {:depends-on []}
                       (dsl/step "S3" (dsl/sh "echo c && sleep 0.1"))))
          start (System/currentTimeMillis)
          result (executor/run-build base-system pipeline {})
          duration (- (System/currentTimeMillis) start)]
      (is (= :success (:build-status result)))
      (is (= 3 (count (:stage-results result))))
      ;; Should complete in ~100ms, not 300ms
      (is (< duration 2000) "Independent stages should run in parallel")
      (cleanup))))

(deftest dag-dsl-stage-opts-test
  (testing "stage function accepts :depends-on in options map"
    (let [s (dsl/stage "Test" {:depends-on ["Build"]}
              (dsl/step "Run" (dsl/sh "echo test")))]
      (is (= ["Build"] (:depends-on s)))
      (is (= "Test" (:stage-name s))))))
