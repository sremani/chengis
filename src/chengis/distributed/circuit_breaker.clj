(ns chengis.distributed.circuit-breaker
  "Per-agent circuit breaker for distributed dispatch.
   Tracks consecutive dispatch failures per agent and temporarily blocks
   dispatch to unhealthy agents.

   States:
     :closed    — Normal operation, dispatches allowed
     :open      — Agent is failing, dispatches blocked
     :half-open — Enough time has passed since open, allow one probe

   Thread-safe: all state mutations via swap! on atom."
  (:require [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private breakers (atom {}))
;; agent-id -> {:state :closed/:open/:half-open
;;              :failures 0
;;              :last-failure nil  ;; Instant
;;              :opened-at nil}    ;; Instant when circuit opened

(def ^:private default-breaker
  {:state :closed
   :failures 0
   :last-failure nil
   :opened-at nil})

;; ---------------------------------------------------------------------------
;; Core operations
;; ---------------------------------------------------------------------------

(defn record-success!
  "Record a successful dispatch to an agent.
   Resets failure count and transitions to :closed."
  [agent-id]
  (swap! breakers assoc agent-id default-breaker)
  :closed)

(defn record-failure!
  "Record a dispatch failure for an agent.
   If failures >= threshold, transitions to :open.
   Returns the new state (:closed or :open)."
  [agent-id threshold]
  (let [now (Instant/now)
        [_ new-state]
        (swap-vals! breakers
                    (fn [state]
                      (let [current (get state agent-id default-breaker)
                            new-failures (inc (:failures current))
                            new-cb (if (>= new-failures threshold)
                                     {:state :open
                                      :failures new-failures
                                      :last-failure now
                                      :opened-at now}
                                     {:state :closed
                                      :failures new-failures
                                      :last-failure now
                                      :opened-at nil})]
                        (assoc state agent-id new-cb))))]
    (let [cb-state (:state (get new-state agent-id))]
      (when (= :open cb-state)
        (log/warn "Circuit breaker OPEN for agent" agent-id
                  "after" (:failures (get new-state agent-id)) "failures"))
      cb-state)))

(defn allow-request?
  "Check if a dispatch to this agent is allowed.
   Returns true if:
     - Circuit is :closed (normal operation)
     - Circuit is :half-open (probe in progress)
     - Circuit is :open but enough time has passed (atomically transitions to :half-open)
   Returns false if circuit is :open and cooldown hasn't elapsed.
   The half-open transition is atomic to prevent multiple concurrent probes."
  [agent-id reset-ms]
  (let [^Instant now (Instant/now)
        [old-state new-state]
        (swap-vals! breakers
                    (fn [state]
                      (let [current (get state agent-id default-breaker)]
                        (if (and (= :open (:state current))
                                 (:opened-at current)
                                 (>= (.toMillis (Duration/between ^Instant (:opened-at current) now))
                                     (long reset-ms)))
                          ;; Atomically transition to half-open
                          (assoc-in state [agent-id :state] :half-open)
                          state))))
        old-cb (get old-state agent-id default-breaker)
        new-cb (get new-state agent-id default-breaker)]
    ;; Log the transition if it happened
    (when (and (= :open (:state old-cb)) (= :half-open (:state new-cb)))
      (log/info "Circuit breaker HALF-OPEN for agent" agent-id "(probing)"))
    (case (:state new-cb)
      :closed true
      :half-open true
      :open false
      ;; Unknown state — allow by default
      true)))

;; ---------------------------------------------------------------------------
;; Query
;; ---------------------------------------------------------------------------

(defn get-state
  "Get the circuit breaker state for an agent.
   Returns {:state :closed/:open/:half-open :failures N :last-failure Instant}
   or the default closed state if agent has no breaker record."
  [agent-id]
  (get @breakers agent-id default-breaker))

(defn get-all-states
  "Get all circuit breaker states. Returns map of agent-id -> state."
  []
  @breakers)

(defn count-open
  "Count the number of agents with open circuit breakers."
  []
  (count (filter #(= :open (:state (val %))) @breakers)))

;; ---------------------------------------------------------------------------
;; Admin / testing
;; ---------------------------------------------------------------------------

(defn cleanup-deregistered!
  "Remove circuit breaker state for agents that are no longer registered.
   Accepts a set of currently registered agent IDs. Any breaker entry whose
   agent-id is NOT in the set is removed. Returns the number of entries removed."
  [registered-agent-ids]
  (let [registered-set (set registered-agent-ids)
        before (count @breakers)]
    (swap! breakers
      (fn [state]
        (into {} (filter (fn [[agent-id _]] (contains? registered-set agent-id)) state))))
    (let [removed (- before (count @breakers))]
      (when (pos? removed)
        (log/info "Circuit breaker cleanup: removed" removed "entries for deregistered agents"))
      removed)))

(defn reset-agent!
  "Reset the circuit breaker for a specific agent to :closed."
  [agent-id]
  (swap! breakers assoc agent-id default-breaker)
  (log/info "Circuit breaker RESET for agent" agent-id))

(defn reset-all!
  "Clear all circuit breaker state. For testing."
  []
  (clojure.core/reset! breakers {}))
