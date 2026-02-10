(ns chengis.distributed.agent-registry
  "Agent registry for distributed builds.
   Tracks registered agents, their health (heartbeat), labels, and capacity.

   Write-through cache: In-memory atom serves as read cache for fast lookups.
   All mutations write to database first (when ds-ref is set), then update atom.
   On startup, atom is hydrated from database via `hydrate-from-db!`.

   When ds-ref is nil (tests, CLI), operates in atom-only mode (backward compatible).

   Concurrency: All state mutations use swap! with pure functions to avoid
   check-then-act race conditions."
  (:require [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; In-memory registry (read cache)
;; ---------------------------------------------------------------------------

(defonce ^:private agents (atom {}))  ;; agent-id -> agent-info

(def ^:private default-heartbeat-timeout-ms
  "Default: agents considered offline after 90s without heartbeat."
  90000)

(defonce ^:private config-ref (atom {}))

;; Database reference for write-through persistence.
;; nil = atom-only mode (tests, CLI).
(defonce ^:private ds-ref (atom nil))

(defn set-config!
  "Set the config map for the agent registry. Called at server startup.
   Used to make heartbeat timeout configurable without hardcoding."
  [cfg]
  (reset! config-ref cfg))

(defn set-db!
  "Set the database datasource for write-through persistence.
   When set, all mutations persist to the agents table.
   When nil, operates in atom-only mode (backward compatible)."
  [ds]
  (reset! ds-ref ds))

(defn hydrate-from-db!
  "Load all agents from database into the in-memory atom.
   Called once at startup to restore agent state after a master restart.
   Requires ds-ref to be set via `set-db!` first.
   Non-fatal: logs error and continues with empty registry if DB load fails."
  []
  (when-let [ds @ds-ref]
    (try
      (let [agent-store (requiring-resolve 'chengis.db.agent-store/load-all-agents)
            db-agents (agent-store ds)
            agent-map (reduce (fn [m agent]
                                (assoc m (:id agent) agent))
                              {} db-agents)]
        (reset! agents agent-map)
        (log/info "Hydrated agent registry from database:" (count db-agents) "agent(s)"))
      (catch Exception e
        (log/error "Failed to hydrate agent registry from database — starting with empty registry:"
                   (.getMessage e))))))

(defn- heartbeat-timeout-ms
  "Get the configured heartbeat timeout, falling back to the default."
  []
  (get-in @config-ref [:distributed :heartbeat-timeout-ms]
          default-heartbeat-timeout-ms))

(defn- now-instant [] (Instant/now))

(defn- ms-since-str
  "Milliseconds since a stringified instant. Returns nil if input is nil."
  [instant-str]
  (when instant-str
    (try
      (.toMillis (Duration/between (Instant/parse instant-str) (now-instant)))
      (catch Exception _ nil))))

(defn- persist-upsert!
  "Write agent record to database if ds-ref is set."
  [record]
  (when-let [ds @ds-ref]
    (try
      (let [upsert-fn (requiring-resolve 'chengis.db.agent-store/upsert-agent!)]
        (upsert-fn ds record))
      (catch Exception e
        (log/error "Failed to persist agent upsert:" (.getMessage e))))))

(defn- persist-heartbeat!
  "Write heartbeat update to database if ds-ref is set."
  [agent-id opts]
  (when-let [ds @ds-ref]
    (try
      (let [update-fn (requiring-resolve 'chengis.db.agent-store/update-agent-heartbeat!)]
        (update-fn ds agent-id opts))
      (catch Exception e
        (log/error "Failed to persist heartbeat:" (.getMessage e))))))

(defn- persist-delete!
  "Delete agent from database if ds-ref is set."
  [agent-id]
  (when-let [ds @ds-ref]
    (try
      (let [delete-fn (requiring-resolve 'chengis.db.agent-store/delete-agent!)]
        (delete-fn ds agent-id))
      (catch Exception e
        (log/error "Failed to persist agent deletion:" (.getMessage e))))))

(defn- persist-builds!
  "Write current_builds to database if ds-ref is set."
  [agent-id current-builds]
  (when-let [ds @ds-ref]
    (try
      (let [update-fn (requiring-resolve 'chengis.db.agent-store/update-agent-builds!)]
        (update-fn ds agent-id current-builds))
      (catch Exception e
        (log/error "Failed to persist build count:" (.getMessage e))))))

(defn- persist-status!
  "Write status to database if ds-ref is set."
  [agent-id status]
  (when-let [ds @ds-ref]
    (try
      (let [update-fn (requiring-resolve 'chengis.db.agent-store/update-agent-status!)]
        (update-fn ds agent-id status))
      (catch Exception e
        (log/error "Failed to persist status:" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Agent management
;; ---------------------------------------------------------------------------

(defn register-agent!
  "Register a new agent. Always generates a new agent ID.
   When :org-id is provided, the agent is restricted to that organization.
   When :org-id is nil (default), the agent is shared across all orgs.
   Persists to database (write-through) when ds-ref is set.
   Returns the agent record."
  [{:keys [name url labels max-builds system-info org-id]}]
  (let [agent-id (util/generate-id)
        now (str (now-instant))
        record (cond-> {:id agent-id
                        :name (or name (str "agent-" (subs agent-id 0 8)))
                        :url url
                        :labels (set (or labels #{}))
                        :max-builds (or max-builds 2)
                        :status :online
                        :current-builds 0
                        :system-info system-info
                        :registered-at now
                        :last-heartbeat now}
                 org-id (assoc :org-id org-id))]
    (log/info "Agent registered:" (:name record) "(" agent-id ")"
              (if org-id (str "org:" org-id) "shared"))
    ;; Write to DB first, then update atom
    (persist-upsert! record)
    (swap! agents assoc agent-id record)
    record))

(defn heartbeat!
  "Update an agent's last heartbeat timestamp atomically.
   Returns true if the agent existed, false otherwise.
   Uses swap-vals! to avoid side effects inside swap!.
   Atom-first for heartbeat (high-frequency), DB write follows."
  [agent-id & [{:keys [current-builds system-info]}]]
  (let [[old-state _new-state]
        (swap-vals! agents
                    (fn [state]
                      (if (contains? state agent-id)
                        (update state agent-id merge
                                {:last-heartbeat (str (now-instant))
                                 :status :online}
                                (when current-builds {:current-builds current-builds})
                                (when system-info {:system-info system-info}))
                        state)))
        existed? (contains? old-state agent-id)]
    ;; Persist heartbeat to DB (atom-first for performance)
    (when existed?
      (persist-heartbeat! agent-id {:current-builds current-builds
                                    :system-info system-info}))
    existed?))

(defn deregister-agent!
  "Remove an agent from the registry.
   Deletes from database first, then removes from atom."
  [agent-id]
  (persist-delete! agent-id)
  (swap! agents dissoc agent-id))

(defn get-agent
  "Look up an agent by ID."
  [agent-id]
  (get @agents agent-id))

(defn list-agents
  "List all registered agents with computed status based on heartbeat.
   When org-id is provided, returns agents assigned to that org plus shared agents (nil org-id).
   When org-id is nil, returns all agents."
  [& {:keys [org-id]}]
  (let [timeout (heartbeat-timeout-ms)
        all-agents (mapv (fn [[_id agent]]
                           (let [elapsed (ms-since-str (:last-heartbeat agent))
                                 status (if (and elapsed (> elapsed timeout))
                                          :offline
                                          (:status agent))]
                             (assoc agent :status status
                                          :heartbeat-age-ms elapsed)))
                         @agents)]
    (if org-id
      ;; Filter: agents for this org + shared agents (nil org-id)
      (filterv (fn [agent]
                 (or (nil? (:org-id agent))
                     (= org-id (:org-id agent))))
               all-agents)
      all-agents)))

(defn- agent-available?
  "Check if an agent can accept a new build.
   Computes availability from heartbeat elapsed time for freshness."
  [agent]
  (let [elapsed (ms-since-str (:last-heartbeat agent))
        timeout (heartbeat-timeout-ms)]
    (and (= :online (:status agent))
         (< (:current-builds agent 0) (:max-builds agent 2))
         ;; Verify heartbeat is recent (guards against stale :online status)
         (or (nil? elapsed) (< elapsed timeout)))))

;; ---------------------------------------------------------------------------
;; Agent selection
;; ---------------------------------------------------------------------------

(defn- score-agent
  "Score an agent for resource-aware scheduling.
   Higher score = more preferred.
   Formula: (1 - load_ratio) * 0.6 + cpu_score * 0.2 + memory_score * 0.2"
  [agent]
  (let [load-ratio (/ (double (:current-builds agent 0))
                      (double (max 1 (:max-builds agent 2))))
        si (:system-info agent)
        cpu-score (if si
                    (min 1.0 (/ (double (:cpu-count si 1)) 16.0))
                    0.5)
        mem-score (if si
                    (min 1.0 (/ (double (:memory-gb si 1)) 32.0))
                    0.5)]
    (+ (* (- 1.0 load-ratio) 0.6)
       (* cpu-score 0.2)
       (* mem-score 0.2))))

(defn- meets-resource-requirements?
  "Check if an agent meets the specified resource requirements."
  [agent resources]
  (if resources
    (let [si (:system-info agent)]
      (and (or (nil? (:cpu resources))
               (and si (>= (:cpu-count si 0) (:cpu resources))))
           (or (nil? (:memory resources))
               (and si (>= (:memory-gb si 0) (:memory resources))))))
    true))

(defn find-available-agent
  "Find an available agent matching the given labels, org context, and resource requirements.
   When org-id is provided, considers agents assigned to that org plus shared agents (nil org-id).
   When org-id is nil, considers all agents (backward compatible).
   When resources is provided and resource-aware-scheduling feature flag is enabled,
   filters by CPU/memory and ranks by weighted score.
   Returns the agent record or nil if none available.
   Selection strategy: resource-aware scoring (if enabled) or least loaded."
  [required-labels & {:keys [org-id resources]}]
  (let [resource-aware? (get-in @config-ref [:feature-flags :resource-aware-scheduling])
        candidates (->> (vals @agents)
                        (filter agent-available?)
                        ;; Filter by org context when provided
                        (filter (fn [agent]
                                  (if org-id
                                    (or (nil? (:org-id agent))
                                        (= org-id (:org-id agent)))
                                    true)))
                        (filter (fn [agent]
                                  (if (seq required-labels)
                                    (every? (:labels agent) required-labels)
                                    true)))
                        ;; Filter by resource requirements when resource-aware scheduling is enabled
                        (filter (fn [agent]
                                  (if (and resource-aware? resources)
                                    (meets-resource-requirements? agent resources)
                                    true))))]
    (if resource-aware?
      ;; Score-based selection
      (first (sort-by (fn [a] (- (score-agent a))) candidates))
      ;; Original: least-loaded selection
      (first (sort-by :current-builds candidates)))))

(defn increment-builds!
  "Increment the current build count for an agent.
   Updates atom, then persists to database."
  [agent-id]
  (let [new-state (swap! agents update-in [agent-id :current-builds] (fnil inc 0))
        new-count (get-in new-state [agent-id :current-builds])]
    (when new-count
      (persist-builds! agent-id new-count))))

(defn decrement-builds!
  "Decrement the current build count for an agent.
   Updates atom, then persists to database."
  [agent-id]
  (let [new-state (swap! agents update-in [agent-id :current-builds] (fn [n] (max 0 (dec (or n 0)))))
        new-count (get-in new-state [agent-id :current-builds])]
    (when new-count
      (persist-builds! agent-id new-count))))

;; ---------------------------------------------------------------------------
;; Health monitoring
;; ---------------------------------------------------------------------------

(defn check-agent-health!
  "Update status of all agents based on heartbeat timeout atomically.
   Persists offline status changes to database.
   Returns count of agents that went offline."
  []
  (let [timeout (heartbeat-timeout-ms)
        [old-state new-state]
        (swap-vals! agents
                    (fn [state]
                      (reduce-kv
                        (fn [acc agent-id agent]
                          (let [elapsed (ms-since-str (:last-heartbeat agent))]
                            (if (and elapsed
                                     (> elapsed timeout)
                                     (not= :offline (:status agent)))
                              (assoc-in acc [agent-id :status] :offline)
                              acc)))
                        state
                        state)))
        went-offline-ids (keep (fn [[id agent]]
                                 (when (and (= :offline (:status agent))
                                            (not= :offline (:status (get old-state id))))
                                   id))
                               new-state)
        went-offline (count went-offline-ids)]
    ;; Persist offline status changes to DB
    (doseq [agent-id went-offline-ids]
      (persist-status! agent-id :offline))
    (when (pos? went-offline)
      (log/warn went-offline "agent(s) went offline"))
    went-offline))

;; ---------------------------------------------------------------------------
;; Draining & offline queries (Phase 3)
;; ---------------------------------------------------------------------------

(defn get-offline-agent-ids
  "Return IDs of all agents currently in :offline status (heartbeat timed out).
   Used by the orphan monitor to find builds that may be stranded.
   When org-id is provided, returns only offline agents visible to that org."
  [& {:keys [org-id]}]
  (->> (list-agents :org-id org-id)
       (filter #(= :offline (:status %)))
       (mapv :id)))

(defn set-agent-draining!
  "Mark an agent as :draining — it won't receive new builds but finishes
   current ones. Returns true if agent existed.
   Uses swap-vals! to avoid side effects inside swap!.
   Persists status change to database."
  [agent-id]
  (let [[old-state _new-state]
        (swap-vals! agents
                    (fn [state]
                      (if (contains? state agent-id)
                        (assoc-in state [agent-id :status] :draining)
                        state)))
        existed? (contains? old-state agent-id)]
    (when existed?
      (persist-status! agent-id :draining))
    existed?))

;; ---------------------------------------------------------------------------
;; Summary & reset
;; ---------------------------------------------------------------------------

(defn registry-summary
  "Return a summary of the agent registry.
   When org-id is provided, summarizes agents visible to that org."
  [& {:keys [org-id]}]
  (let [all-agents (list-agents :org-id org-id)]
    {:total (count all-agents)
     :online (count (filter #(= :online (:status %)) all-agents))
     :offline (count (filter #(= :offline (:status %)) all-agents))
     :total-capacity (reduce + 0 (map :max-builds all-agents))
     :total-active (reduce + 0 (map :current-builds all-agents))}))

(defn reset-registry!
  "Clear the agent registry. For testing."
  []
  (reset! agents {}))
