(ns chengis.distributed.orphan-monitor
  "Periodic monitor for orphaned builds — builds dispatched to agents
   that have since gone offline. Detects stranded builds and re-enqueues
   them (or moves to dead-letter if retries exhausted).

   Uses chime for periodic scheduling (same pattern as agent heartbeat).
   Default check interval: 2 minutes."
  (:require [chengis.distributed.build-queue :as bq]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.circuit-breaker :as cb]
            [chengis.metrics :as metrics]
            [chime.core :as chime]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private monitor-scheduler (atom nil))

;; ---------------------------------------------------------------------------
;; Orphan detection logic (testable unit)
;; ---------------------------------------------------------------------------

(defn check-orphans!
  "Scan for builds dispatched to offline agents and recover them.
   For each offline agent:
     - Find builds in :dispatched status assigned to that agent
     - Re-enqueue them (back to :pending) if retries remain
     - Move to :dead-letter if max retries exceeded
   Returns the total count of recovered builds."
  [system]
  (let [ds (:db system)
        metrics-reg (:metrics system)]
    (when ds
      ;; First, ensure agent health is up-to-date
      (agent-reg/check-agent-health!)
      (let [offline-ids (agent-reg/get-offline-agent-ids)
            total-recovered
            (reduce (fn [total agent-id]
                      (let [recovered (bq/requeue-for-agent! ds agent-id)]
                        (when (pos? recovered)
                          (log/warn "Re-queued" recovered "orphaned builds from offline agent" agent-id))
                        (+ total recovered)))
                    0
                    offline-ids)]
        (when (pos? total-recovered)
          (log/info "Orphan recovery: re-queued" total-recovered "builds total")
          (try (metrics/record-orphan-recovery! metrics-reg total-recovered)
               (catch Exception _)))
        ;; Clean up circuit breaker state for agents that are no longer registered
        (let [registered-ids (set (map :id (agent-reg/list-agents)))]
          (when (seq registered-ids)
            (cb/cleanup-deregistered! registered-ids)))
        total-recovered))))

;; ---------------------------------------------------------------------------
;; Scheduler
;; ---------------------------------------------------------------------------

(defn start-monitor!
  "Start the periodic orphan check.
   Polls every check-interval-ms (default from config, fallback 120000ms = 2 min).
   Returns a stop function."
  [system]
  ;; Stop any existing scheduler first
  (when-let [existing @monitor-scheduler]
    (try (.close existing) (catch Exception _))
    (reset! monitor-scheduler nil)
    (log/debug "Stopped previous orphan monitor"))
  (let [interval-ms (get-in system [:config :distributed :dispatch :orphan-check-interval-ms]
                            120000)
        ;; Start after one interval (don't check immediately on startup)
        start-time (.plus (Instant/now) (Duration/ofMillis interval-ms))
        times (chime/periodic-seq start-time (Duration/ofMillis interval-ms))
        sched (chime/chime-at
                times
                (fn [_time]
                  (try
                    (check-orphans! system)
                    (catch Exception e
                      (log/error "Orphan monitor error:" (.getMessage e))))))]
    (reset! monitor-scheduler sched)
    (log/info "Orphan monitor started: checking every" interval-ms "ms")
    (fn []
      (when-let [s @monitor-scheduler]
        (.close s)
        (reset! monitor-scheduler nil)
        (log/info "Orphan monitor stopped")))))

(defn stop-monitor!
  "Stop the orphan monitor if running."
  []
  (when-let [sched @monitor-scheduler]
    (.close sched)
    (reset! monitor-scheduler nil)
    (log/info "Orphan monitor stopped")))

(defn running?*
  "Check if the orphan monitor is currently scheduled."
  []
  (some? @monitor-scheduler))
