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
  "Create a new job from a pipeline definition. Returns the created job.
   When org-id is provided, the job is scoped to that organization."
  [ds pipeline-def & {:keys [org-id]}]
  (let [id (util/generate-id)
        job-name (:pipeline-name pipeline-def)
        row (cond-> {:id id
                     :name job-name
                     :pipeline (util/serialize-edn pipeline-def)
                     :triggers (util/serialize-edn (:triggers pipeline-def))
                     :parameters (util/serialize-edn (:parameters pipeline-def))}
              org-id (assoc :org-id org-id))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :jobs
                   :values [row]}))
    (assoc row :pipeline pipeline-def
               :triggers (:triggers pipeline-def)
               :parameters (:parameters pipeline-def))))

(defn get-job
  "Retrieve a job by name. When org-id is provided, scopes lookup to that org."
  [ds job-name & {:keys [org-id]}]
  (row->job
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :jobs
                   :where (if org-id
                            [:and [:= :name job-name] [:= :org-id org-id]]
                            [:= :name job-name])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-job-by-id
  "Retrieve a job by ID. When org-id is provided, verifies the job belongs to that org."
  [ds job-id & {:keys [org-id]}]
  (row->job
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :jobs
                   :where (if org-id
                            [:and [:= :id job-id] [:= :org-id org-id]]
                            [:= :id job-id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-jobs
  "List all jobs. When org-id is provided, only returns jobs in that org."
  [ds & {:keys [org-id]}]
  (mapv row->job
    (jdbc/execute! ds
      (sql/format (cond-> {:select :*
                            :from :jobs
                            :order-by [[:created-at :asc]]}
                    org-id (assoc :where [:= :org-id org-id])))
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-job!
  "Delete a job by name. Returns true if a row was deleted.
   When org-id is provided, only deletes within that org."
  [ds job-name & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :jobs
                              :where (if org-id
                                       [:and [:= :name job-name] [:= :org-id org-id]]
                                       [:= :name job-name])}))]
    (pos? (:next.jdbc/update-count result 0))))

(defn update-job!
  "Update a job's pipeline definition. When org-id is provided, scopes to that org."
  [ds job-name pipeline-def & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:update :jobs
                 :set {:pipeline (util/serialize-edn pipeline-def)
                       :triggers (util/serialize-edn (:triggers pipeline-def))
                       :parameters (util/serialize-edn (:parameters pipeline-def))
                       :updated-at [:raw "CURRENT_TIMESTAMP"]}
                 :where (if org-id
                          [:and [:= :name job-name] [:= :org-id org-id]]
                          [:= :name job-name])})))
