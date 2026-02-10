(ns chengis.db.cost-store
  "Database persistence for build cost attribution entries.
   Follows store conventions: ds as first arg, org-id scoping,
   HoneySQL for query generation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

(defn record-build-cost!
  "Insert a cost entry for a completed build."
  [ds {:keys [build-id job-id org-id agent-id started-at ended-at
              duration-s cost-per-hour computed-cost]}]
  (let [id (util/generate-id)
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :agent-id agent-id
             :started-at started-at
             :ended-at ended-at
             :duration-s duration-s
             :cost-per-hour (or cost-per-hour 1.0)
             :computed-cost computed-cost}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-cost-entries
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn get-build-cost
  "Get the cost entry for a specific build."
  [ds build-id]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from [:build-cost-entries]
                 :where [:= :build-id build-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-org-cost-summary
  "Get cost summary for an org, grouped by job.
   Returns: [{:job-id :total-cost :total-duration-s :build-count}]"
  [ds & {:keys [org-id limit] :or {limit 50}}]
  (let [conditions (when org-id [[:= :org-id org-id]])
        query (cond-> {:select [[:job-id :job-id]
                                [[:sum :computed-cost] :total-cost]
                                [[:sum :duration-s] :total-duration-s]
                                [[:count :*] :build-count]]
                       :from [:build-cost-entries]
                       :group-by [:job-id]
                       :order-by [[:total-cost :desc]]
                       :limit limit}
                (seq conditions) (assoc :where (if (= 1 (count conditions))
                                                 (first conditions)
                                                 (into [:and] conditions))))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-job-cost-summary
  "Get cost summary for a specific job over time."
  [ds job-id & {:keys [org-id limit] :or {limit 50}}]
  (let [conditions (cond-> [[:= :job-id job-id]]
                     org-id (conj [:= :org-id org-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:build-cost-entries]
                   :where where
                   :order-by [[:created-at :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-total-cost
  "Get total computed cost, optionally filtered by org-id."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:coalesce [:sum :computed-cost] 0] :total]]
                        :from [:build-cost-entries]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:total result) 0.0)))

(defn cleanup-old-costs!
  "Delete cost entries older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :build-cost-entries
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))

(defn count-cost-entries
  "Count cost entries, optionally filtered by org-id."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:build-cost-entries]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))
