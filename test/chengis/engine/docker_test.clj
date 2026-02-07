(ns chengis.engine.docker-test
  "Unit tests for Docker command generation utilities.
   These tests verify command building — no Docker daemon required."
  (:require [clojure.test :refer :all]
            [chengis.engine.docker :as docker]
            [clojure.string :as str]))

(deftest resolve-volumes-test
  (testing "replaces :workspace token"
    (is (= ["/home/ci:/workspace"]
           (docker/resolve-volumes "/home/ci" [":workspace:/workspace"]))))

  (testing "handles multiple volumes"
    (let [result (docker/resolve-volumes "/work"
                   [":workspace:/app"
                    "/cache:/cache"
                    ":workspace/src:/app/src"])]
      (is (= ["/work:/app" "/cache:/cache" "/work/src:/app/src"] result))))

  (testing "returns nil for empty volumes"
    (is (nil? (docker/resolve-volumes "/work" nil)))
    (is (nil? (docker/resolve-volumes "/work" [])))))

(deftest build-docker-run-cmd-test
  (testing "basic docker run command"
    (let [step-def {:image "maven:3.9"
                    :command "mvn test"}
          build-ctx {:workspace "/workspace/job/1"
                     :env {}}
          cmd (docker/build-docker-run-cmd step-def build-ctx)]
      (is (str/starts-with? cmd "docker run --rm"))
      (is (str/includes? cmd "-v /workspace/job/1:/workspace"))
      (is (str/includes? cmd "-w /workspace"))
      (is (str/includes? cmd "maven:3.9"))
      (is (str/includes? cmd "sh -c"))
      (is (str/includes? cmd "mvn test"))))

  (testing "custom workdir"
    (let [cmd (docker/build-docker-run-cmd
                {:image "node:18" :command "npm test" :workdir "/app"}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "-w /app"))
      (is (str/includes? cmd "-v /ws:/app"))))

  (testing "environment variables"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "env"
                 :env {"FOO" "bar"}}
                {:workspace "/ws" :env {"CI" "true"}})]
      (is (str/includes? cmd "-e CI=true"))
      (is (str/includes? cmd "-e FOO=bar"))))

  (testing "extra volumes"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "ls"
                 :volumes ["/cache:/cache" ":workspace/src:/src"]}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "-v /ws:/workspace"))
      (is (str/includes? cmd "-v /cache:/cache"))
      (is (str/includes? cmd "-v /ws/src:/src"))))

  (testing "network mode"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "ping localhost"
                 :network "host"}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "--network host"))))

  (testing "additional docker args"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "echo hi"
                 :docker-args ["--memory" "512m" "--cpus" "2"]}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "--memory 512m"))
      (is (str/includes? cmd "--cpus 2")))))

(deftest build-docker-compose-cmd-test
  (testing "basic compose command"
    (let [cmd (docker/build-docker-compose-cmd
                {:service "api" :command "pytest tests/"}
                {:workspace "/ws"})]
      (is (str/starts-with? cmd "cd /ws"))
      (is (str/includes? cmd "docker-compose"))
      (is (str/includes? cmd "-f docker-compose.yml"))
      (is (str/includes? cmd "run --rm api"))
      (is (str/includes? cmd "pytest tests/"))))

  (testing "custom compose file"
    (let [cmd (docker/build-docker-compose-cmd
                {:service "web"
                 :command "npm test"
                 :compose-file "docker-compose.test.yml"}
                {:workspace "/ws"})]
      (is (str/includes? cmd "-f docker-compose.test.yml")))))

(deftest image-management-test
  (testing "ensure-image with :never returns nil"
    (is (nil? (docker/ensure-image! "alpine:latest" :never)))))
