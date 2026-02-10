(ns chengis.db.cache-store
  "Database persistence for build/dependency cache entries.
   Tracks cache metadata: keys, paths, sizes, hit counts."
  (:require [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

(defn save-cache-entry!
  "Save or update a cache entry. Skips if key already exists (immutable cache)."
  [ds {:keys [job-id cache-key paths size-bytes org-id]}]
  (let [id (util/generate-id)]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :cache-entries
                     :values [{:id id
                               :job-id job-id
                               :cache-key cache-key
                               :paths (str paths)
                               :size-bytes (or size-bytes 0)
                               :org-id (or org-id "default-org")}]})
        {:builder-fn rs/as-unqualified-kebab-maps})
      (catch Exception e
        ;; Unique constraint violation — key already exists, skip
        (log/debug "Cache entry already exists for key:" cache-key)
        nil))))

(defn get-cache-entry
  "Retrieve a cache entry by job-id and exact cache-key."
  [ds job-id cache-key]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from [:cache-entries]
                 :where [:and [:= :job-id job-id] [:= :cache-key cache-key]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn find-by-prefix
  "Find cache entries whose cache-key starts with the given prefix.
   Used for restore-keys fallback matching. Returns most recent first."
  [ds job-id key-prefix]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from [:cache-entries]
                 :where [:and [:= :job-id job-id]
                              [:like :cache-key (str key-prefix "%")]]
                 :order-by [[:created-at :desc]]
                 :limit 5})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn record-cache-hit!
  "Increment hit count and update last_hit_at for a cache entry."
  [ds job-id cache-key]
  (jdbc/execute-one! ds
    (sql/format {:update :cache-entries
                 :set {:hit-count [:+ :hit-count 1]
                       :last-hit-at [:raw "CURRENT_TIMESTAMP"]}
                 :where [:and [:= :job-id job-id] [:= :cache-key cache-key]]})))

(defn delete-cache-entries!
  "Delete cache entries older than the specified number of days.
   Formats cutoff as 'YYYY-MM-DD HH:MM:SS' to match SQLite CURRENT_TIMESTAMP format."
  [ds job-id days]
  (let [cutoff-instant (.minus (java.time.Instant/now)
                               (java.time.Duration/ofDays days))
        cutoff (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                        (.atZone cutoff-instant (java.time.ZoneOffset/UTC)))]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :cache-entries
                   :where [:and
                           [:= :job-id job-id]
                           [:< :created-at cutoff]]}))))

(defn list-cache-entries
  "List all cache entries for a job."
  [ds job-id]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from [:cache-entries]
                 :where [:= :job-id job-id]
                 :order-by [[:created-at :desc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
