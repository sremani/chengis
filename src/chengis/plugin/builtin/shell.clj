(ns chengis.plugin.builtin.shell
  "Builtin shell step executor plugin.
   Wraps the existing process/execute-command for :shell type steps."
  (:require [chengis.engine.process :as process]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Shell Step Executor
;; ---------------------------------------------------------------------------

(defrecord ShellExecutor []
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (process/execute-command
      (cond-> {:command (:command step-def)
               :dir (:workspace build-ctx)
               :env (merge (:env build-ctx) (:env step-def))
               :timeout (:timeout step-def)}
        (seq (:mask-values build-ctx))
        (assoc :mask-values (:mask-values build-ctx))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the shell step executor plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "shell" "0.1.0" "Built-in shell command executor"
      :provides #{:step-executor}))
  (registry/register-step-executor! :shell (->ShellExecutor)))
