(ns chengis.db.audit-store
  "Audit log persistence for security and compliance."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn insert-audit!
  "Insert a single audit log entry."
  [ds {:keys [user-id username action resource-type resource-id detail ip-address user-agent]}]
  (let [id (util/generate-id)
        row {:id id
             :user-id user-id
             :username username
             :action action
             :resource-type resource-type
             :resource-id resource-id
             :detail (util/serialize-edn detail)
             :ip-address ip-address
             :user-agent user-agent}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :audit-logs
                   :values [row]}))
    id))

(defn query-audits
  "Query audit logs with optional filters and pagination.
   Options: :user-id, :action, :resource-type, :from-date, :to-date, :limit, :offset"
  [ds {:keys [user-id action resource-type from-date to-date limit offset]
       :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     user-id       (conj [:= :user-id user-id])
                     action        (conj [:= :action action])
                     resource-type (conj [:= :resource-type resource-type])
                     from-date     (conj [:>= :timestamp from-date])
                     to-date       (conj [:<= :timestamp to-date]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select :*
                       :from :audit-logs
                       :order-by [[:timestamp :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (mapv (fn [row]
            (update row :detail util/deserialize-edn))
          (jdbc/execute! ds
            (sql/format query)
            {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn count-audits
  "Count audit log entries matching optional filters."
  [ds {:keys [user-id action resource-type from-date to-date]}]
  (let [conditions (cond-> [:and]
                     user-id       (conj [:= :user-id user-id])
                     action        (conj [:= :action action])
                     resource-type (conj [:= :resource-type resource-type])
                     from-date     (conj [:>= :timestamp from-date])
                     to-date       (conj [:<= :timestamp to-date]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select [[[:count :*] :count]]
                       :from :audit-logs}
                where (assoc :where where))]
    (:count
      (jdbc/execute-one! ds
        (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn purge-old!
  "Delete audit logs older than retention-days. Returns number of rows deleted."
  [ds retention-days]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :audit-logs
                              :where [:< :timestamp
                                      [:datetime "now" (str "-" retention-days " days")]]}))]
    (:next.jdbc/update-count result 0)))
