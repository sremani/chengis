(ns chengis.distributed.agent-registry
  "Agent registry for distributed builds.
   Tracks registered agents, their health (heartbeat), labels, and capacity.
   Backed by an in-memory atom with optional DB persistence.

   Concurrency: All state mutations use swap! with pure functions to avoid
   check-then-act race conditions."
  (:require [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; In-memory registry
;; ---------------------------------------------------------------------------

(defonce ^:private agents (atom {}))  ;; agent-id -> agent-info

(def ^:private heartbeat-timeout-ms
  "Agents are considered offline after this many ms without heartbeat."
  90000)

(defn- now-instant [] (Instant/now))

(defn- ms-since-str
  "Milliseconds since a stringified instant. Returns nil if input is nil."
  [instant-str]
  (when instant-str
    (try
      (.toMillis (Duration/between (Instant/parse instant-str) (now-instant)))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Agent management
;; ---------------------------------------------------------------------------

(defn register-agent!
  "Register a new agent. Always generates a new agent ID.
   Returns the agent record."
  [{:keys [name url labels max-builds system-info]}]
  (let [agent-id (util/generate-id)
        now (str (now-instant))
        record {:id agent-id
                :name (or name (str "agent-" (subs agent-id 0 8)))
                :url url
                :labels (set (or labels #{}))
                :max-builds (or max-builds 2)
                :status :online
                :current-builds 0
                :system-info system-info
                :registered-at now
                :last-heartbeat now}]
    (log/info "Agent registered:" (:name record) "(" agent-id ")")
    (swap! agents assoc agent-id record)
    record))

(defn heartbeat!
  "Update an agent's last heartbeat timestamp atomically.
   Returns true if the agent existed, false otherwise."
  [agent-id & [{:keys [current-builds system-info]}]]
  (let [existed? (atom false)]
    (swap! agents
           (fn [state]
             (if (contains? state agent-id)
               (do (reset! existed? true)
                   (update state agent-id merge
                           {:last-heartbeat (str (now-instant))
                            :status :online}
                           (when current-builds {:current-builds current-builds})
                           (when system-info {:system-info system-info})))
               state)))
    @existed?))

(defn deregister-agent!
  "Remove an agent from the registry."
  [agent-id]
  (swap! agents dissoc agent-id))

(defn get-agent
  "Look up an agent by ID."
  [agent-id]
  (get @agents agent-id))

(defn list-agents
  "List all registered agents with computed status based on heartbeat."
  []
  (mapv (fn [[_id agent]]
          (let [elapsed (ms-since-str (:last-heartbeat agent))
                status (if (and elapsed (> elapsed heartbeat-timeout-ms))
                         :offline
                         (:status agent))]
            (assoc agent :status status
                         :heartbeat-age-ms elapsed)))
        @agents))

(defn- agent-available?
  "Check if an agent can accept a new build.
   Computes availability from heartbeat elapsed time for freshness."
  [agent]
  (let [elapsed (ms-since-str (:last-heartbeat agent))]
    (and (= :online (:status agent))
         (< (:current-builds agent 0) (:max-builds agent 2))
         ;; Verify heartbeat is recent (guards against stale :online status)
         (or (nil? elapsed) (< elapsed heartbeat-timeout-ms)))))

;; ---------------------------------------------------------------------------
;; Agent selection
;; ---------------------------------------------------------------------------

(defn find-available-agent
  "Find an available agent matching the given labels.
   Returns the agent record or nil if none available.
   Selection strategy: least loaded agent matching all labels."
  [required-labels]
  (let [candidates (->> (vals @agents)
                        (filter agent-available?)
                        (filter (fn [agent]
                                  (if (seq required-labels)
                                    (every? (:labels agent) required-labels)
                                    true)))
                        (sort-by :current-builds))]
    (first candidates)))

(defn increment-builds!
  "Increment the current build count for an agent."
  [agent-id]
  (swap! agents update-in [agent-id :current-builds] (fnil inc 0)))

(defn decrement-builds!
  "Decrement the current build count for an agent."
  [agent-id]
  (swap! agents update-in [agent-id :current-builds] (fn [n] (max 0 (dec (or n 0))))))

;; ---------------------------------------------------------------------------
;; Health monitoring
;; ---------------------------------------------------------------------------

(defn check-agent-health!
  "Update status of all agents based on heartbeat timeout atomically.
   Returns count of agents that went offline."
  []
  (let [[old-state new-state]
        (swap-vals! agents
                    (fn [state]
                      (reduce-kv
                        (fn [acc agent-id agent]
                          (let [elapsed (ms-since-str (:last-heartbeat agent))]
                            (if (and elapsed
                                     (> elapsed heartbeat-timeout-ms)
                                     (not= :offline (:status agent)))
                              (assoc-in acc [agent-id :status] :offline)
                              acc)))
                        state
                        state)))
        went-offline (count (filter (fn [[id agent]]
                                      (and (= :offline (:status agent))
                                           (not= :offline (:status (get old-state id)))))
                                    new-state))]
    (when (pos? went-offline)
      (log/warn went-offline "agent(s) went offline"))
    went-offline))

;; ---------------------------------------------------------------------------
;; Summary & reset
;; ---------------------------------------------------------------------------

(defn registry-summary
  "Return a summary of the agent registry."
  []
  (let [all-agents (list-agents)]
    {:total (count all-agents)
     :online (count (filter #(= :online (:status %)) all-agents))
     :offline (count (filter #(= :offline (:status %)) all-agents))
     :total-capacity (reduce + 0 (map :max-builds all-agents))
     :total-active (reduce + 0 (map :current-builds all-agents))}))

(defn reset-registry!
  "Clear the agent registry. For testing."
  []
  (reset! agents {}))
