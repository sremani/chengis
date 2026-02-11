(ns chengis.plugin.builtin.pulumi-test
  "Tests for Pulumi IaC step executor plugin.
   Covers: preview, up, destroy, output, refresh, stack selection,
   backend URL, tool-not-found, timeout, and plugin registration."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [chengis.plugin.builtin.pulumi :as pulumi]
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
  [& {:keys [action working-dir stack-name vars timeout env]
      :or {action "preview"}}]
  (cond-> {:action action}
    working-dir  (assoc :working-dir working-dir)
    stack-name   (assoc :stack-name stack-name)
    vars         (assoc :vars vars)
    timeout      (assoc :timeout timeout)
    env          (assoc :env env)))

;; ---------------------------------------------------------------------------
;; Preview/Up/Destroy commands
;; ---------------------------------------------------------------------------

(deftest pulumi-preview-test
  (testing "pulumi preview builds correct command and returns result"
    (let [commands-seen (atom [])
          executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (swap! commands-seen conj (:command opts))
                      {:exit-code 0 :stdout "{\"steps\": []}" :stderr ""
                       :duration-ms 1500 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "preview"))]
          (is (= 0 (:exit-code result)))
          (is (= 1 (count @commands-seen)))
          (is (str/includes? (first @commands-seen) "pulumi"))
          (is (str/includes? (first @commands-seen) "preview")))))))

(deftest pulumi-up-test
  (testing "pulumi up builds up command with --yes"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "up"))
                      {:exit-code 0 :stdout "Resources updated" :stderr ""
                       :duration-ms 5000 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "up"))]
          (is (= 0 (:exit-code result))))))))

(deftest pulumi-destroy-test
  (testing "pulumi destroy builds destroy command"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "destroy"))
                      {:exit-code 0 :stdout "Destroyed" :stderr ""
                       :duration-ms 3000 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "destroy"))]
          (is (= 0 (:exit-code result))))))))

(deftest pulumi-output-test
  (testing "pulumi output builds output command"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "output"))
                      {:exit-code 0 :stdout "{\"url\": \"https://example.com\"}" :stderr ""
                       :duration-ms 200 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "output"))]
          (is (= 0 (:exit-code result)))
          (is (str/includes? (:stdout result) "url")))))))

(deftest pulumi-refresh-test
  (testing "pulumi refresh builds refresh command"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (is (str/includes? (:command opts) "refresh"))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 1000 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "refresh"))]
          (is (= 0 (:exit-code result))))))))

;; ---------------------------------------------------------------------------
;; Stack selection
;; ---------------------------------------------------------------------------

(deftest pulumi-stack-select-test
  (testing "stack selection runs before main command"
    (let [commands-seen (atom [])
          executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (swap! commands-seen conj (:command opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx)
          (make-step-def :action "preview" :stack-name "staging"))
        ;; Should have run stack select then preview (2 commands)
        (is (= 2 (count @commands-seen)))
        (is (str/includes? (first @commands-seen) "stack"))
        (is (str/includes? (first @commands-seen) "select"))
        (is (str/includes? (second @commands-seen) "preview"))))))

;; ---------------------------------------------------------------------------
;; Backend URL
;; ---------------------------------------------------------------------------

(deftest pulumi-backend-url-test
  (testing "PULUMI_BACKEND_URL env var set when backend-url configured"
    (let [captured-env (atom nil)
          executor (pulumi/->PulumiExecutor {:backend-url "s3://my-state-bucket"})]
      (with-redefs [process/execute-command
                    (fn [opts]
                      (reset! captured-env (:env opts))
                      {:exit-code 0 :stdout "" :stderr ""
                       :duration-ms 100 :timed-out? false})]
        (proto/execute-step executor
          (make-build-ctx)
          (make-step-def :action "preview"))
        (is (= "s3://my-state-bucket" (get @captured-env "PULUMI_BACKEND_URL")))))))

;; ---------------------------------------------------------------------------
;; Error handling
;; ---------------------------------------------------------------------------

(deftest pulumi-tool-not-found-test
  (testing "exit code 127 handled as tool-not-found"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [_]
                      {:exit-code 127 :stdout "" :stderr "pulumi: command not found"
                       :duration-ms 50 :timed-out? false})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "preview"))]
          (is (= 127 (:exit-code result)))
          (is (some? (:error result)))
          (is (str/includes? (:error result) "not found")))))))

(deftest pulumi-timeout-test
  (testing "timed-out? is handled correctly"
    (let [executor (pulumi/->PulumiExecutor nil)]
      (with-redefs [process/execute-command
                    (fn [_]
                      {:exit-code -1 :stdout "" :stderr ""
                       :duration-ms 600000 :timed-out? true})]
        (let [result (proto/execute-step executor
                       (make-build-ctx)
                       (make-step-def :action "preview"))]
          (is (true? (:timed-out? result)))
          (is (some? (:error result)))
          (is (str/includes? (:error result) "timed out")))))))

;; ---------------------------------------------------------------------------
;; Plugin registration
;; ---------------------------------------------------------------------------

(deftest pulumi-init-test
  (testing "init! registers pulumi plugin and executor"
    (pulumi/init!)
    (let [plugin (registry/get-plugin "pulumi")]
      (is (some? plugin))
      (is (= "pulumi" (:name plugin)))
      (is (= "1.0.0" (:version plugin)))
      (is (contains? (:provides plugin) :step-executor)))))
