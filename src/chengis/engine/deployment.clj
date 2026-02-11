(ns chengis.engine.deployment
  "Core deployment orchestration — execute, rollback, and cancel deployments.
   Uses environment locking to prevent concurrent deployments."
  (:require [chengis.db.deployment-store :as deployment-store]
            [chengis.db.environment-store :as env-store]
            [chengis.db.strategy-store :as strategy-store]
            [taoensso.timbre :as log]))

(defn- execute-strategy-steps!
  "Execute deployment steps based on strategy type.
   Returns sequence of step results."
  [ds deployment strategy]
  (let [strategy-type (or (:strategy-type strategy) "direct")
        steps (case strategy-type
                "direct"     [{:step-name "deploy" :step-order 1}]
                "blue-green" [{:step-name "provision-new" :step-order 1}
                              {:step-name "health-check-new" :step-order 2}
                              {:step-name "switch-traffic" :step-order 3}
                              {:step-name "drain-old" :step-order 4}]
                "canary"     [{:step-name "deploy-canary" :step-order 1}
                              {:step-name "monitor-canary" :step-order 2}
                              {:step-name "promote-full" :step-order 3}]
                "rolling"    [{:step-name "rolling-batch-1" :step-order 1}
                              {:step-name "rolling-batch-2" :step-order 2}
                              {:step-name "rolling-batch-3" :step-order 3}
                              {:step-name "rolling-batch-4" :step-order 4}]
                ;; fallback to direct
                [{:step-name "deploy" :step-order 1}])]
    (doseq [step steps]
      (let [s (deployment-store/add-deployment-step! ds
                (assoc step :deployment-id (:id deployment)))]
        (deployment-store/update-step-status! ds (:id s) "in-progress")
        ;; Simulated execution — in a real system this would invoke actual deployment
        (deployment-store/update-step-status! ds (:id s) "succeeded"
          :output (str "Step " (:step-name step) " completed successfully"))))
    (deployment-store/get-deployment-steps ds (:id deployment))))

(defn execute-deployment!
  "Execute a deployment using the specified strategy.
   Locks the environment to prevent concurrent deployments.
   Returns {:success bool :deployment-id id} or {:success false :reason str}."
  [system deployment-id]
  (let [ds (:db system)
        deployment (deployment-store/get-deployment ds deployment-id)]
    (if-not deployment
      {:success false :reason "Deployment not found"}
      (let [env-id (:environment-id deployment)
            user-id (or (:initiated-by deployment) "system")]
        ;; Try to acquire environment lock
        (if-not (env-store/lock-environment! ds env-id user-id)
          {:success false :reason "Environment is locked by another deployment"}
          (try
            ;; Mark deployment as in-progress
            (deployment-store/update-deployment-status! ds deployment-id "in-progress"
              :started-at true)
            ;; Execute strategy steps
            (let [strategy (when-let [sid (:strategy-id deployment)]
                             (strategy-store/get-strategy ds sid))]
              (execute-strategy-steps! ds deployment (or strategy {:strategy-type "direct"}))
              ;; Mark as succeeded
              (deployment-store/update-deployment-status! ds deployment-id "succeeded"
                :completed-at true)
              (log/info "Deployment succeeded" {:id deployment-id :env-id env-id})
              {:success true :deployment-id deployment-id})
            (catch Exception e
              (log/error e "Deployment failed" {:id deployment-id})
              (deployment-store/update-deployment-status! ds deployment-id "failed"
                :completed-at true)
              {:success false :reason (.getMessage e) :deployment-id deployment-id})
            (finally
              ;; Always unlock environment
              (env-store/unlock-environment! ds env-id))))))))

(defn rollback-deployment!
  "Create and execute a rollback deployment that reverts to the previous successful state.
   Returns {:success bool} or {:success false :reason str}."
  [system original-deployment-id]
  (let [ds (:db system)
        original (deployment-store/get-deployment ds original-deployment-id)]
    (if-not original
      {:success false :reason "Original deployment not found"}
      (let [prev (deployment-store/get-previous-successful-deployment
                   ds (:environment-id original) original-deployment-id)]
        (if-not prev
          {:success false :reason "No previous successful deployment to roll back to"}
          (let [rollback (deployment-store/create-deployment! ds
                           {:org-id (:org-id original)
                            :environment-id (:environment-id original)
                            :build-id (:build-id prev)
                            :strategy-id (:strategy-id original)
                            :initiated-by (:initiated-by original)
                            :rollback-of original-deployment-id})]
            (execute-deployment! system (:id rollback))))))))

(defn cancel-deployment!
  "Cancel a pending or in-progress deployment."
  [ds deployment-id]
  (let [cancelled (deployment-store/cancel-deployment! ds deployment-id)]
    (if cancelled
      (do (log/info "Deployment cancelled" {:id deployment-id})
          {:success true :deployment-id deployment-id})
      {:success false :reason "Deployment not found or already completed"})))
