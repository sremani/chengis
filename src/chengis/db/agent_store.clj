(ns chengis.db.agent-store
  "Database persistence layer for the distributed agent registry.
   Provides CRUD operations for agent records, enabling write-through
   caching so agent state survives master restarts.

   All functions accept a next.jdbc datasource as the first argument.
   JSON serialization is used for :labels (set→array) and :system-info (map→object).
   Status is stored as a string and converted to a keyword on read."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]))

;; ---------------------------------------------------------------------------
;; Serialization helpers
;; ---------------------------------------------------------------------------

(defn- labels->json
  "Serialize a set of labels to a JSON array string."
  [labels]
  (when (seq labels)
    (json/write-str (vec labels))))

(defn- json->labels
  "Deserialize a JSON array string to a set of labels."
  [s]
  (if (and s (not= s ""))
    (set (json/read-str s))
    #{}))

(defn- system-info->json
  "Serialize a system-info map to a JSON object string."
  [info]
  (when info
    (json/write-str info)))

(defn- json->system-info
  "Deserialize a JSON object string to a map with keyword keys."
  [s]
  (when (and s (not= s ""))
    (json/read-str s :key-fn keyword)))

(defn- row->agent
  "Convert a database row to an agent record with proper types:
   - labels: JSON array → set
   - system-info: JSON object → map
   - status: string → keyword"
  [row]
  (when row
    (-> row
        (update :labels json->labels)
        (update :system-info json->system-info)
        (update :status keyword))))

;; ---------------------------------------------------------------------------
;; CRUD operations
;; ---------------------------------------------------------------------------

(defn upsert-agent!
  "Insert or update an agent record.
   On conflict (same id), updates name, url, labels, status, max-builds,
   system-info, last-heartbeat, current-builds, and org-id.
   Returns the agent map."
  [ds {:keys [id name url labels status max-builds system-info
              last-heartbeat registered-at current-builds org-id]}]
  (let [labels-json (labels->json labels)
        sysinfo-json (system-info->json system-info)
        status-str (clojure.core/name (or status :online))]
    (jdbc/execute-one! ds
      (sql/format
        {:insert-into :agents
         :values [{:id id
                   :name name
                   :url url
                   :labels labels-json
                   :status status-str
                   :max-builds (or max-builds 2)
                   :system-info sysinfo-json
                   :last-heartbeat last-heartbeat
                   :registered-at registered-at
                   :current-builds (or current-builds 0)
                   :org-id org-id}]
         :on-conflict [:id]
         :do-update-set [:name :url :labels :status :max-builds
                         :system-info :last-heartbeat :current-builds :org-id]}))
    {:id id :name name :url url :labels labels :status (keyword status-str)
     :max-builds (or max-builds 2) :system-info system-info
     :last-heartbeat last-heartbeat :registered-at registered-at
     :current-builds (or current-builds 0) :org-id org-id}))

(defn update-agent-heartbeat!
  "Update an agent's heartbeat timestamp and optional fields.
   High-frequency operation — only updates specific columns."
  [ds agent-id {:keys [current-builds system-info]}]
  (let [now (str (java.time.Instant/now))
        set-clause (cond-> {:last-heartbeat now
                            :status "online"}
                     current-builds (assoc :current-builds current-builds)
                     system-info (assoc :system-info (system-info->json system-info)))]
    (jdbc/execute-one! ds
      (sql/format {:update :agents
                   :set set-clause
                   :where [:= :id agent-id]}))))

(defn update-agent-status!
  "Update the status column for an agent."
  [ds agent-id status]
  (jdbc/execute-one! ds
    (sql/format {:update :agents
                 :set {:status (clojure.core/name status)}
                 :where [:= :id agent-id]})))

(defn update-agent-builds!
  "Update the current_builds count for an agent."
  [ds agent-id current-builds]
  (jdbc/execute-one! ds
    (sql/format {:update :agents
                 :set {:current-builds current-builds}
                 :where [:= :id agent-id]})))

(defn delete-agent!
  "Delete an agent record by id."
  [ds agent-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :agents
                 :where [:= :id agent-id]})))

(defn get-agent-by-id
  "Retrieve a single agent by id. Returns nil if not found."
  [ds agent-id]
  (-> (jdbc/execute-one! ds
        (sql/format {:select :*
                     :from :agents
                     :where [:= :id agent-id]})
        {:builder-fn rs/as-unqualified-kebab-maps})
      row->agent))

(defn load-all-agents
  "Load all agent records from the database.
   Returns a vector of agent maps with deserialized fields."
  [ds]
  (mapv row->agent
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :agents
                   :order-by [[:name :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))
