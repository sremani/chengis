(ns chengis.db.provenance-store
  "CRUD store for SLSA provenance attestations.
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

(defn create-attestation!
  "Insert a new provenance attestation record. Returns the created row map."
  [ds {:keys [id build-id job-id org-id slsa-level predicate-type
              subject-json predicate-json envelope-json
              builder-id build-type source-repo source-branch source-commit]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :slsa-level (or slsa-level "L1")
             :predicate-type (or predicate-type "https://slsa.dev/provenance/v1")
             :subject-json subject-json
             :predicate-json predicate-json
             :envelope-json envelope-json
             :builder-id (or builder-id "chengis")
             :build-type (or build-type "chengis/pipeline/v1")
             :source-repo source-repo
             :source-branch source-branch
             :source-commit source-commit}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :provenance-attestations
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Created provenance attestation" {:id id :build-id build-id})
    row))

(defn get-attestation
  "Get a provenance attestation by build-id, optionally scoped to org."
  [ds build-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :build-id build-id] [:= :org-id org-id]]
                [:= :build-id build-id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :provenance-attestations
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-attestations
  "List provenance attestations with optional filters."
  [ds & {:keys [org-id job-id limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :provenance-attestations
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-attestation!
  "Delete a provenance attestation by ID, with optional org-id ownership check."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :provenance-attestations
                   :where where}))))

(defn cleanup-old-attestations!
  "Delete attestations older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :provenance-attestations
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))
