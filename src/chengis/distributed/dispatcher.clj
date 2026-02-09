(ns chengis.distributed.dispatcher
  "Build dispatch for distributed execution.
   Determines whether a build should run locally, be queued for async dispatch,
   or dispatched directly to a remote agent.

   Phase 3: When queue-enabled is true, builds are enqueued to the persistent
   build queue instead of dispatched directly. The queue processor handles
   actual dispatch with circuit breaker protection and retry logic."
  (:require [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.build-queue :as bq]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Remote dispatch (direct mode — used when queue is disabled)
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
                   :timeout 30000})
          status (or (:status resp) 500)]
      (if (< status 300)
        (do
          (log/info "Build dispatched to agent" (:name agent) "(" (:id agent) ")")
          (agent-reg/increment-builds! (:id agent))
          {:dispatched? true :agent-id (:id agent)})
        (do
          (log/warn "Agent" (:name agent) "rejected build: HTTP" status)
          {:dispatched? false :error (str "Agent returned HTTP " status)})))
    (catch Exception e
      (log/error "Failed to dispatch to agent" (:name agent) ":" (.getMessage e))
      {:dispatched? false :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Dispatch decision
;; ---------------------------------------------------------------------------

(defn dispatch-build!
  "Decide where to run a build and dispatch it.

   Arguments:
     system        - system map with :config and :db
     build-payload - map with :pipeline, :build-id, :job-id, :parameters, :org-id, etc.
     labels        - set of required agent labels (from pipeline or job config)

   Returns:
     {:mode :local}  — run locally (no agent found or distributed disabled)
     {:mode :remote :agent-id \"...\"}  — dispatched to agent (direct mode)
     {:mode :queued :queue-id \"...\"}  — enqueued for async dispatch (queue mode)

   When queue-enabled is true, builds are enqueued rather than dispatched directly.
   The queue processor handles actual dispatch with retry and circuit breaker.
   If distributed is disabled or no agent matches, falls back to local.
   Org-id from build-payload is used to select org-scoped or shared agents."
  [system build-payload labels]
  (let [dist-config (get-in system [:config :distributed])
        enabled? (:enabled dist-config)
        queue-enabled? (get-in dist-config [:dispatch :queue-enabled] false)
        fallback-local? (get-in dist-config [:dispatch :fallback-local] true)
        auth-token (get-in dist-config [:auth-token])
        max-retries (get-in dist-config [:dispatch :max-retries] 3)
        org-id (:org-id build-payload)]
    (when-not org-id
      (log/warn "Build dispatched without org-id scoping"
                {:build-id (:build-id build-payload)}))
    (cond
      ;; Distributed disabled — local execution
      (not enabled?)
      {:mode :local}

      ;; Queue mode — enqueue for async dispatch
      (and queue-enabled? (:db system))
      (let [build-id (:build-id build-payload)
            job-id (:job-id build-payload)
            item (bq/enqueue! (:db system) build-id job-id
                              build-payload labels
                              {:max-retries max-retries})]
        {:mode :queued :queue-id (:id item)})

      ;; Direct dispatch mode (legacy — no queue)
      :else
      (let [agent (agent-reg/find-available-agent labels :org-id org-id)]
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
