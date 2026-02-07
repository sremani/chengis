(ns chengis.db.artifact-store
  "Storage for build artifacts metadata in SQLite."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn save-artifact!
  "Save artifact metadata to the database."
  [ds {:keys [build-id filename path size-bytes content-type]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-artifacts
                   :values [{:id id
                             :build-id build-id
                             :filename filename
                             :path path
                             :size-bytes size-bytes
                             :content-type (or content-type "application/octet-stream")}]}))
    {:id id :build-id build-id :filename filename :path path
     :size-bytes size-bytes :content-type content-type}))

(defn list-artifacts
  "List all artifacts for a build."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :build-artifacts
                 :where [:= :build-id build-id]
                 :order-by [[:filename :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-artifact
  "Get a single artifact by build-id and filename."
  [ds build-id filename]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :build-artifacts
                 :where [:and [:= :build-id build-id]
                              [:= :filename filename]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn delete-artifacts-for-build!
  "Delete all artifact records for a build."
  [ds build-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :build-artifacts
                 :where [:= :build-id build-id]})))
