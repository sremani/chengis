(ns chengis.db.analytics-store
  "Database persistence for precomputed build and stage analytics.
   Follows store conventions: ds as first arg, org-id scoping,
   HoneySQL for query generation."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; Build Analytics
;; ---------------------------------------------------------------------------

(defn upsert-build-analytics!
  "Insert or update a build analytics row for a given org/job/period.
   Uses INSERT with ON CONFLICT for idempotent aggregation runs."
  [ds {:keys [org-id job-id period-type period-start period-end
              total-builds success-count failure-count aborted-count
              success-rate avg-duration-s
              p50-duration-s p90-duration-s p99-duration-s max-duration-s]}]
  (let [id (util/generate-id)
        row {:id id
             :org-id org-id
             :job-id job-id
             :period-type period-type
             :period-start period-start
             :period-end period-end
             :total-builds total-builds
             :success-count success-count
             :failure-count failure-count
             :aborted-count aborted-count
             :success-rate success-rate
             :avg-duration-s avg-duration-s
             :p50-duration-s p50-duration-s
             :p90-duration-s p90-duration-s
             :p99-duration-s p99-duration-s
             :max-duration-s max-duration-s}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-analytics
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

;; ---------------------------------------------------------------------------
;; Stage Analytics
;; ---------------------------------------------------------------------------

(defn upsert-stage-analytics!
  "Insert or update a stage analytics row."
  [ds {:keys [org-id job-id stage-name period-type period-start period-end
              total-runs success-count failure-count
              avg-duration-s p90-duration-s max-duration-s flakiness-score]}]
  (let [id (util/generate-id)
        row {:id id
             :org-id org-id
             :job-id job-id
             :stage-name stage-name
             :period-type period-type
             :period-start period-start
             :period-end period-end
             :total-runs total-runs
             :success-count success-count
             :failure-count failure-count
             :avg-duration-s avg-duration-s
             :p90-duration-s p90-duration-s
             :max-duration-s max-duration-s
             :flakiness-score flakiness-score}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :stage-analytics
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

;; ---------------------------------------------------------------------------
;; Queries — Build Trends
;; ---------------------------------------------------------------------------

(defn get-build-trends
  "Get build analytics trend data for charts.
   Returns analytics rows ordered by period_start ascending.
   Options: :org-id, :job-id, :period-type (daily/weekly), :limit."
  [ds & {:keys [org-id job-id period-type limit]
         :or {period-type "daily" limit 90}}]
  (let [conditions (cond-> [[:= :period-type period-type]]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:build-analytics]
                   :where where
                   :order-by [[:period-start :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-latest-build-analytics
  "Get the most recent analytics row for an org/job/period combination."
  [ds & {:keys [org-id job-id period-type]
         :or {period-type "daily"}}]
  (let [conditions (cond-> [[:= :period-type period-type]]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute-one! ds
      (sql/format {:select [:*]
                   :from [:build-analytics]
                   :where where
                   :order-by [[:period-start :desc]]
                   :limit 1})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Queries — Stage Analytics
;; ---------------------------------------------------------------------------

(defn get-slowest-stages
  "Get stages ranked by p90 duration for a given period.
   Returns the most recent period's data."
  [ds & {:keys [org-id job-id period-type limit]
         :or {period-type "daily" limit 20}}]
  (let [conditions (cond-> [[:= :period-type period-type]]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:stage-analytics]
                   :where where
                   :order-by [[:p90-duration-s :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-flaky-stages
  "Get stages with flakiness score above a threshold."
  [ds & {:keys [org-id period-type threshold limit]
         :or {period-type "daily" threshold 0.15 limit 20}}]
  (let [conditions (cond-> [[:= :period-type period-type]
                            [:> :flakiness-score threshold]]
                     org-id (conj [:= :org-id org-id]))
        where (into [:and] conditions)]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:stage-analytics]
                   :where where
                   :order-by [[:flakiness-score :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-stage-trends
  "Get stage analytics trend data for a specific stage."
  [ds stage-name & {:keys [org-id job-id period-type limit]
                    :or {period-type "daily" limit 30}}]
  (let [conditions (cond-> [[:= :period-type period-type]
                            [:= :stage-name stage-name]]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (into [:and] conditions)]
    (jdbc/execute! ds
      (sql/format {:select [:*]
                   :from [:stage-analytics]
                   :where where
                   :order-by [[:period-start :desc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(defn cleanup-old-analytics!
  "Delete analytics records older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (Instant/now) (Duration/ofDays retention-days)))
        r1 (jdbc/execute-one! ds
             (sql/format {:delete-from :build-analytics
                          :where [:< :computed-at cutoff]}))
        r2 (jdbc/execute-one! ds
             (sql/format {:delete-from :stage-analytics
                          :where [:< :computed-at cutoff]}))]
    (+ (or (:next.jdbc/update-count r1) 0)
       (or (:next.jdbc/update-count r2) 0))))

;; ---------------------------------------------------------------------------
;; Count helpers
;; ---------------------------------------------------------------------------

(defn count-build-analytics
  "Count build analytics records, optionally filtered by org-id."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:build-analytics]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))

(defn count-stage-analytics
  "Count stage analytics records, optionally filtered by org-id."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select [[[:count :*] :cnt]]
                        :from [:stage-analytics]}
                org-id (assoc :where [:= :org-id org-id]))
        result (jdbc/execute-one! ds
                 (sql/format query)
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:cnt result) 0)))
