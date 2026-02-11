(ns chengis.plugin.builtin.pulumi
  "Pulumi IaC step executor — supports preview, up, destroy, output, refresh."
  (:require [chengis.engine.iac :as iac]
            [chengis.engine.process :as process]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- build-env
  "Merge build env with step env. Sets PULUMI_SKIP_UPDATE_CHECK=1 and
   PULUMI_NON_INTERACTIVE=1 for CI mode. Optionally sets PULUMI_BACKEND_URL."
  [build-ctx step-def pulumi-config]
  (merge (:env build-ctx)
         (:env step-def)
         {"PULUMI_SKIP_UPDATE_CHECK" "1"
          "PULUMI_NON_INTERACTIVE"   "1"}
         (when-let [bu (:backend-url pulumi-config)]
           {"PULUMI_BACKEND_URL" bu})))

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
    (do (log/warn "Pulumi" action "timed out")
        (assoc result :error (str "Pulumi " action " timed out")))

    (= 127 (:exit-code result))
    (do (log/error "Pulumi binary not found:" binary
                   "— ensure it is installed and on the PATH")
        (assoc result
               :error (str "Pulumi binary not found: " binary
                           ". Install Pulumi or set :binary-path in config.")
               :stderr (str (:stderr result)
                            "\nPulumi binary not found: " binary)))

    (not (zero? (:exit-code result)))
    (do (log/warn "Pulumi" action "failed with exit code" (:exit-code result))
        result)

    :else
    (do (log/info "Pulumi" action "succeeded in" (:duration-ms result) "ms")
        result)))

;; ---------------------------------------------------------------------------
;; Pulumi Step Executor
;; ---------------------------------------------------------------------------

(defrecord PulumiExecutor [config]
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (let [action       (or (:action step-def) "preview")
          pulumi-config (or config (get-in build-ctx [:config :iac :pulumi]) {})
          binary       (or (:binary-path pulumi-config) "pulumi")
          timeout      (or (:timeout step-def) (:timeout-ms pulumi-config) 600000)
          working-dir  (or (:working-dir step-def) (:workspace build-ctx))
          env          (build-env build-ctx step-def pulumi-config)
          mask-values  (:mask-values build-ctx)
          stack-name   (or (:stack-name step-def) (:default-stack pulumi-config))]
      ;; If a stack is specified, select it first
      (if stack-name
        (let [select-cmd (str binary " stack select " stack-name " --non-interactive")
              select-result (run-command select-cmd working-dir env timeout mask-values)]
          (cond
            ;; Check timeout first
            (:timed-out? select-result)
            (do (log/warn "Pulumi stack select timed out for stack:" stack-name)
                (assoc select-result
                       :error (str "Pulumi stack select timed out for: " stack-name)))

            ;; Exit 127 = binary not found
            (= 127 (:exit-code select-result))
            (do (log/error "Pulumi binary not found:" binary)
                (assoc select-result
                       :error (str "Pulumi binary not found: " binary
                                   ". Install Pulumi or set :binary-path in config.")
                       :stderr (str (:stderr select-result)
                                    "\nPulumi binary not found: " binary)))

            ;; Stack select failed
            (not (zero? (:exit-code select-result)))
            (do (log/error "Pulumi stack select failed for stack:" stack-name
                           "exit:" (:exit-code select-result))
                (assoc select-result
                       :error (str "Failed to select Pulumi stack: " stack-name)))

            ;; Stack selected successfully — run main action
            :else
            (let [cmd (iac/build-pulumi-command
                        {:binary     binary
                         :action     action
                         :stack-name stack-name
                         :vars       (:vars step-def)})
                  result (run-command cmd working-dir env timeout mask-values)]
              (handle-result result action binary))))
        ;; No stack specified — run action directly
        (let [cmd (iac/build-pulumi-command
                    {:binary binary
                     :action action
                     :vars   (:vars step-def)})
              result (run-command cmd working-dir env timeout mask-values)]
          (handle-result result action binary))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Pulumi step executor plugin."
  ([] (init! nil))
  ([config]
   (registry/register-plugin!
     (proto/plugin-descriptor
       "pulumi" "1.0.0"
       "Pulumi Infrastructure-as-Code step executor"
       :provides #{:step-executor}))
   (registry/register-step-executor! :pulumi (->PulumiExecutor config))))
