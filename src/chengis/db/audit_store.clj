(ns chengis.db.audit-store
  "Audit log persistence for security and compliance.
   Supports SHA-256 hash chain for tamper-evident logging."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [chengis.util :as util])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Hash chain helpers
;; ---------------------------------------------------------------------------

(defn- compute-entry-hash
  "Compute SHA-256 of an audit entry + previous hash to form a chain.
   Uses a canonical string of sorted key/value pairs."
  [entry-map prev-hash]
  (try
    (let [;; Build canonical string from sorted keys, excluding hash columns
          canonical (->> (dissoc entry-map :prev-hash :entry-hash)
                         (sort-by (comp str key))
                         (map (fn [[k v]] (str (name k) "=" v)))
                         (str/join "|"))
          data (str canonical "|prev=" (or prev-hash "genesis"))
          digest (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes data "UTF-8"))]
      (format "%064x" (BigInteger. 1 hash-bytes)))
    (catch Exception _e nil)))

(defn- get-latest-hash
  "Get the entry_hash of the most recent audit log entry.
   Uses seq_num for deterministic insertion-order tiebreaking (cross-DB portable)."
  [ds]
  (let [row (jdbc/execute-one! ds
              ["SELECT entry_hash FROM audit_logs ORDER BY seq_num DESC LIMIT 1"]
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (:entry-hash row)))

(defn- next-seq-num
  "Get the next sequence number for audit log insertion ordering."
  [ds]
  (let [row (jdbc/execute-one! ds
              ["SELECT COALESCE(MAX(seq_num), 0) + 1 AS next_seq FROM audit_logs"]
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (:next-seq row)))

;; ---------------------------------------------------------------------------
;; Core operations
;; ---------------------------------------------------------------------------

(defn insert-audit!
  "Insert a single audit log entry with hash chain.
   When :org-id is provided, associates the entry with that organization.
   Assigns a seq_num for deterministic insertion ordering (cross-DB portable)."
  [ds {:keys [user-id username action resource-type resource-id detail ip-address user-agent org-id]}]
  (let [id (util/generate-id)
        base-row (cond-> {:id id
                          :user-id user-id
                          :username username
                          :action action
                          :resource-type resource-type
                          :resource-id resource-id
                          :detail (util/serialize-edn detail)
                          :ip-address ip-address
                          :user-agent user-agent}
                   org-id (assoc :org-id org-id))
        ;; Hash chain: link to previous entry
        prev-hash (get-latest-hash ds)
        entry-hash (compute-entry-hash base-row prev-hash)
        seq-num (next-seq-num ds)
        row (assoc base-row
              :prev-hash prev-hash
              :entry-hash entry-hash
              :seq-num seq-num)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :audit-logs
                   :values [row]}))
    id))

(defn query-audits
  "Query audit logs with optional filters and pagination.
   Options: :user-id, :action, :resource-type, :from-date, :to-date, :org-id, :limit, :offset"
  [ds {:keys [user-id action resource-type from-date to-date org-id limit offset]
       :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     user-id       (conj [:= :user-id user-id])
                     action        (conj [:= :action action])
                     resource-type (conj [:= :resource-type resource-type])
                     org-id        (conj [:= :org-id org-id])
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

(defn query-audits-asc
  "Query audit logs in ascending timestamp order (for hash chain verification).
   Options: :from-date, :to-date, :org-id, :limit, :offset"
  [ds {:keys [from-date to-date org-id limit offset]
       :or {limit 1000 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id    (conj [:= :org-id org-id])
                     from-date (conj [:>= :timestamp from-date])
                     to-date   (conj [:<= :timestamp to-date]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :audit-logs
                       :order-by [[:seq-num :asc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn count-audits
  "Count audit log entries matching optional filters."
  [ds {:keys [user-id action resource-type from-date to-date org-id]}]
  (let [conditions (cond-> [:and]
                     user-id       (conj [:= :user-id user-id])
                     action        (conj [:= :action action])
                     resource-type (conj [:= :resource-type resource-type])
                     org-id        (conj [:= :org-id org-id])
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
  (let [cutoff (str (.minus (java.time.Instant/now) (java.time.Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :audit-logs
                              :where [:< :timestamp cutoff]}))]
    (:next.jdbc/update-count result 0)))
