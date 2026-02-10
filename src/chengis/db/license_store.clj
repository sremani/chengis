(ns chengis.db.license-store
  "CRUD store for license scanning reports and license policies.
   license_reports: per-build license scan results.
   license_policies: org-scoped allow/deny rules for license IDs.
   Follows store conventions: ds as first arg, org-id scoping,
   HoneySQL for query generation."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private sqlite-ts-formatter
  "Formatter matching SQLite CURRENT_TIMESTAMP format: 'yyyy-MM-dd HH:mm:ss'."
  (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"))

(defn- format-cutoff
  "Format an Instant as a string matching SQLite's CURRENT_TIMESTAMP format."
  [^Instant inst]
  (.format sqlite-ts-formatter (.atZone inst ZoneOffset/UTC)))

;; ---------------------------------------------------------------------------
;; License Reports
;; ---------------------------------------------------------------------------

(defn create-report!
  "Insert a license scan report for a build."
  [ds {:keys [id build-id job-id org-id total-deps allowed-count
              denied-count unknown-count policy-passed licenses-json]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :total-deps (or total-deps 0)
             :allowed-count (or allowed-count 0)
             :denied-count (or denied-count 0)
             :unknown-count (or unknown-count 0)
             :policy-passed (if (nil? policy-passed) 1 (if policy-passed 1 0))
             :licenses-json licenses-json}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :license-reports
                   :values [row]}))
    (log/info "Created license report" {:id id :build-id build-id
                                         :denied denied-count})
    row))

(defn get-build-report
  "Get the license report for a build, optionally scoped to org."
  [ds build-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :build-id build-id] [:= :org-id org-id]]
                [:= :build-id build-id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :license-reports
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-reports
  "List license reports with optional filters."
  [ds & {:keys [org-id job-id policy-passed limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id])
                     (some? policy-passed)
                     (conj [:= :policy-passed (if policy-passed 1 0)]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :license-reports
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn cleanup-old-reports!
  "Delete license reports older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :license-reports
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))

;; ---------------------------------------------------------------------------
;; License Policies
;; ---------------------------------------------------------------------------

(defn create-license-policy!
  "Create a license policy entry. action is \"allow\" or \"deny\"."
  [ds {:keys [id org-id license-id action]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :license-id license-id
             :action (or action "allow")}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :license-policies
                   :values [row]}))
    (log/info "Created license policy" {:id id :license-id license-id :action action})
    row))

(defn list-license-policies
  "List license policies, optionally scoped to org."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select :*
                       :from :license-policies
                       :order-by [[:license-id :asc]]}
                org-id (assoc :where [:= :org-id org-id]))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-license-policy!
  "Delete a license policy by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :license-policies
                   :where where}))))
