(ns chengis.engine.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.process :as process]))

(deftest execute-command-basic
  (testing "successful echo command"
    (let [result (process/execute-command {:command "echo 'hello world'"})]
      (is (zero? (:exit-code result)))
      (is (= "hello world\n" (:stdout result)))
      (is (= "" (:stderr result)))
      (is (pos? (:duration-ms result)))
      (is (false? (:timed-out? result)))))

  (testing "command with environment variables"
    (let [result (process/execute-command {:command "echo $MY_VAR"
                                           :env {"MY_VAR" "chengis"}})]
      (is (zero? (:exit-code result)))
      (is (= "chengis\n" (:stdout result)))))

  (testing "failing command"
    (let [result (process/execute-command {:command "exit 42"})]
      (is (= 42 (:exit-code result)))
      (is (false? (:timed-out? result)))))

  (testing "command with stderr"
    (let [result (process/execute-command {:command "echo 'oops' >&2 && exit 1"})]
      (is (= 1 (:exit-code result)))
      (is (= "oops\n" (:stderr result)))))

  (testing "command with working directory"
    (let [result (process/execute-command {:command "pwd" :dir "/tmp"})]
      (is (zero? (:exit-code result)))
      (is (clojure.string/includes? (:stdout result) "tmp"))))

  (testing "command timeout"
    (let [result (process/execute-command {:command "sleep 10" :timeout 500})]
      (is (not (zero? (:exit-code result))))
      (is (true? (:timed-out? result))))))
