(ns chengis.plugin.docker-plugin-test
  "Tests for the Docker plugin registration and step execution via plugin system."
  (:require [clojure.test :refer :all]
            [chengis.plugin.builtin.docker :as docker-plugin]
            [chengis.plugin.loader :as plugin-loader]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

(use-fixtures :once
  (fn [f]
    (plugin-loader/load-plugins!)
    (f)))

(deftest docker-plugin-registration-test
  (testing "Docker plugin is registered"
    (is (some? (registry/get-plugin "docker")))
    (is (= "0.1.0" (:version (registry/get-plugin "docker")))))

  (testing "Docker step executor is registered"
    (is (some? (registry/get-step-executor :docker))))

  (testing "Docker Compose step executor is registered"
    (is (some? (registry/get-step-executor :docker-compose)))))

(deftest docker-executor-type-test
  (testing "Docker executor is a DockerExecutor"
    (is (instance? chengis.plugin.builtin.docker.DockerExecutor
                   (registry/get-step-executor :docker))))

  (testing "Docker Compose executor is a DockerComposeExecutor"
    (is (instance? chengis.plugin.builtin.docker.DockerComposeExecutor
                   (registry/get-step-executor :docker-compose)))))

(deftest docker-registry-summary-test
  (testing "Registry summary includes docker executors"
    (let [summary (registry/registry-summary)
          executors (set (:step-executors summary))]
      (is (contains? executors :docker))
      (is (contains? executors :docker-compose))
      (is (contains? executors :shell)))))
