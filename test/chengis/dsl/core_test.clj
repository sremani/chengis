(ns chengis.dsl.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]))

(use-fixtures :each (fn [f] (dsl/clear-registry!) (f)))

(deftest sh-test
  (testing "basic shell command"
    (is (= {:type :shell :command "echo hi"}
           (dsl/sh "echo hi"))))

  (testing "shell command with options"
    (let [result (dsl/sh "make" :env {"CC" "gcc"} :dir "/src" :timeout 5000)]
      (is (= "make" (:command result)))
      (is (= {"CC" "gcc"} (:env result)))
      (is (= "/src" (:dir result)))
      (is (= 5000 (:timeout result))))))

(deftest step-test
  (testing "step combines name with action"
    (let [s (dsl/step "Build" (dsl/sh "make"))]
      (is (= "Build" (:step-name s)))
      (is (= :shell (:type s)))
      (is (= "make" (:command s))))))

(deftest stage-test
  (testing "sequential stage"
    (let [s (dsl/stage "Build"
              (dsl/step "Compile" (dsl/sh "make compile"))
              (dsl/step "Link" (dsl/sh "make link")))]
      (is (= "Build" (:stage-name s)))
      (is (false? (:parallel? s)))
      (is (= 2 (count (:steps s))))))

  (testing "parallel stage"
    (let [s (dsl/stage "Test"
              (dsl/parallel
                (dsl/step "Unit" (dsl/sh "make test"))
                (dsl/step "Lint" (dsl/sh "make lint"))))]
      (is (= "Test" (:stage-name s)))
      (is (true? (:parallel? s)))
      (is (= 2 (count (:steps s)))))))

(deftest when-branch-test
  (testing "adds branch condition to steps"
    (let [steps (dsl/when-branch "main"
                  (dsl/step "Deploy" (dsl/sh "./deploy.sh")))]
      (is (= 1 (count steps)))
      (is (= {:type :branch :value "main"}
             (:condition (first steps)))))))

(deftest defpipeline-test
  (testing "defines and registers a pipeline"
    (let [p (dsl/defpipeline test-pipeline
              {:description "A test pipeline"}
              (dsl/stage "Build"
                (dsl/step "Compile" (dsl/sh "echo compile")))
              (dsl/stage "Test"
                (dsl/step "Run" (dsl/sh "echo test"))))]
      (is (= "test-pipeline" (:pipeline-name p)))
      (is (= "A test pipeline" (:description p)))
      (is (= 2 (count (:stages p))))
      ;; Registered in the registry
      (is (= p (dsl/get-pipeline "test-pipeline")))
      (is (= ["test-pipeline"] (vec (dsl/list-pipelines))))))

  (testing "pipeline without options map"
    (let [p (dsl/defpipeline simple
              (dsl/stage "Only"
                (dsl/step "Do" (dsl/sh "echo do"))))]
      (is (= "simple" (:pipeline-name p)))
      (is (nil? (:description p)))
      (is (= 1 (count (:stages p)))))))
