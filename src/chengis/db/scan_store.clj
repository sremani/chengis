(ns chengis.db.scan-store
  "CRUD store for vulnerability scan results.
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

(defn create-scan!
  "Insert a new vulnerability scan record. Returns the created row map."
  [ds {:keys [id build-id job-id org-id scan-target scanner scanner-version
              critical-count high-count medium-count low-count total-count
              pass-threshold passed results-json]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :scan-target scan-target
             :scanner scanner
             :scanner-version scanner-version
             :critical-count (or critical-count 0)
             :high-count (or high-count 0)
             :medium-count (or medium-count 0)
             :low-count (or low-count 0)
             :total-count (or total-count 0)
             :pass-threshold pass-threshold
             :passed (if (nil? passed) 1 (if (boolean? passed) (if passed 1 0) passed))
             :results-json results-json}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :vulnerability-scans
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Created vulnerability scan" {:id id :build-id build-id :scanner scanner})
    row))

(defn get-build-scans
  "Get all vulnerability scans for a given build, optionally scoped to org."
  [ds build-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :build-id build-id] [:= :org-id org-id]]
                [:= :build-id build-id])]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :vulnerability-scans
                   :where where
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-scans
  "List vulnerability scans with optional filters.
   The 'passed' parameter filters by pass/fail status (0 or 1)."
  [ds & {:keys [org-id job-id passed limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id       (conj [:= :org-id org-id])
                     job-id       (conj [:= :job-id job-id])
                     (some? passed) (conj [:= :passed passed]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :vulnerability-scans
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-scan-summary
  "Get aggregate summary: total scans, passed, failed in last N days.
   Returns {:total n :passed n :failed n}."
  [ds & {:keys [org-id days]
         :or {days 30}}]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays days)))
        conditions (cond-> [:and [:>= :created-at cutoff]]
                     org-id (conj [:= :org-id org-id]))
        total-query {:select [[[:count :*] :total]]
                     :from :vulnerability-scans
                     :where conditions}
        passed-query {:select [[[:count :*] :passed]]
                      :from :vulnerability-scans
                      :where (conj conditions [:= :passed 1])}
        failed-query {:select [[[:count :*] :failed]]
                      :from :vulnerability-scans
                      :where (conj conditions [:= :passed 0])}
        total (or (:total (jdbc/execute-one! ds (sql/format total-query)
                            {:builder-fn rs/as-unqualified-kebab-maps})) 0)
        passed (or (:passed (jdbc/execute-one! ds (sql/format passed-query)
                              {:builder-fn rs/as-unqualified-kebab-maps})) 0)
        failed (or (:failed (jdbc/execute-one! ds (sql/format failed-query)
                              {:builder-fn rs/as-unqualified-kebab-maps})) 0)]
    {:total total :passed passed :failed failed}))

(defn cleanup-old-scans!
  "Delete vulnerability scans older than retention-days. Returns count deleted."
  [ds retention-days]
  (let [cutoff (format-cutoff (.minus (Instant/now) (Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :vulnerability-scans
                              :where [:< :created-at cutoff]}))]
    (or (:next.jdbc/update-count result) 0)))
