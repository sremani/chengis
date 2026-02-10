(ns chengis.db.sbom-store
  "CRUD store for SBOM (Software Bill of Materials) reports.
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

(defn create-sbom!
  "Insert a new SBOM report record. Returns the created row map."
  [ds {:keys [id build-id job-id org-id sbom-format sbom-version
              component-count content-hash sbom-content tool-name tool-version]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :sbom-format sbom-format
             :sbom-version sbom-version
             :component-count (or component-count 0)
             :content-hash content-hash
             :sbom-content sbom-content
             :tool-name tool-name
             :tool-version tool-version}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :sbom-reports
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Created SBOM report" {:id id :build-id build-id :format sbom-format})
    row))

(defn get-sbom
  "Get an SBOM report by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :sbom-reports
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-sboms
  "Get all SBOM reports for a given build, optionally scoped to org."
  [ds build-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :build-id build-id] [:= :org-id org-id]]
                [:= :build-id build-id])]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :sbom-reports
                   :where where
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-sboms
  "List SBOM reports with optional filters."
  [ds & {:keys [org-id job-id limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :sbom-reports
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-sbom!
  "Delete an SBOM report by ID, with optional org-id ownership check."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :sbom-reports
                   :where where}))))

(defn cleanup-old-sboms!
  "Delete SBOM reports older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :sbom-reports
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))
