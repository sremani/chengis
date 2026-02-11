(ns chengis.plugin.builtin.terraform-test
  "Tests for Terraform IaC step executor plugin.
   Covers: command building, auto-init, tool-not-found, timeout, env var passthrough,
   working directory resolution, and plugin registration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [chengis.plugin.builtin.terraform :as terraform]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.engine.process :as process]))

;; ---------------------------------------------------------------------------
;; Registry cleanup fixture
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- make-build-ctx
  [& {:keys [workspace env config mask-values]
      :or {workspace "/tmp/workspace"}}]
  (cond-> {:workspace workspace}
    env          (assoc :env env)
    config       (assoc :config config)
    mask-values  (assoc :mask-values mask-values)))

(defn- make-step-def
  [& {:keys [action working-dir var-files vars workspace timeout env]
      :or {action "plan"}}]
  (cond-> {:action action}
    working-dir  (assoc :working-dir working-dir)
    var-files    (assoc :var-files var-files)
    vars         (assoc :vars vars)
    workspace    (assoc :workspace workspace)
    timeout      (assoc :timeout timeout)
    env          (assoc :env env)))

;; ---------------------------------------------------------------------------
;; Plan/Apply/Destroy/Validate/Output commands
;; ---------------------------------------------------------------------------

(deftest terraform-plan-test
  (testing "terraform plan builds correct command and returns result"
    (let [commands-seen (atom [])
          executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (swap! commands-seen conj (:command opts))
                      {:exit-code 0 :stdout "Plan: 1 to add" :stderr ""
                       :duration-ms 2500 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "plan"))]
          (is (= 0 (:exit-code result)))
          (is (= 1 (count @commands-seen)))
          (is (str/includes? (first @commands-seen) "terraform"))
          (is (str/includes? (first @commands-seen) "plan")))))))

(deftest terraform-apply-test
  (testing "terraform apply builds apply command"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      {:exit-code 0 :stdout "Apply complete" :stderr ""
                       :duration-ms 5000 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "apply"))]
          (is (= 0 (:exit-code result))))))))

(deftest terraform-destroy-test
  (testing "terraform destroy builds destroy command"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "destroy"))
                      {:exit-code 0 :stdout "Destroy complete" :stderr ""
                       :duration-ms 3000 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "destroy"))]
          (is (= 0 (:exit-code result))))))))

(deftest terraform-validate-test
  (testing "terraform validate builds validate command"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "validate"))
                      {:exit-code 0 :stdout "Success" :stderr ""
                       :duration-ms 500 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "validate"))]
          (is (= 0 (:exit-code result))))))))

(deftest terraform-output-test
  (testing "terraform output builds output command"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "output"))
                      {:exit-code 0 :stdout "{\"vpc_id\": \"vpc-123\"}" :stderr ""
                       :duration-ms 200 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "output"))]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:stdout result) "vpc_id")))))))

;; ---------------------------------------------------------------------------
;; Auto-init behavior
;; ---------------------------------------------------------------------------

(deftest terraform-auto-init-test
  (testing "when auto-init is true, init runs before main action"
    (let [commands-seen (atom [])
          executor (terraform/->TerraformExecutor {:auto-init true})]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (swap! commands-seen conj (:command opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx)
          (make-step-def :action "plan"))
        ;; Should have run init then plan (2 commands)
        (is (= 2 (count @commands-seen)))
        (is (str/includes? (first @commands-seen) "init"))
        (is (str/includes? (second @commands-seen) "plan"))))))

(deftest terraform-no-auto-init-test
  (testing "when auto-init is false, no init runs"
    (let [commands-seen (atom [])
          executor (terraform/->TerraformExecutor {:auto-init false})]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (swap! commands-seen conj (:command opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx)
          (make-step-def :action "plan"))
        (is (= 1 (count @commands-seen)))
        (is (not (str/includes? (first @commands-seen) "init")))))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest terraform-tool-not-found-test
  (testing "exit code 127 is handled as tool-not-found"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [_]
                      {:exit-code 127 :stdout "" :stderr "terraform: command not found"
                       :duration-ms 50 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "plan"))]
          (is (= 127 (:exit-code result)))
          (is (some? (:error result)))
          (is (str/includes? (:error result) "not found")))))))

(deftest terraform-timeout-test
  (testing "timed-out? is handled correctly"
    (let [executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [_]
                      {:exit-code -1 :stdout "" :stderr ""
                       :duration-ms 600000 :timed-out? true})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "plan"))]
          (is (true? (:timed-out? result)))
          (is (some? (:error result)))
          (is (str/includes? (:error result) "timed out")))))))

;; ---------------------------------------------------------------------------
;; Environment variable passthrough
;; ---------------------------------------------------------------------------

(deftest terraform-var-injection-test
  (testing "TF_VAR_ env vars pass through to process"
    (let [captured-env (atom nil)
          executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx :env {"TF_VAR_instance_type" "t3.medium"
                                "TF_VAR_region" "us-east-1"})
          (make-step-def :action "plan"))
        (is (= "t3.medium" (get @captured-env "TF_VAR_instance_type")))
        (is (= "us-east-1" (get @captured-env "TF_VAR_region")))
        ;; TF_IN_AUTOMATION should be set
        (is (= "1" (get @captured-env "TF_IN_AUTOMATION")))))))

;; ---------------------------------------------------------------------------
;; Working directory resolution
;; ---------------------------------------------------------------------------

(deftest terraform-working-dir-test
  (testing "step-def working-dir takes precedence over build workspace"
    (let [captured-dir (atom nil)
          executor (terraform/->TerraformExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (reset! captured-dir (:dir opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx :workspace "/tmp/default-workspace")
          (make-step-def :action "plan" :working-dir "/tmp/custom-dir"))
        (is (= "/tmp/custom-dir" @captured-dir))))))

;; ---------------------------------------------------------------------------
;; Plugin registration
;; ---------------------------------------------------------------------------

(deftest terraform-init-test
  (testing "init! registers terraform plugin and executor"
    (terraform/init!)
    (let [plugin (registry/get-plugin "terraform")]
      (is (some? plugin))
      (is (= "terraform" (:name plugin)))
      (is (= "1.0.0" (:version plugin)))
      (is (contains? (:provides plugin) :step-executor)))))
