(ns chengis.engine.health-check-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]
            [chengis.db.health-check-store :as hc-store]
            [chengis.engine.health-check :as hc-engine]))

(def test-db-path "/tmp/chengis-health-check-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(defn- create-test-env! [ds slug]
  (env-store/create-environment! ds
    {:org-id "org-1" :name slug :slug slug :env-order 10}))

(deftest execute-command-check-healthy-test
  (testing "command check returns healthy for exit 0"
    (let [result (hc-engine/execute-health-check
                   {:check-type "command"
                    :config {:command "echo hello" :expected-exit-code 0 :timeout-ms 5000}})]
      (is (= "healthy" (:status result)))
      (is (some? (:response-time-ms result))))))

(deftest execute-command-check-unhealthy-test
  (testing "command check returns unhealthy for non-zero exit"
    (let [result (hc-engine/execute-health-check
                   {:check-type "command"
                    :config {:command "exit 1" :expected-exit-code 0 :timeout-ms 5000}})]
      (is (= "unhealthy" (:status result))))))

(deftest execute-command-check-timeout-test
  (testing "command check returns timeout"
    (let [result (hc-engine/execute-health-check
                   {:check-type "command"
                    :config {:command "sleep 10" :timeout-ms 100}})]
      (is (= "timeout" (:status result))))))

(deftest execute-http-check-error-test
  (testing "HTTP check returns error for unreachable host"
    (let [result (hc-engine/execute-health-check
                   {:check-type "http"
                    :config {:url "http://192.0.2.1:1/health" :timeout-ms 500}})]
      (is (contains? #{"error" "timeout"} (:status result))))))

(deftest execute-unknown-check-type-test
  (testing "unknown check type returns error"
    (let [result (hc-engine/execute-health-check
                   {:check-type "unknown" :config {}})]
      (is (= "error" (:status result))))))

(deftest execute-health-check-config-json-test
  (testing "parses config-json if config is not a map"
    (let [result (hc-engine/execute-health-check
                   {:check-type "command"
                    :config-json "{\"command\":\"echo ok\",\"expected-exit-code\":0,\"timeout-ms\":5000}"})]
      (is (= "healthy" (:status result))))))

(deftest run-environment-health-checks-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev")]
    (testing "runs all enabled checks and saves results"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env)
         :name "Echo Check" :check-type "command"
         :config {:command "echo ok" :expected-exit-code 0 :timeout-ms 5000}})
      (let [results (hc-engine/run-environment-health-checks! ds (:id env))]
        (is (= 1 (count results)))
        (is (= "healthy" (:status (first results))))
        ;; Verify result was saved
        (is (some? (:result-id (first results))))))))

(deftest run-environment-health-checks-with-deployment-id-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging")]
    (testing "saves results with deployment-id"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env)
         :name "Echo" :check-type "command"
         :config {:command "echo ok" :expected-exit-code 0 :timeout-ms 5000}})
      (hc-engine/run-environment-health-checks! ds (:id env) :deployment-id "dep-123")
      (let [dep-results (hc-store/get-deployment-health-results ds "dep-123")]
        (is (= 1 (count dep-results)))
        (is (= "dep-123" (:deployment-id (first dep-results))))))))

(deftest wait-for-healthy-immediate-test
  (let [ds (test-ds)
        env (create-test-env! ds "quick")]
    (testing "returns healthy immediately when all checks pass"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env)
         :name "Fast" :check-type "command"
         :config {:command "echo ok" :expected-exit-code 0 :timeout-ms 5000}})
      (let [result (hc-engine/wait-for-healthy! ds (:id env)
                     :timeout-ms 5000 :interval-ms 100 :retries 1)]
        (is (true? (:healthy result)))))))

(deftest wait-for-healthy-no-checks-test
  (let [ds (test-ds)
        env (create-test-env! ds "empty")]
    (testing "returns healthy when no checks defined"
      (let [result (hc-engine/wait-for-healthy! ds (:id env)
                     :timeout-ms 1000 :interval-ms 100 :retries 0)]
        (is (true? (:healthy result)))))))

(deftest wait-for-unhealthy-test
  (let [ds (test-ds)
        env (create-test-env! ds "bad")]
    (testing "returns unhealthy after retries"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env)
         :name "Fail" :check-type "command"
         :config {:command "exit 1" :expected-exit-code 0 :timeout-ms 1000}})
      (let [result (hc-engine/wait-for-healthy! ds (:id env)
                     :timeout-ms 5000 :interval-ms 100 :retries 1)]
        (is (false? (:healthy result)))
        (is (some? (:reason result)))))))
