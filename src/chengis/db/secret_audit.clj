(ns chengis.db.secret-audit
  "Secret access audit trail — logs every read, write, and delete of secrets."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn log-secret-access!
  "Log a secret access event. Action is one of: read, write, delete, build-read.
   When :org-id is provided, associates the entry with that organization.
   Swallows errors (non-blocking) — table may not exist if migration hasn't run."
  [ds {:keys [secret-name scope action user-id ip-address org-id]}]
  (try
    (let [id (util/generate-id)
          row (cond-> {:id id
                       :secret-name secret-name
                       :scope (or scope "global")
                       :action (if action (name action) "unknown")
                       :user-id user-id
                       :ip-address ip-address}
                org-id (assoc :org-id org-id))]
      (jdbc/execute-one! ds
        (sql/format {:insert-into :secret-access-log
                     :values [row]}))
      id)
    (catch Exception _
      nil)))

(defn list-secret-accesses
  "Query secret access log with optional filters and pagination.
   When :org-id is provided, scopes to that organization."
  [ds & {:keys [secret-name scope org-id limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     secret-name (conj [:= :secret-name secret-name])
                     scope       (conj [:= :scope scope])
                     org-id      (conj [:= :org-id org-id]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select :*
                       :from :secret-access-log
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn count-secret-accesses
  "Count secret access log entries.
   When :org-id is provided, scopes to that organization."
  [ds & {:keys [secret-name scope org-id]}]
  (let [conditions (cond-> [:and]
                     secret-name (conj [:= :secret-name secret-name])
                     scope       (conj [:= :scope scope])
                     org-id      (conj [:= :org-id org-id]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select [[[:count :*] :count]]
                       :from :secret-access-log}
                where (assoc :where where))]
    (:count
      (jdbc/execute-one! ds
        (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn cleanup-old-accesses!
  "Delete secret access log entries older than retention-days."
  [ds retention-days]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :secret-access-log
                              :where [:< :created-at
                                      [:datetime "now" (str "-" retention-days " days")]]}))]
    (:next.jdbc/update-count result 0)))
