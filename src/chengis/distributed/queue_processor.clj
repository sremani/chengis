(ns chengis.distributed.queue-processor
  "Background queue processor for distributed build dispatch.
   Dequeues pending builds, selects agents through the circuit breaker,
   and dispatches builds. Handles retries, local fallback, and metrics.

   The processor runs as a daemon thread polling the queue at a
   configurable interval (default 500ms)."
  (:require [chengis.distributed.build-queue :as bq]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.metrics :as metrics]
            [clojure.data.json :as json]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private running? (atom false))
(defonce ^:private processor-thread (atom nil))

;; ---------------------------------------------------------------------------
;; Dispatch to agent (extracted from dispatcher.clj for queue use)
;; ---------------------------------------------------------------------------

(defn- dispatch-to-agent!
  "Send a build to a remote agent. Returns {:dispatched? bool :error ...}."
  [agent build-payload auth-token]
  (try
    (let [url (str (:url agent) "/builds")
          resp @(http/post url
                  {:headers (cond-> {"Content-Type" "application/json"}
                              auth-token (assoc "Authorization" (str "Bearer " auth-token)))
                   :body (if (string? build-payload)
                           build-payload
                           (json/write-str build-payload))
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

(defn- find-agent-with-circuit-breaker
  "Find an available agent, filtering out agents with open circuit breakers.
   Uses the agent registry's list and filters by circuit breaker state.
   When org-id is provided, only considers agents for that org plus shared agents."
  [labels cb-reset-ms & {:keys [org-id]}]
  (let [;; Get all available agents that match labels (pre-filtered by org if provided)
        candidates (->> (agent-reg/list-agents :org-id org-id)
                        (filter #(= :online (:status %)))
                        (filter #(< (:current-builds % 0) (:max-builds % 2)))
                        (filter (fn [agent]
                                  (if (seq labels)
                                    (every? (:labels agent) labels)
                                    true)))
                        ;; Filter by circuit breaker
                        (filter #(cb/allow-request? (:id %) cb-reset-ms))
                        ;; Sort by least loaded
                        (sort-by :current-builds))]
    (first candidates)))

;; ---------------------------------------------------------------------------
;; Process one queue item (testable unit)
;; ---------------------------------------------------------------------------

(defn process-one!
  "Dequeue and process a single build from the queue.
   Returns :processed, nil (queue empty), :no-agent, or :failed."
  [system]
  (let [ds (:db system)
        config (:config system)
        dist-config (get config :distributed)
        dispatch-config (get dist-config :dispatch)
        auth-token (:auth-token dist-config)
        cb-threshold (get dispatch-config :circuit-breaker-threshold 5)
        cb-reset-ms (get dispatch-config :circuit-breaker-reset-ms 60000)
        backoff-ms (get dispatch-config :retry-backoff-ms 1000)
        fallback-local? (get dispatch-config :fallback-local true)]
    (when-let [item (bq/dequeue-next! ds)]
      (let [labels (set (or (:labels item) []))
            org-id (get-in item [:payload :org-id])
            agent (find-agent-with-circuit-breaker labels cb-reset-ms :org-id org-id)]
        (if agent
          ;; Dispatch to agent
          (let [result (dispatch-to-agent! agent (:payload item) auth-token)]
            (if (:dispatched? result)
              (do
                (bq/mark-dispatched! ds (:id item) (:agent-id result))
                (cb/record-success! (:agent-id result))
                (try (metrics/record-dispatch! (:metrics system) :success)
                     (catch Exception _))
                :processed)
              (do
                (cb/record-failure! (:id agent) cb-threshold)
                (bq/mark-failed! ds (:id item) (:error result)
                                 {:backoff-ms backoff-ms})
                (try (metrics/record-dispatch! (:metrics system) :failure)
                     (catch Exception _))
                :failed)))
          ;; No agent available
          (if fallback-local?
            (do
              ;; Put back as pending for retry (short delay)
              (bq/mark-failed! ds (:id item) "No matching agent available"
                               {:backoff-ms (min backoff-ms 2000)})
              (try (metrics/record-dispatch! (:metrics system) :no-agent)
                   (catch Exception _))
              :no-agent)
            (do
              (bq/mark-failed! ds (:id item) "No matching agent and fallback disabled"
                               {:backoff-ms backoff-ms})
              :no-agent)))))))

;; ---------------------------------------------------------------------------
;; Background processor loop
;; ---------------------------------------------------------------------------

(defn stop-processor!
  "Stop the background queue processor."
  []
  (reset! running? false)
  (when-let [t @processor-thread]
    (.interrupt t)
    (reset! processor-thread nil))
  (log/info "Queue processor stop requested"))

(defn running?*
  "Check if the processor is currently running."
  []
  @running?)

(defn start-processor!
  "Start the background queue processor thread.
   Polls the queue every poll-interval-ms (default 500ms).
   If already running, stops the existing processor first.
   Returns a stop function."
  [system & [{:keys [poll-interval-ms] :or {poll-interval-ms 500}}]]
  (when @running?
    (log/warn "Queue processor already running — stopping previous instance")
    (stop-processor!))
  (reset! running? true)
  (let [thread (Thread.
                 (fn []
                   (log/info "Queue processor started (poll interval:" poll-interval-ms "ms)")
                   (while @running?
                     (try
                       (let [result (process-one! system)]
                         (when (or (nil? result) (= :empty result))
                           ;; Queue empty — sleep before polling again
                           (Thread/sleep poll-interval-ms)))
                       (catch InterruptedException _
                         (log/debug "Queue processor interrupted"))
                       (catch Exception e
                         (log/error "Queue processor error:" (.getMessage e))
                         (Thread/sleep poll-interval-ms))))
                   (log/info "Queue processor stopped"))
                 "chengis-queue-processor")]
    (.setDaemon thread true)
    (.start thread)
    (reset! processor-thread thread)
    (fn []
      (stop-processor!))))
