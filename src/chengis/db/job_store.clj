(ns chengis.db.job-store
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn- row->job [row]
  (when row
    (-> row
        (update :pipeline util/deserialize-edn)
        (update :triggers util/deserialize-edn)
        (update :parameters util/deserialize-edn))))

(defn create-job!
  "Create a new job from a pipeline definition. Returns the created job."
  [ds pipeline-def]
  (let [id (util/generate-id)
        job-name (:pipeline-name pipeline-def)
        row {:id id
             :name job-name
             :pipeline (util/serialize-edn pipeline-def)
             :triggers (util/serialize-edn (:triggers pipeline-def))
             :parameters (util/serialize-edn (:parameters pipeline-def))}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :jobs
                   :values [row]}))
    (assoc row :pipeline pipeline-def
               :triggers (:triggers pipeline-def)
               :parameters (:parameters pipeline-def))))

(defn get-job
  "Retrieve a job by name."
  [ds job-name]
  (row->job
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :jobs
                   :where [:= :name job-name]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-job-by-id
  "Retrieve a job by ID."
  [ds job-id]
  (row->job
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :jobs
                   :where [:= :id job-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-jobs
  "List all jobs."
  [ds]
  (mapv row->job
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :jobs
                   :order-by [[:created-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-job!
  "Delete a job by name. Returns true if a row was deleted."
  [ds job-name]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :jobs
                              :where [:= :name job-name]}))]
    (pos? (:next.jdbc/update-count result 0))))

(defn update-job!
  "Update a job's pipeline definition."
  [ds job-name pipeline-def]
  (jdbc/execute-one! ds
    (sql/format {:update :jobs
                 :set {:pipeline (util/serialize-edn pipeline-def)
                       :triggers (util/serialize-edn (:triggers pipeline-def))
                       :parameters (util/serialize-edn (:parameters pipeline-def))
                       :updated-at [:datetime "now"]}
                 :where [:= :name job-name]})))
