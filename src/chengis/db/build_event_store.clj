(ns chengis.db.build-event-store
  "Durable build event persistence for replay and audit.
   Events are persisted to the build_events table and can be replayed
   for SSE reconnection or post-mortem analysis."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

(def ^:private seq-counter (atom 0))

(defn- time-ordered-id
  "Generate a time-ordered ID: epoch millis + sequence counter + random suffix.
   Ensures events inserted sequentially have lexicographically sortable IDs,
   avoiding the ordering problem with random UUIDs when created_at has
   second-level precision (SQLite). The zero-padded counter ensures correct
   ordering even within the same millisecond."
  []
  (let [n (swap! seq-counter inc)]
    (format "%d-%06d-%s" (System/currentTimeMillis) (mod n 999999) (util/generate-id))))

(defn persist-event!
  "Persist a build event to the database.
   Event map keys: :build-id, :event-type, :stage-name, :step-name, :data.
   The :data field is serialized as EDN for SQLite portability."
  [ds event]
  (let [id (time-ordered-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-events
                   :values [{:id id
                             :build-id (:build-id event)
                             :event-type (if (keyword? (:event-type event))
                                           (name (:event-type event))
                                           (str (:event-type event)))
                             :stage-name (:stage-name event)
                             :step-name (:step-name event)
                             :data (util/serialize-edn (:data event))}]}))
    id))

(defn list-events
  "List events for a build, ordered ascending by time-ordered ID.
   Options:
     :after-id    — return only events after this event ID (cursor-based pagination)
     :event-type  — filter by event type (keyword or string)
     :limit       — max events to return (default: 500)"
  [ds build-id & {:keys [after-id event-type limit]}]
  (let [limit (or limit 500)
        conditions (cond-> [[:= :build-id build-id]]
                     ;; Use id-based pagination since IDs are time-ordered
                     after-id   (conj [:> :id after-id])
                     event-type (conj [:= :event-type (if (keyword? event-type)
                                                        (name event-type)
                                                        (str event-type))]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (mapv (fn [row]
            (-> row
                (update :event-type util/ensure-keyword)
                (update :data util/deserialize-edn)))
      (jdbc/execute! ds
        (sql/format {:select :*
                     :from :build-events
                     :where where
                     :order-by [[:id :asc]]
                     :limit limit})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn count-events
  "Count the number of events for a build."
  [ds build-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[[:count :*] :cnt]]
                              :from :build-events
                              :where [:= :build-id build-id]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))

(defn cleanup-old-events!
  "Delete events older than retention-days. Returns the number of rows deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (java.time.Instant/now)
                            (java.time.Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :build-events
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))
