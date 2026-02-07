(ns chengis.distributed.agent-registry
  "Agent registry for distributed builds.
   Tracks registered agents, their health (heartbeat), labels, and capacity.
   Backed by an in-memory atom with optional DB persistence."
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

(defn- ms-since [instant]
  (.toMillis (Duration/between instant (now-instant))))

;; ---------------------------------------------------------------------------
;; Agent management
;; ---------------------------------------------------------------------------

(defn register-agent!
  "Register a new agent or update an existing one.
   Returns the agent record."
  [{:keys [name url labels max-builds system-info] :as agent-info}]
  (let [agent-id (or (:id agent-info)
                     (util/generate-id))
        record (merge agent-info
                      {:id agent-id
                       :name (or name (str "agent-" (subs agent-id 0 8)))
                       :url url
                       :labels (set (or labels #{}))
                       :max-builds (or max-builds 2)
                       :status :online
                       :current-builds 0
                       :system-info system-info
                       :registered-at (str (now-instant))
                       :last-heartbeat (now-instant)})]
    (log/info "Agent registered:" (:name record) "(" agent-id ")")
    (swap! agents assoc agent-id record)
    record))

(defn heartbeat!
  "Update an agent's last heartbeat timestamp.
   Returns true if the agent exists, false otherwise."
  [agent-id & [{:keys [current-builds system-info]}]]
  (if (contains? @agents agent-id)
    (do
      (swap! agents update agent-id merge
             {:last-heartbeat (now-instant)
              :status :online}
             (when current-builds {:current-builds current-builds})
             (when system-info {:system-info system-info}))
      true)
    false))

(defn deregister-agent!
  "Remove an agent from the registry."
  [agent-id]
  (swap! agents dissoc agent-id))

(defn get-agent
  "Look up an agent by ID."
  [agent-id]
  (get @agents agent-id))

(defn list-agents
  "List all registered agents with current status."
  []
  (mapv (fn [[_id agent]]
          (let [last-hb (:last-heartbeat agent)
                elapsed (when last-hb (ms-since last-hb))
                status (if (and elapsed (> elapsed heartbeat-timeout-ms))
                         :offline
                         (:status agent))]
            (assoc agent :status status
                         :heartbeat-age-ms elapsed)))
        @agents))

(defn- agent-available?
  "Check if an agent can accept a new build."
  [agent]
  (and (= :online (:status agent))
       (< (:current-builds agent 0) (:max-builds agent 2))
       (let [elapsed (when (:last-heartbeat agent)
                       (ms-since (:last-heartbeat agent)))]
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
  "Update status of all agents based on heartbeat timeout.
   Returns count of agents that went offline."
  []
  (let [offline-count (atom 0)]
    (doseq [[agent-id agent] @agents]
      (when-let [last-hb (:last-heartbeat agent)]
        (when (> (ms-since last-hb) heartbeat-timeout-ms)
          (when (not= :offline (:status agent))
            (log/warn "Agent" (:name agent) "(" agent-id ") went offline")
            (swap! agents assoc-in [agent-id :status] :offline)
            (swap! offline-count inc)))))
    @offline-count))

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
