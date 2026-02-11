(ns chengis.db.log-chunk-store
  "Chunked build log storage for large build outputs.
   Stores log output in ordered chunks (e.g. 1000 lines per chunk)
   instead of monolithic TEXT blobs in build_steps."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn save-chunk!
  "Save a single log chunk to the database."
  [ds {:keys [build-id step-id chunk-index source line-start line-count content]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-log-chunks
                   :values [{:id id
                             :build-id build-id
                             :step-id step-id
                             :chunk-index chunk-index
                             :source (or source "stdout")
                             :line-start line-start
                             :line-count line-count
                             :content content}]}))
    id))

(defn get-chunks
  "Retrieve log chunks for a step, ordered by chunk_index.
   Options: :source (stdout/stderr), :offset, :limit for lazy loading."
  [ds step-id & {:keys [source offset limit]
                  :or {offset 0 limit 10}}]
  (let [conditions (cond-> [[:= :step-id step-id]]
                     source (conj [:= :source source]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-log-chunks
                   :where where
                   :order-by [[:chunk-index :asc]]
                   :limit limit
                   :offset offset})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-chunks-for-build
  "Retrieve all log chunks for a build, ordered by step and chunk."
  [ds build-id & {:keys [source limit] :or {limit 100}}]
  (let [conditions (cond-> [[:= :build-id build-id]]
                     source (conj [:= :source source]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-log-chunks
                   :where where
                   :order-by [[:step-id :asc] [:chunk-index :asc]]
                   :limit limit})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-chunk-count
  "Return the total number of chunks for a step."
  [ds step-id & {:keys [source]}]
  (let [conditions (cond-> [[:= :step-id step-id]]
                     source (conj [:= :source source]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (or (:cnt (jdbc/execute-one! ds
                (sql/format {:select [[[:count :*] :cnt]]
                             :from :build-log-chunks
                             :where where})
                {:builder-fn rs/as-unqualified-kebab-maps}))
        0)))

(defn get-total-line-count
  "Return the total number of lines across all chunks for a step."
  [ds step-id & {:keys [source]}]
  (let [conditions (cond-> [[:= :step-id step-id]]
                     source (conj [:= :source source]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (or (:total (jdbc/execute-one! ds
                  (sql/format {:select [[[:coalesce [:sum :line-count] 0] :total]]
                               :from :build-log-chunks
                               :where where})
                  {:builder-fn rs/as-unqualified-kebab-maps}))
        0)))

(defn delete-chunks-for-build!
  "Delete all log chunks for a build. Used during cleanup."
  [ds build-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :build-log-chunks
                 :where [:= :build-id build-id]})))
