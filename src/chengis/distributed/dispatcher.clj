(ns chengis.distributed.dispatcher
  "Build dispatch for distributed execution.
   Determines whether a build should run locally or on a remote agent,
   then dispatches accordingly."
  (:require [chengis.distributed.agent-registry :as agent-reg]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Remote dispatch
;; ---------------------------------------------------------------------------

(defn- dispatch-to-agent!
  "Send a build to a remote agent for execution.
   Returns {:dispatched? bool :agent-id :error}."
  [agent build-payload auth-token]
  (try
    (let [url (str (:url agent) "/builds")
          resp @(http/post url
                  {:headers (cond-> {"Content-Type" "application/json"}
                              auth-token (assoc "Authorization" (str "Bearer " auth-token)))
                   :body (json/write-str build-payload)
                   :timeout 30000})]
      (if (< (:status resp 500) 300)
        (do
          (log/info "Build dispatched to agent" (:name agent) "(" (:id agent) ")")
          (agent-reg/increment-builds! (:id agent))
          {:dispatched? true :agent-id (:id agent)})
        (do
          (log/warn "Agent" (:name agent) "rejected build:" (:status resp))
          {:dispatched? false :error (str "Agent returned HTTP " (:status resp))})))
    (catch Exception e
      (log/error "Failed to dispatch to agent" (:name agent) ":" (.getMessage e))
      {:dispatched? false :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Dispatch decision
;; ---------------------------------------------------------------------------

(defn dispatch-build!
  "Decide where to run a build and dispatch it.

   Arguments:
     system        - system map with :config
     build-payload - map with :pipeline, :build-id, :job-id, :parameters, etc.
     labels        - set of required agent labels (from pipeline or job config)

   Returns:
     {:mode :local}  — run locally (no agent found or distributed disabled)
     {:mode :remote :agent-id \"...\"}  — dispatched to agent

   If distributed is disabled or no agent matches, falls back to local."
  [system build-payload labels]
  (let [dist-config (get-in system [:config :distributed])
        enabled? (:enabled dist-config)
        fallback-local? (get-in dist-config [:dispatch :fallback-local] true)
        auth-token (get-in dist-config [:auth-token])]
    (if-not enabled?
      {:mode :local}
      ;; Try to find an available agent
      (let [agent (agent-reg/find-available-agent labels)]
        (if agent
          (let [result (dispatch-to-agent! agent build-payload auth-token)]
            (if (:dispatched? result)
              {:mode :remote :agent-id (:agent-id result)}
              (if fallback-local?
                (do
                  (log/warn "Agent dispatch failed, falling back to local:" (:error result))
                  {:mode :local :fallback-reason (:error result)})
                {:mode :failed :error (:error result)})))
          (if fallback-local?
            (do
              (log/info "No matching agent found, running build locally")
              {:mode :local :fallback-reason "No matching agent"})
            {:mode :failed :error "No matching agent available"}))))))
