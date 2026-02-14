(ns chengis.distributed.queue-processor
  "Background queue processor for distributed build dispatch.
   Dequeues pending builds, selects agents through the circuit breaker,
   and dispatches builds. Handles retries, local fallback, and metrics.

   The processor runs as a daemon thread polling the queue at a
   configurable interval (default 500ms)."
  (:require [chengis.distributed.build-queue :as bq]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.agent-http :as agent-http]
            [chengis.metrics :as metrics]
            [clojure.data.json :as json]
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
  "Send a build to a remote agent. Uses agent-http connection pooling for TCP reuse.
   Returns {:dispatched? bool :error ...}."
  [agent build-payload auth-token]
  (try
    (let [body (if (string? build-payload)
                 build-payload
                 (json/write-str build-payload))
          headers (cond-> {}
                    auth-token (assoc "Authorization" (str "Bearer " auth-token)))
          resp @(agent-http/post! (:id agent) (:url agent) "/builds" body headers)
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

(defn- exponential-backoff
  "Calculate exponential backoff with jitter.
   base-ms * 2^(attempt-1), capped at max-ms, with ±25% random jitter."
  ^long [^long base-ms ^long attempt ^long max-ms]
  (let [exp-ms (* base-ms (bit-shift-left 1 (min (dec (max attempt 1)) 10)))
        capped (min exp-ms max-ms)
        jitter-range (/ capped 4)
        jitter (- (long (* (Math/random) (* 2.0 (double jitter-range)))) (long jitter-range))]
    (max base-ms (+ capped jitter))))

(defn process-one!
  "Dequeue and process a single build from the queue.
   Returns :processed, nil (queue empty), :no-agent, or :failed.
   Uses exponential backoff for retry delays."
  [system]
  (let [ds (:db system)
        config (:config system)
        dist-config (get config :distributed)
        dispatch-config (get dist-config :dispatch)
        auth-token (:auth-token dist-config)
        cb-threshold (get dispatch-config :circuit-breaker-threshold 5)
        cb-reset-ms (get dispatch-config :circuit-breaker-reset-ms 60000)
        base-backoff-ms (get dispatch-config :retry-backoff-ms 1000)
        max-backoff-ms (get dispatch-config :max-retry-backoff-ms 30000)
        fallback-local? (get dispatch-config :fallback-local true)]
    (when-let [item (bq/dequeue-next! ds)]
      (let [labels (set (or (:labels item) []))
            org-id (get-in item [:payload :org-id])
            attempt (inc (or (:retry-count item) 0))
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
                (let [backoff (exponential-backoff base-backoff-ms attempt max-backoff-ms)]
                  (bq/mark-failed! ds (:id item) (:error result)
                                   {:backoff-ms backoff}))
                (try (metrics/record-dispatch! (:metrics system) :failure)
                     (catch Exception _))
                :failed)))
          ;; No agent available
          (let [backoff (exponential-backoff base-backoff-ms attempt max-backoff-ms)]
            (if fallback-local?
              (do
                (bq/mark-failed! ds (:id item) "No matching agent available"
                                 {:backoff-ms (min backoff 2000)})
                (try (metrics/record-dispatch! (:metrics system) :no-agent)
                     (catch Exception _))
                :no-agent)
              (do
                (bq/mark-failed! ds (:id item) "No matching agent and fallback disabled"
                                 {:backoff-ms backoff})
                :no-agent))))))))

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
   Uses adaptive polling: backs off when queue is empty (up to max-idle-poll-ms),
   resets to base interval on successful dispatch.
   If already running, stops the existing processor first.
   Returns a stop function."
  [system & [{:keys [poll-interval-ms max-idle-poll-ms]
              :or {poll-interval-ms 500 max-idle-poll-ms 5000}}]]
  (when @running?
    (log/warn "Queue processor already running — stopping previous instance")
    (stop-processor!))
  (reset! running? true)
  (let [^Thread thread (Thread.
                 ^Runnable (fn []
                   (log/info "Queue processor started (poll interval:" poll-interval-ms
                             "ms, max idle:" max-idle-poll-ms "ms)")
                   (let [consecutive-empty (atom 0)]
                     (while @running?
                       (try
                         (let [result (process-one! system)]
                           (if (= :processed result)
                             ;; Success — reset to base polling interval
                             (reset! consecutive-empty 0)
                             (do
                               ;; Empty, no-agent, or failed — back off adaptively
                               (swap! consecutive-empty inc)
                               (let [idle-backoff (min max-idle-poll-ms
                                                       (* poll-interval-ms
                                                          (bit-shift-left 1
                                                            (min @consecutive-empty 4))))]
                                 (Thread/sleep idle-backoff)))))
                         (catch InterruptedException _
                           (log/debug "Queue processor interrupted"))
                         (catch Exception e
                           (log/error "Queue processor error:" (.getMessage e))
                           (swap! consecutive-empty inc)
                           (Thread/sleep (min max-idle-poll-ms
                                              (* poll-interval-ms
                                                 (bit-shift-left 1
                                                   (min @consecutive-empty 4)))))))))
                   (log/info "Queue processor stopped"))
                 "chengis-queue-processor")]
    (.setDaemon thread true)
    (.start thread)
    (reset! processor-thread thread)
    (fn []
      (stop-processor!))))
