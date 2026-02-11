(ns chengis.engine.docker-test
  "Unit tests for Docker command generation utilities.
   These tests verify command building — no Docker daemon required."
  (:require [clojure.test :refer :all]
            [chengis.engine.docker :as docker]
            [clojure.string :as str]))

(deftest resolve-volumes-test
  (testing "replaces legacy :workspace token at start"
    (is (= ["/home/ci:/workspace"]
           (docker/resolve-volumes "/home/ci" [":workspace:/workspace"]))))

  (testing "replaces ${WORKSPACE} token"
    (is (= ["/home/ci:/app"]
           (docker/resolve-volumes "/home/ci" ["${WORKSPACE}:/app"]))))

  (testing "handles multiple volumes"
    (let [result (docker/resolve-volumes "/work"
                   [":workspace:/app"
                    "/cache:/cache"
                    "${WORKSPACE}/src:/app/src"])]
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
      (is (str/includes? cmd "maven:3.9"))
      (is (str/includes? cmd "sh -c"))
      (is (str/includes? cmd "mvn test"))))

  (testing "custom workdir"
    (let [cmd (docker/build-docker-run-cmd
                {:image "node:18" :command "npm test" :workdir "/app"}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "/app"))
      (is (str/includes? cmd "/ws:/app"))))

  (testing "environment variables with shell quoting"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "env"
                 :env {"FOO" "bar"}}
                {:workspace "/ws" :env {"CI" "true"}})]
      (is (str/includes? cmd "-e CI='true'"))
      (is (str/includes? cmd "-e FOO='bar'"))))

  (testing "env values with spaces are safely quoted"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "echo hi"
                 :env {"MSG" "hello world"}}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "-e MSG='hello world'"))))

  (testing "extra volumes"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "ls"
                 :volumes ["/cache:/cache"]}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "/ws:/workspace"))
      (is (str/includes? cmd "/cache:/cache"))))

  (testing "network mode"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "ping localhost"
                 :network "host"}
                {:workspace "/ws" :env {}})]
      (is (str/includes? cmd "--network host"))))

  (testing "additional docker args — only flags allowed"
    (let [cmd (docker/build-docker-run-cmd
                {:image "alpine" :command "echo hi"
                 :docker-args ["--memory" "512m" "--cpus" "2"]}
                {:workspace "/ws" :env {}})]
      ;; --memory and --cpus are flags (start with -)
      (is (str/includes? cmd "--memory"))
      (is (str/includes? cmd "--cpus"))))

  (testing "invalid image name throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (docker/build-docker-run-cmd
            {:image "alpine; curl evil.com" :command "echo"}
            {:workspace "/ws" :env {}}))))

  (testing "invalid network name throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (docker/build-docker-run-cmd
            {:image "alpine" :command "echo"
             :network "host;rm -rf /"}
            {:workspace "/ws" :env {}})))))

(deftest build-docker-compose-cmd-test
  (testing "basic compose command"
    (let [cmd (docker/build-docker-compose-cmd
                {:service "api" :command "pytest tests/"}
                {:workspace "/ws"})]
      (is (str/includes? cmd "cd"))
      (is (str/includes? cmd "/ws"))
      (is (str/includes? cmd "docker-compose"))
      (is (str/includes? cmd "docker-compose.yml"))
      (is (str/includes? cmd "run --rm api"))
      (is (str/includes? cmd "pytest tests/"))))

  (testing "custom compose file"
    (let [cmd (docker/build-docker-compose-cmd
                {:service "web"
                 :command "npm test"
                 :compose-file "docker-compose.test.yml"}
                {:workspace "/ws"})]
      (is (str/includes? cmd "docker-compose.test.yml"))))

  (testing "invalid service name throws"
    (is (thrown? clojure.lang.ExceptionInfo
          (docker/build-docker-compose-cmd
            {:service "api && rm -rf /" :command "echo"}
            {:workspace "/ws"})))))

(deftest image-management-test
  (testing "ensure-image with :never returns nil"
    (is (nil? (docker/ensure-image! "alpine:latest" :never))))

  (testing "image name validation"
    (is (thrown? clojure.lang.ExceptionInfo
          (docker/pull-image! "alpine;rm -rf /")))))

;; ---------------------------------------------------------------------------
;; Phase 2c: Boundary tests for Docker name/image length validation
;; ---------------------------------------------------------------------------

(deftest image-name-length-boundary-test
  (testing "image name at exactly 256 chars is valid (> 256, not >= 256)"
    (let [name-256 (apply str "a/" (repeat 254 "x"))]
      (is (= 256 (count name-256)))
      ;; Should NOT throw — 256 is the boundary (> 256 rejects)
      (is (some? (docker/build-docker-run-cmd
                   {:image name-256 :command "echo hi"}
                   {:workspace "/ws"})))))

  (testing "image name at 257 chars is rejected"
    (let [name-257 (apply str "a/" (repeat 255 "x"))]
      (is (= 257 (count name-257)))
      (is (thrown? clojure.lang.ExceptionInfo
            (docker/build-docker-run-cmd
              {:image name-257 :command "echo hi"}
              {:workspace "/ws"}))))))
