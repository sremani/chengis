(ns chengis.db.partitioning
  "Database partitioning management for PostgreSQL.
   Provides monthly range partitioning for high-growth tables.
   Includes partition lifecycle: creation, maintenance, and cleanup."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.string :as str]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.time YearMonth LocalDate]
           [java.time.format DateTimeFormatter]))

(def ^:private partitionable-tables
  "Tables eligible for partitioning with their partition column."
  {"builds" "created_at"
   "build_events" "created_at"
   "audit_logs" "timestamp"})

(defn- validate-table-name!
  "Validate that a table name is in the allowed set. Prevents SQL injection
   in raw DDL statements that use string interpolation."
  [table-name]
  (when-not (contains? partitionable-tables table-name)
    (throw (ex-info (str "Invalid table for partitioning: " table-name)
                    {:table-name table-name
                     :allowed (keys partitionable-tables)}))))

(defn- partition-name
  "Generate partition name for a table and year-month."
  [table-name year month]
  (format "%s_y%dm%02d" table-name year month))

(defn- month-range
  "Return [start end) dates for a year-month partition."
  [year month]
  (let [ym (YearMonth/of year month)
        start (.atDay ym 1)
        end (.atDay (.plusMonths ym 1) 1)]
    [(.format start (DateTimeFormatter/ISO_LOCAL_DATE))
     (.format end (DateTimeFormatter/ISO_LOCAL_DATE))]))

(defn create-monthly-partition!
  "Create a partition for a specific year-month range.
   Records the partition in partition_metadata.
   Returns true if created, false if already exists."
  [ds table-name year month]
  (validate-table-name! table-name)
  (let [part-name (partition-name table-name year month)
        [range-start range-end] (month-range year month)]
    (try
      (jdbc/execute-one! ds
        [(format "CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')"
                 part-name table-name range-start range-end)])
      ;; Record in metadata
      (jdbc/execute-one! ds
        (sql/format {:insert-into :partition-metadata
                     :values [{:id (util/generate-id)
                               :table-name table-name
                               :partition-name part-name
                               :range-start range-start
                               :range-end range-end
                               :status "active"}]
                     :on-conflict [:table-name :partition-name]
                     :do-nothing true}))
      (log/info "Created partition" part-name "for" table-name
                "range" range-start "to" range-end)
      true
      (catch Exception e
        (if (str/includes? (str (.getMessage e)) "already exists")
          (do (log/debug "Partition" part-name "already exists") false)
          (do (log/error "Failed to create partition" part-name ":" (.getMessage e))
              false))))))

(defn ensure-future-partitions!
  "Ensure partitions exist for the next N months from today."
  [ds table-name n]
  (let [today (LocalDate/now)]
    (doseq [offset (range (inc n))]  ;; include current month
      (let [future-month (.plusMonths (YearMonth/from today) offset)]
        (create-monthly-partition! ds table-name
          (.getYear future-month) (.getMonthValue future-month))))))

(defn list-partitions
  "List all partitions for a table from the metadata table."
  [ds table-name]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :partition-metadata
                 :where [:= :table-name table-name]
                 :order-by [[:range-start :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn detach-partition!
  "Detach a partition (makes it a standalone table for archival).
   Updates metadata status to 'detached'."
  [ds table-name partition-name-str]
  (validate-table-name! table-name)
  (try
    (jdbc/execute-one! ds
      [(format "ALTER TABLE %s DETACH PARTITION %s" table-name partition-name-str)])
    (jdbc/execute-one! ds
      (sql/format {:update :partition-metadata
                   :set {:status "detached"}
                   :where [:and
                           [:= :table-name table-name]
                           [:= :partition-name partition-name-str]]}))
    (log/info "Detached partition" partition-name-str "from" table-name)
    true
    (catch Exception e
      (log/error "Failed to detach partition" partition-name-str ":" (.getMessage e))
      false)))

(defn drop-old-partitions!
  "Drop partitions older than retention-months."
  [ds table-name retention-months]
  (let [cutoff (.format (.atDay (.minusMonths (YearMonth/now) retention-months) 1)
                        (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        old-partitions (jdbc/execute! ds
                         (sql/format {:select :*
                                      :from :partition-metadata
                                      :where [:and
                                              [:= :table-name table-name]
                                              [:< :range-end cutoff]
                                              [:= :status "active"]]})
                         {:builder-fn rs/as-unqualified-kebab-maps})]
    (doseq [part old-partitions]
      (log/info "Dropping old partition" (:partition-name part)
                "range" (:range-start part) "to" (:range-end part))
      (detach-partition! ds table-name (:partition-name part))
      ;; Actually drop the detached table
      (try
        (jdbc/execute-one! ds
          [(format "DROP TABLE IF EXISTS %s" (:partition-name part))])
        (jdbc/execute-one! ds
          (sql/format {:update :partition-metadata
                       :set {:status "dropped"}
                       :where [:= :id (:id part)]}))
        (catch Exception e
          (log/error "Failed to drop" (:partition-name part) ":" (.getMessage e)))))))

(defn maintenance-cycle!
  "Run one partition maintenance cycle for all partitionable tables.
   Creates future partitions and drops expired ones."
  [ds config]
  (let [retention (get-in config [:partitioning :retention-months] 12)
        future-n (get-in config [:partitioning :future-partitions] 3)]
    (doseq [[table-name _col] partitionable-tables]
      (try
        (ensure-future-partitions! ds table-name future-n)
        (drop-old-partitions! ds table-name retention)
        (catch Exception e
          (log/error "Partition maintenance failed for" table-name ":" (.getMessage e)))))))

(defn partitioning-status
  "Get a summary of partitioning status for monitoring."
  [ds]
  (let [all-parts (jdbc/execute! ds
                    (sql/format {:select [:table-name
                                          [[:count :*] :total]
                                          [[:sum [:case [:= :status "active"] [:inline 1]
                                                        :else [:inline 0]]]
                                           :active]]
                                 :from :partition-metadata
                                 :group-by [:table-name]})
                    {:builder-fn rs/as-unqualified-kebab-maps})]
    {:tables (vec all-parts)
     :partitionable-tables (keys partitionable-tables)}))
