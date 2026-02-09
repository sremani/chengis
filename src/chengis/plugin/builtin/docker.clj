(ns chengis.plugin.builtin.docker
  "Builtin Docker step executor plugin.
   Supports :docker and :docker-compose step types.
   Generates Docker commands and delegates to process/execute-command."
  (:require [chengis.db.docker-policy-store :as docker-policy-store]
            [chengis.engine.docker :as docker]
            [chengis.engine.process :as process]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Docker Step Executor
;; ---------------------------------------------------------------------------

(defrecord DockerExecutor []
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (let [;; Ensure image is available
          image (:image step-def)
          pull-policy (or (:pull-policy step-def)
                         (get-in build-ctx [:docker-config :pull-policy])
                         :if-not-present)]
      ;; Check Docker image policy before pulling/running
      (when (and image (:db build-ctx))
        (let [check (docker-policy-store/check-image-allowed
                      (:db build-ctx) image :org-id (:org-id build-ctx))]
          (when-not (:allowed check)
            (throw (ex-info (str "Docker image blocked by policy: " (:reason check))
                            {:type :docker-policy-denied :image image
                             :reason (:reason check)})))))
      ;; Pull image if needed
      (when image
        (log/info "Ensuring Docker image:" image "policy:" pull-policy)
        (docker/ensure-image! image pull-policy))
      ;; Build and execute the docker run command
      (let [cmd (docker/build-docker-run-cmd step-def build-ctx)]
        (log/info "Docker command:" cmd)
        (process/execute-command
          (cond-> {:command cmd
                   :dir (:workspace build-ctx)
                   :timeout (or (:timeout step-def)
                                (get-in build-ctx [:docker-config :default-timeout])
                                600000)}
            (seq (:mask-values build-ctx))
            (assoc :mask-values (:mask-values build-ctx))))))))

;; ---------------------------------------------------------------------------
;; Docker Compose Step Executor
;; ---------------------------------------------------------------------------

(defrecord DockerComposeExecutor []
  proto/StepExecutor
  (execute-step [_this build-ctx step-def]
    (let [cmd (docker/build-docker-compose-cmd step-def build-ctx)]
      (log/info "Docker Compose command:" cmd)
      (process/execute-command
        (cond-> {:command cmd
                 :dir (:workspace build-ctx)
                 :timeout (or (:timeout step-def) 600000)}
          (seq (:mask-values build-ctx))
          (assoc :mask-values (:mask-values build-ctx)))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Docker step executor plugins."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "docker" "0.1.0" "Built-in Docker container executor"
      :provides #{:step-executor}))
  (registry/register-step-executor! :docker (->DockerExecutor))
  (registry/register-step-executor! :docker-compose (->DockerComposeExecutor)))
