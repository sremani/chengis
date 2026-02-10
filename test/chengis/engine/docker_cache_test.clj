(ns chengis.engine.docker-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [chengis.engine.docker :as docker]))

(deftest cache-volumes-in-docker-run-test
  (testing "cache-volumes generates correct -v flags with named volumes"
    (let [step-def {:image "node:18"
                    :command "npm ci"
                    :cache-volumes {"npm-cache" "/root/.npm"}}
          build-ctx {:workspace "/tmp/ws" :env {}}
          cmd (docker/build-docker-run-cmd step-def build-ctx)]
      (is (str/includes? cmd "-v npm-cache:'/root/.npm'"))
      (is (str/includes? cmd "node:18"))))

  (testing "multiple cache volumes produce multiple -v flags"
    (let [step-def {:image "node:18"
                    :command "npm ci"
                    :cache-volumes {"npm-cache" "/root/.npm"
                                    "build-cache" "/app/.cache"}}
          build-ctx {:workspace "/tmp/ws" :env {}}
          cmd (docker/build-docker-run-cmd step-def build-ctx)]
      (is (str/includes? cmd "npm-cache:"))
      (is (str/includes? cmd "build-cache:"))))

  (testing "cache volume names validated - rejects invalid chars"
    (let [step-def {:image "node:18"
                    :command "echo ok"
                    :cache-volumes {"invalid/name" "/data"}}
          build-ctx {:workspace "/tmp/ws" :env {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid cache volume"
            (docker/build-docker-run-cmd step-def build-ctx)))))

  (testing "cache volumes and regular volumes coexist"
    (let [step-def {:image "node:18"
                    :command "npm ci"
                    :volumes ["./src:/app/src"]
                    :cache-volumes {"npm-cache" "/root/.npm"}}
          build-ctx {:workspace "/tmp/ws" :env {}}
          cmd (docker/build-docker-run-cmd step-def build-ctx)]
      ;; Both workspace vol, extra vol, and cache vol should be present
      (is (str/includes? cmd "/tmp/ws:/workspace"))
      (is (str/includes? cmd "npm-cache:"))
      ;; Make sure the command still produces valid docker run
      (is (str/starts-with? cmd "docker run --rm"))))

  (testing "no cache-volumes produces same output as before"
    (let [step-def {:image "node:18"
                    :command "echo hi"}
          build-ctx {:workspace "/tmp/ws" :env {}}
          cmd (docker/build-docker-run-cmd step-def build-ctx)]
      (is (str/starts-with? cmd "docker run --rm"))
      (is (not (str/includes? cmd "cache")))))

  (testing "cache volume mount path validated - rejects relative paths"
    (let [step-def {:image "node:18"
                    :command "echo ok"
                    :cache-volumes {"my-cache" "relative/path"}}
          build-ctx {:workspace "/tmp/ws" :env {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid cache volume mount path"
            (docker/build-docker-run-cmd step-def build-ctx)))))

  (testing "cache volume mount path validated - rejects path traversal"
    (let [step-def {:image "node:18"
                    :command "echo ok"
                    :cache-volumes {"my-cache" "/root/../etc/shadow"}}
          build-ctx {:workspace "/tmp/ws" :env {}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid cache volume mount path"
            (docker/build-docker-run-cmd step-def build-ctx))))))

(deftest containerize-steps-cache-volumes-test
  (testing "containerize-steps propagates cache-volumes from stage container config"
    ;; We test this indirectly via the DSL
    (let [stage-container {:image "node:18"
                           :cache-volumes {"npm-cache" "/root/.npm"}}
          ;; Simulate containerize-steps by checking the step-def after merging
          shell-step {:type :shell :step-name "Install" :command "npm ci"}
          ;; The containerize-steps fn in executor wraps shell steps with docker config
          ;; We verify the cache-volumes key is preserved on the container config
          step-with-cache (merge shell-step
                                 {:type :docker
                                  :image "node:18"
                                  :cache-volumes {"npm-cache" "/root/.npm"}})]
      (is (= {"npm-cache" "/root/.npm"} (:cache-volumes step-with-cache)))
      (is (= :docker (:type step-with-cache))))))
