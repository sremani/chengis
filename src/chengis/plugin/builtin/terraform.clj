(ns chengis.plugin.builtin.terraform
  "Terraform IaC step executor — supports init, validate, plan, apply, destroy, output."
  (:require [chengis.engine.iac :as iac]
            [chengis.engine.process :as process]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- build-env
  "Merge build env with step env. Adds TF_IN_AUTOMATION=1 for non-interactive
   mode and TF_CLI_ARGS for parallelism from config. TF_VAR_* env vars from
   the build environment pass through automatically."
  [build-ctx step-def tf-config]
  (merge (:env build-ctx)
         (:env step-def)
         {"TF_IN_AUTOMATION" "1"}
         (when-let [parallelism (:parallelism tf-config)]
           {"TF_CLI_ARGS" (str "-parallelism=" parallelism)})))

(defn- resolve-working-dir
  "Resolve the working directory: step-def :working-dir takes precedence,
   falling back to the build workspace root."
  [build-ctx step-def]
  (or (:working-dir step-def) (:workspace build-ctx)))

(defn- run-command
  "Execute a command via process/execute-command with standard options.
   Returns the standard result map."
  [command working-dir env timeout mask-values]
  (process/execute-command
    (cond-> {:command command
             :dir working-dir
             :env env
             :timeout timeout}
      (seq mask-values)
      (assoc :mask-values mask-values))))

(defn- handle-result
  "Post-process a command result: check for timeout, tool-not-found (exit 127),
   and log appropriately. Returns the result map, potentially annotated."
  [result action binary]
  (cond
    (:timed-out? result)
    (do (log/warn "Terraform" action "timed out")
        (assoc result :error (str "Terraform " action " timed out")))

    (= 127 (:exit-code result))
    (do (log/error "Terraform binary not found:" binary
                   "— ensure it is installed and on the PATH")
        (assoc result
               :error (str "Terraform binary not found: " binary
                           ". Install Terraform or set :binary-path in config.")
               :stderr (str (:stderr result)
                            "\nTerraform binary not found: " binary)))

    (not (zero? (:exit-code result)))
    (do (log/warn "Terraform" action "failed with exit code" (:exit-code result))
        result)

    :else
    (do (log/info "Terraform" action "succeeded in" (:duration-ms result) "ms")
        result)))

;; ---------------------------------------------------------------------------
;; Terraform Step Executor
;; ---------------------------------------------------------------------------

(defrecord TerraformExecutor [config]
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (let [action      (or (:action step-def) "plan")
          tf-config   (or config (get-in build-ctx [:config :iac :terraform]) {})
          binary      (or (:binary-path tf-config) "terraform")
          timeout     (or (:timeout step-def) (:timeout-ms tf-config) 600000)
          working-dir (resolve-working-dir build-ctx step-def)
          env         (build-env build-ctx step-def tf-config)
          mask-values (:mask-values build-ctx)]
      ;; Auto-init: if enabled and this is not an "init" action, run init first
      (if (and (:auto-init tf-config)
               (not= action "init"))
        (let [init-cmd (iac/build-terraform-command
                         {:binary binary
                          :action "init"})
              init-result (run-command init-cmd working-dir env timeout mask-values)
              init-result (handle-result init-result "init" binary)]
          (if (or (:timed-out? init-result)
                  (not (zero? (:exit-code init-result))))
            ;; Init failed — return the init failure, don't proceed
            (do (log/error "Terraform auto-init failed, aborting" action)
                init-result)
            ;; Init succeeded — proceed with main action
            (let [cmd (iac/build-terraform-command
                        {:binary    binary
                         :action    action
                         :var-files (:var-files step-def)
                         :vars      (:vars step-def)
                         :workspace (:workspace step-def)})
                  result (run-command cmd working-dir env timeout mask-values)]
              (handle-result result action binary))))
        ;; No auto-init — just run the action directly
        (let [cmd (iac/build-terraform-command
                    {:binary    binary
                     :action    action
                     :var-files (:var-files step-def)
                     :vars      (:vars step-def)
                     :workspace (:workspace step-def)})
              result (run-command cmd working-dir env timeout mask-values)]
          (handle-result result action binary))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Terraform step executor plugin."
  ([] (init! nil))
  ([config]
   (registry/register-plugin!
     (proto/plugin-descriptor
       "terraform" "1.0.0"
       "Terraform Infrastructure-as-Code step executor"
       :provides #{:step-executor}))
   (registry/register-step-executor! :terraform (->TerraformExecutor config))))
