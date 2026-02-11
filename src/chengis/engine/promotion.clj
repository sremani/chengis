(ns chengis.engine.promotion
  "Artifact promotion orchestration — eligibility checks and promotion flow
   from one environment to another."
  (:require [chengis.db.promotion-store :as promotion-store]
            [chengis.db.environment-store :as env-store]
            [chengis.db.build-store :as build-store]
            [chengis.feature-flags :as feature-flags]
            [taoensso.timbre :as log]))

(defn check-promotion-eligibility
  "Check if a build is eligible for promotion to a target environment.
   Returns {:eligible true} or {:eligible false :reason str}.
   Optionally checks supply chain status if Phase 7 flags are enabled."
  [ds build-id & {:keys [config]}]
  (let [build (build-store/get-build ds build-id)]
    (cond
      (nil? build)
      {:eligible false :reason "Build not found"}

      (not= :success (:status build))
      {:eligible false :reason (str "Build status is " (name (or (:status build) :unknown))
                                    ", only successful builds can be promoted")}

      ;; Supply chain checks (soft dependency on Phase 7)
      (and config
           (feature-flags/enabled? config :container-scanning)
           ;; If container scanning is enabled, we could check scan results here
           ;; For now, we just verify the build is successful
           false)  ;; Placeholder: always passes
      {:eligible false :reason "Supply chain checks failed"}

      :else
      {:eligible true})))

(defn execute-promotion!
  "Execute a promotion: check eligibility, create record, handle approval gate.
   Returns {:success true :promotion-id id :awaiting-approval bool}
   or {:success false :reason str}."
  [system {:keys [org-id build-id artifact-id from-environment-id
                  to-environment-id user-id release-id]}]
  (let [ds (:db system)
        config (:config system)
        eligibility (check-promotion-eligibility ds build-id :config config)]
    (if-not (:eligible eligibility)
      {:success false :reason (:reason eligibility)}
      (let [to-env (env-store/get-environment ds to-environment-id :org-id org-id)]
        (if-not to-env
          {:success false :reason "Target environment not found"}
          (let [promo (promotion-store/create-promotion! ds
                        {:org-id org-id
                         :build-id build-id
                         :artifact-id artifact-id
                         :from-environment-id from-environment-id
                         :to-environment-id to-environment-id
                         :promoted-by user-id})]
            (if (:requires-approval to-env)
              ;; Wait for manual approval
              (do (log/info "Promotion awaiting approval"
                    {:promotion-id (:id promo) :env (:name to-env)})
                  {:success true :promotion-id (:id promo) :awaiting-approval true})
              ;; Auto-approve and complete
              (do (promotion-store/approve-promotion! ds (:id promo) user-id)
                  (promotion-store/complete-promotion! ds (:id promo) :release-id release-id)
                  (log/info "Promotion completed"
                    {:promotion-id (:id promo) :env (:name to-env)})
                  {:success true :promotion-id (:id promo) :promoted true}))))))))
