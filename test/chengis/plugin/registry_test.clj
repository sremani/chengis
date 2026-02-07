(ns chengis.plugin.registry-test
  (:require [clojure.test :refer :all]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

(deftest plugin-registration-test
  (testing "register and retrieve a plugin"
    (let [desc (proto/plugin-descriptor "test-plugin" "1.0.0" "A test plugin")]
      (registry/register-plugin! desc)
      (is (= "test-plugin" (:name (registry/get-plugin "test-plugin"))))
      (is (= "1.0.0" (:version (registry/get-plugin "test-plugin"))))))

  (testing "list-plugins includes newly registered"
    ;; test-plugin was registered in previous testing block within this deftest
    (registry/register-plugin! (proto/plugin-descriptor "p1" "0.1" "Plugin 1"))
    (registry/register-plugin! (proto/plugin-descriptor "p2" "0.2" "Plugin 2"))
    (is (>= (count (registry/list-plugins)) 2))
    (is (some? (registry/get-plugin "p1")))
    (is (some? (registry/get-plugin "p2"))))

  (testing "deregister-plugin removes it"
    (registry/register-plugin! (proto/plugin-descriptor "temp" "0.1" "Temp"))
    (is (some? (registry/get-plugin "temp")))
    (registry/deregister-plugin! "temp")
    (is (nil? (registry/get-plugin "temp")))))

(deftest step-executor-registration-test
  (testing "register and lookup step executor"
    (let [mock-executor (reify proto/StepExecutor
                          (execute-step [_ _ _] {:exit-code 0}))]
      (registry/register-step-executor! :test-type mock-executor)
      (is (some? (registry/get-step-executor :test-type)))
      (is (= {:exit-code 0}
             (proto/execute-step (registry/get-step-executor :test-type) {} {})))))

  (testing "unknown executor returns nil"
    (is (nil? (registry/get-step-executor :nonexistent))))

  (testing "list-step-executors includes registered"
    (registry/register-step-executor! :foo (reify proto/StepExecutor
                                             (execute-step [_ _ _] nil)))
    (registry/register-step-executor! :bar (reify proto/StepExecutor
                                             (execute-step [_ _ _] nil)))
    (let [types (set (registry/list-step-executors))]
      (is (contains? types :foo))
      (is (contains? types :bar)))))

(deftest notifier-registration-test
  (testing "register and lookup notifier"
    (let [mock-notifier (reify proto/Notifier
                          (send-notification [_ _ _] {:status :sent}))]
      (registry/register-notifier! :test-notify mock-notifier)
      (is (some? (registry/get-notifier :test-notify)))
      (is (= {:status :sent}
             (proto/send-notification (registry/get-notifier :test-notify) {} {}))))))

(deftest pipeline-format-registration-test
  (testing "register and lookup pipeline format"
    (let [mock-format (reify proto/PipelineFormat
                        (parse-pipeline [_ _] {:pipeline {}})
                        (detect-file [_ _] nil))]
      (registry/register-pipeline-format! "test-fmt" mock-format)
      (is (some? (registry/get-pipeline-format "test-fmt")))
      (is (= {:pipeline {}}
             (proto/parse-pipeline (registry/get-pipeline-format "test-fmt") "test.yml")))))

  (testing "get-all-pipeline-formats returns all"
    (registry/register-pipeline-format! "fmt1" (reify proto/PipelineFormat
                                                  (parse-pipeline [_ _] nil)
                                                  (detect-file [_ _] nil)))
    (is (>= (count (registry/get-all-pipeline-formats)) 1))))

(deftest registry-summary-test
  (testing "summary reflects registered components"
    (registry/register-plugin! (proto/plugin-descriptor "sum-test" "0.1" "Summary test"))
    (registry/register-step-executor! :shell (reify proto/StepExecutor
                                               (execute-step [_ _ _] nil)))
    (registry/register-notifier! :console (reify proto/Notifier
                                            (send-notification [_ _ _] nil)))
    (let [summary (registry/registry-summary)]
      (is (= 1 (:plugins summary)))
      (is (some #{:shell} (:step-executors summary)))
      (is (some #{:console} (:notifiers summary))))))

(deftest reset-registry-test
  (testing "reset clears everything"
    (registry/register-plugin! (proto/plugin-descriptor "x" "0.1" "X"))
    (registry/register-step-executor! :x (reify proto/StepExecutor
                                            (execute-step [_ _ _] nil)))
    (registry/reset-registry!)
    (is (empty? (registry/list-plugins)))
    (is (empty? (registry/list-step-executors)))))
