(ns chengis.db.signature-store
  "CRUD store for artifact_signatures table.
   Stores cryptographic signatures for build artifacts (cosign, GPG)."
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

(defn create-signature!
  "Insert a new artifact signature record. Generates id if nil.
   verified defaults to 0 (unverified)."
  [ds {:keys [id artifact-id build-id job-id org-id signer key-reference
              signature-value target-digest]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :artifact-id artifact-id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :signer signer
             :key-reference key-reference
             :signature-value signature-value
             :verified 0
             :target-digest target-digest}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :artifact-signatures
                   :values [row]}))
    (log/info "Created artifact signature" {:id id :build-id build-id :signer signer})
    row))

(defn get-build-signatures
  "Get all signatures for a build, optionally scoped to org."
  [ds build-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :build-id build-id] [:= :org-id org-id]]
                [:= :build-id build-id])]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :artifact-signatures
                   :where where
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-signatures
  "List signatures with optional filters.
   verified is 0 or 1. Default limit 50 offset 0."
  [ds & {:keys [org-id job-id verified limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id        (conj [:= :org-id org-id])
                     job-id        (conj [:= :job-id job-id])
                     (some? verified) (conj [:= :verified verified]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :artifact-signatures
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn verify-signature!
  "Mark a signature as verified. Sets verified=1 and verified_at=CURRENT_TIMESTAMP.
   Optionally scoped to org-id for multi-tenant safety."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:update :artifact-signatures
                   :set {:verified 1
                         :verified-at [:raw "CURRENT_TIMESTAMP"]}
                   :where where})))
  (log/info "Verified signature" {:id id}))

(defn cleanup-old-signatures!
  "Delete signatures older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :artifact-signatures
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))
