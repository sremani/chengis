(ns chengis.plugin.loader-test
  (:require [clojure.test :refer :all]
            [chengis.plugin.loader :as loader]
            [chengis.plugin.registry :as registry]))

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

(deftest load-builtin-plugins-test
  (testing "load-plugins! registers all builtins"
    (let [summary (loader/load-plugins!)]
      ;; Should have registered plugins
      (is (>= (:plugins summary) 5) "Should have at least 5 builtin plugins")

      ;; Step executors
      (is (some? (registry/get-step-executor :shell))
          "Shell step executor should be registered")

      ;; Notifiers
      (is (some? (registry/get-notifier :console))
          "Console notifier should be registered")
      (is (some? (registry/get-notifier :slack))
          "Slack notifier should be registered")

      ;; Artifact handlers
      (is (some? (registry/get-artifact-handler "local"))
          "Local artifact handler should be registered")

      ;; SCM providers
      (is (some? (registry/get-scm-provider :git))
          "Git SCM provider should be registered"))))

(deftest load-plugins-idempotent-test
  (testing "loading plugins twice doesn't break anything"
    (loader/load-plugins!)
    (loader/load-plugins!)
    (is (some? (registry/get-step-executor :shell)))))

(deftest load-plugins-with-system-test
  (testing "load-plugins! accepts a system map"
    (let [system {:config {:plugins {:directory "/nonexistent/path"}}}
          summary (loader/load-plugins! system)]
      ;; Should still load builtins even if external dir doesn't exist
      (is (>= (:plugins summary) 5)))))

(deftest registry-summary-after-load-test
  (testing "registry summary reflects loaded plugins"
    (loader/load-plugins!)
    (let [summary (registry/registry-summary)]
      (is (pos? (:plugins summary)))
      (is (seq (:step-executors summary)))
      (is (seq (:notifiers summary))))))
