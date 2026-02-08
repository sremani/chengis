(ns chengis.db.secret-audit
  "Secret access audit trail — logs every read, write, and delete of secrets."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn log-secret-access!
  "Log a secret access event. Action is one of: read, write, delete, build-read.
   Swallows errors (non-blocking) — table may not exist if migration hasn't run."
  [ds {:keys [secret-name scope action user-id ip-address]}]
  (try
    (let [id (util/generate-id)
          row {:id id
               :secret-name secret-name
               :scope (or scope "global")
               :action (if action (name action) "unknown")
               :user-id user-id
               :ip-address ip-address}]
      (jdbc/execute-one! ds
        (sql/format {:insert-into :secret-access-log
                     :values [row]}))
      id)
    (catch Exception _
      nil)))

(defn list-secret-accesses
  "Query secret access log with optional filters and pagination."
  [ds & {:keys [secret-name scope limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     secret-name (conj [:= :secret-name secret-name])
                     scope       (conj [:= :scope scope]))
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
  "Count secret access log entries."
  [ds & {:keys [secret-name scope]}]
  (let [conditions (cond-> [:and]
                     secret-name (conj [:= :secret-name secret-name])
                     scope       (conj [:= :scope scope]))
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
