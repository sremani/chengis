(ns chengis.db.build-store
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn- normalize-build
  "Normalize a build row: deserialize parameters, convert status to keyword."
  [build]
  (when build
    (-> build
        (update :parameters util/deserialize-edn)
        (update :status util/ensure-keyword))))

(defn- normalize-status
  "Convert the :status field from string to keyword."
  [row]
  (update row :status util/ensure-keyword))

(defn next-build-number
  "Get the next build number for a job."
  [ds job-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[[:max :build-number] :max-num]]
                              :from :builds
                              :where [:= :job-id job-id]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (inc (or (:max-num result) 0))))

(defn create-build!
  "Create a new build record. Returns the build map."
  [ds {:keys [job-id trigger-type parameters workspace]}]
  (let [id (util/generate-id)
        build-number (next-build-number ds job-id)
        row {:id id
             :job-id job-id
             :build-number build-number
             :status "queued"
             :trigger-type (when trigger-type (name trigger-type))
             :parameters (util/serialize-edn parameters)
             :workspace workspace}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :builds
                   :values [row]}))
    (normalize-build (assoc row :build-number build-number))))

(defn update-build-status!
  "Update the status of a build."
  [ds build-id status & {:keys [started-at completed-at]}]
  (let [updates (cond-> {:status (name status)}
                  started-at   (assoc :started-at started-at)
                  completed-at (assoc :completed-at completed-at))]
    (jdbc/execute-one! ds
      (sql/format {:update :builds
                   :set updates
                   :where [:= :id build-id]}))))

(defn update-build-workspace!
  "Set the workspace path for a build."
  [ds build-id workspace]
  (jdbc/execute-one! ds
    (sql/format {:update :builds
                 :set {:workspace workspace}
                 :where [:= :id build-id]})))

(defn save-stage-result!
  "Save a stage result to the database."
  [ds build-id stage-result]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-stages
                   :values [{:id id
                             :build-id build-id
                             :stage-name (:stage-name stage-result)
                             :status (name (:stage-status stage-result))
                             :started-at (:started-at stage-result)
                             :completed-at (:completed-at stage-result)}]}))))

(defn save-step-result!
  "Save a step result to the database."
  [ds build-id stage-name step-result]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-steps
                   :values [{:id id
                             :build-id build-id
                             :stage-name stage-name
                             :step-name (:step-name step-result)
                             :status (name (:step-status step-result))
                             :exit-code (:exit-code step-result)
                             :stdout (:stdout step-result)
                             :stderr (:stderr step-result)
                             :started-at (:started-at step-result)
                             :completed-at (:completed-at step-result)}]}))))

(defn save-git-info!
  "Save git metadata for a build. Called when :git-info is present in build result."
  [ds build-id git-info]
  (when git-info
    (jdbc/execute-one! ds
      (sql/format {:update :builds
                   :set {:git-branch       (:branch git-info)
                         :git-commit       (:commit git-info)
                         :git-commit-short (:commit-short git-info)
                         :git-author       (:author git-info)
                         :git-message      (:message git-info)}
                   :where [:= :id build-id]}))))

(defn save-build-result!
  "Persist a complete build result (stages, steps, and git info) to the database."
  [ds build-result]
  (let [build-id (:build-id build-result)]
    ;; Update build status
    (update-build-status! ds build-id (:build-status build-result)
                          :started-at (:started-at build-result)
                          :completed-at (:completed-at build-result))
    ;; Save git info if present
    (save-git-info! ds build-id (:git-info build-result))
    ;; Save pipeline source (chengisfile or server)
    (when-let [ps (:pipeline-source build-result)]
      (jdbc/execute-one! ds
        (sql/format {:update :builds
                     :set {:pipeline-source ps}
                     :where [:= :id build-id]})))
    ;; Save stages and their steps
    (doseq [stage (:stage-results build-result)]
      (save-stage-result! ds build-id stage)
      (doseq [step (:step-results stage)]
        (save-step-result! ds build-id (:stage-name stage) step)))))

(defn add-build-log!
  "Add a log entry for a build."
  [ds build-id level source message]
  (jdbc/execute-one! ds
    (sql/format {:insert-into :build-logs
                 :values [{:build-id build-id
                           :level (name level)
                           :source source
                           :message message}]})))

(defn get-build
  "Retrieve a build by ID."
  [ds build-id]
  (normalize-build
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :builds
                   :where [:= :id build-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-by-job-and-number
  "Retrieve a build by job ID and build number."
  [ds job-id build-number]
  (normalize-build
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :builds
                   :where [:and
                           [:= :job-id job-id]
                           [:= :build-number build-number]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-builds
  "List builds, optionally filtered by job ID."
  ([ds]
   (mapv normalize-build
     (jdbc/execute! ds
       (sql/format {:select :*
                    :from :builds
                    :order-by [[:created-at :desc]]
                    :limit 50})
       {:builder-fn rs/as-unqualified-kebab-maps})))
  ([ds job-id]
   (mapv normalize-build
     (jdbc/execute! ds
       (sql/format {:select :*
                    :from :builds
                    :where [:= :job-id job-id]
                    :order-by [[:created-at :desc]]
                    :limit 50})
       {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn get-build-stages
  "Get all stages for a build."
  [ds build-id]
  (mapv normalize-status
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-stages
                   :where [:= :build-id build-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-steps
  "Get all steps for a build."
  [ds build-id]
  (mapv normalize-status
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :build-steps
                   :where [:= :build-id build-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-logs
  "Get logs for a build."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :build-logs
                 :where [:= :build-id build-id]
                 :order-by [[:id :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
