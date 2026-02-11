(ns chengis.plugin.builtin.cloudformation
  "AWS CloudFormation IaC step executor — supports validate-template, create-stack,
   update-stack, delete-stack, describe-stacks, create-change-set."
  (:require [chengis.engine.iac :as iac]
            [chengis.engine.process :as process]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- build-env
  "Merge build env with step env. Sets AWS_DEFAULT_REGION when a region is
   specified in the step or config."
  [build-ctx step-def region]
  (merge (:env build-ctx)
         (:env step-def)
         (when region
           {"AWS_DEFAULT_REGION" region})))

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
    (do (log/warn "CloudFormation" action "timed out")
        (assoc result :error (str "CloudFormation " action " timed out")))

    (= 127 (:exit-code result))
    (do (log/error "AWS CLI binary not found:" binary
                   "— ensure it is installed and on the PATH")
        (assoc result
               :error (str "AWS CLI binary not found: " binary
                           ". Install the AWS CLI or set :binary-path in config.")
               :stderr (str (:stderr result)
                            "\nAWS CLI binary not found: " binary)))

    (not (zero? (:exit-code result)))
    (do (log/warn "CloudFormation" action "failed with exit code" (:exit-code result))
        result)

    :else
    (do (log/info "CloudFormation" action "succeeded in" (:duration-ms result) "ms")
        result)))

;; ---------------------------------------------------------------------------
;; Action mapping
;; ---------------------------------------------------------------------------

(def ^:private action-map
  "Map user-friendly action names to AWS CLI cloudformation subcommands."
  {"validate"   "validate-template"
   "create"     "create-stack"
   "update"     "update-stack"
   "delete"     "delete-stack"
   "describe"   "describe-stacks"
   "change-set" "create-change-set"})

(defn- resolve-action
  "Resolve a user-specified action to the corresponding AWS CLI subcommand.
   Passes through actions that are already valid subcommands."
  [action]
  (get action-map action action))

;; ---------------------------------------------------------------------------
;; CloudFormation Step Executor
;; ---------------------------------------------------------------------------

(defrecord CloudFormationExecutor [config]
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (let [raw-action  (or (:action step-def) "validate")
          action      (resolve-action raw-action)
          cf-config   (or config (get-in build-ctx [:config :iac :cloudformation]) {})
          binary      (or (:binary-path cf-config) "aws")
          timeout     (or (:timeout step-def) (:timeout-ms cf-config) 900000)
          working-dir (or (:working-dir step-def) (:workspace build-ctx))
          region      (or (:region step-def) (:region cf-config))
          env         (build-env build-ctx step-def region)
          mask-values (:mask-values build-ctx)
          cmd         (iac/build-cloudformation-command
                        {:binary        binary
                         :action        action
                         :stack-name    (:stack-name step-def)
                         :template-file (:template-file step-def)
                         :region        region
                         :parameters    (:vars step-def)
                         :capabilities  (or (:capabilities step-def)
                                            (:default-capabilities cf-config))})
          result      (run-command cmd working-dir env timeout mask-values)]
      (handle-result result action binary))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the CloudFormation step executor plugin."
  ([] (init! nil))
  ([config]
   (registry/register-plugin!
     (proto/plugin-descriptor
       "cloudformation" "1.0.0"
       "AWS CloudFormation Infrastructure-as-Code step executor"
       :provides #{:step-executor}))
   (registry/register-step-executor! :cloudformation (->CloudFormationExecutor config))))
