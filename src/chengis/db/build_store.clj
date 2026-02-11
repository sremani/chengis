(ns chengis.db.build-store
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [chengis.db.pagination :as pagination]))

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

(defn- get-root-build-id*
  "Internal: follow the parent chain to find the original build ID.
   Uses a loop to handle arbitrary chain depth."
  [ds build-id]
  (loop [current-id build-id
         depth 0]
    (if (> depth 100)  ;; safety: prevent infinite loops
      current-id
      (let [build (jdbc/execute-one! ds
                    (sql/format {:select [:root-build-id :parent-build-id]
                                 :from :builds
                                 :where [:= :id current-id]})
                    {:builder-fn rs/as-unqualified-kebab-maps})]
        (cond
          ;; If this build already has a root-build-id, use it (fast path)
          (:root-build-id build) (:root-build-id build)
          ;; If no parent, this IS the root
          (nil? (:parent-build-id build)) current-id
          ;; Otherwise follow the parent chain
          :else (recur (:parent-build-id build) (inc depth)))))))

(defn- compute-attempt-info
  "Compute the attempt number and root-build-id for a retry.
   Looks at the parent build to determine the root of the retry chain
   and the next attempt number."
  [ds parent-build-id]
  (let [root-id (get-root-build-id* ds parent-build-id)
        max-result (jdbc/execute-one! ds
                     (sql/format {:select [[[:max :attempt-number] :max-attempt]]
                                  :from :builds
                                  :where [:or
                                          [:= :id root-id]
                                          [:= :root-build-id root-id]]})
                     {:builder-fn rs/as-unqualified-kebab-maps})
        max-attempt (or (:max-attempt max-result) 1)]
    {:root-build-id root-id
     :attempt-number (inc max-attempt)}))

(defn create-build!
  "Create a new build record. Returns the build map.
   When :org-id is provided, stores it for fast org-scoped queries.
   When :parent-build-id is set (retry), automatically computes
   :attempt-number and :root-build-id unless explicitly provided.
   Uses a transaction to prevent build number race conditions —
   SELECT MAX(build_number) and INSERT are atomic."
  [ds {:keys [job-id trigger-type parameters workspace parent-build-id org-id
              attempt-number root-build-id]}]
  (jdbc/with-transaction [tx ds]
    (let [id (util/generate-id)
          build-number (next-build-number tx job-id)
          ;; Auto-compute attempt tracking for retries
          [eff-root-id eff-attempt]
          (if parent-build-id
            (let [provided-root root-build-id
                  computed (when-not (and provided-root attempt-number)
                             (compute-attempt-info tx parent-build-id))
                  root-id (or provided-root (:root-build-id computed) parent-build-id)
                  attempt (or attempt-number (:attempt-number computed) 2)]
              [root-id attempt])
            [root-build-id (or attempt-number 1)])
          row (cond-> {:id id
                       :job-id job-id
                       :build-number build-number
                       :status "queued"
                       :trigger-type (when trigger-type (name trigger-type))
                       :parameters (util/serialize-edn parameters)
                       :workspace workspace
                       :attempt-number eff-attempt}
                parent-build-id (assoc :parent-build-id parent-build-id)
                eff-root-id (assoc :root-build-id eff-root-id)
                org-id (assoc :org-id org-id))]
      (jdbc/execute-one! tx
        (sql/format {:insert-into :builds
                     :values [row]}))
      (normalize-build (assoc row :build-number build-number)))))

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
  "Retrieve a build by ID. When org-id is provided, verifies the build belongs to that org."
  [ds build-id & {:keys [org-id]}]
  (normalize-build
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :builds
                   :where (if org-id
                            [:and [:= :id build-id] [:= :org-id org-id]]
                            [:= :id build-id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-build-by-job-and-number
  "Retrieve a build by job ID and build number.
   When org-id is provided, verifies the build belongs to that org."
  [ds job-id build-number & {:keys [org-id]}]
  (normalize-build
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :builds
                   :where (let [base [:and
                                      [:= :job-id job-id]
                                      [:= :build-number build-number]]]
                            (if org-id
                              (conj base [:= :org-id org-id])
                              base))})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-builds
  "List builds, optionally filtered by job ID and/or org-id.
   Accepts either a job-id string (backward compat) or an options map.
   Options: :job-id, :org-id, :cursor, :limit, :cursor-mode
   When :cursor-mode is true, returns {:items [...] :has-more bool :next-cursor str}
   Otherwise returns a plain vector (backward compat)."
  ([ds]
   (list-builds ds {}))
  ([ds job-id-or-opts]
   (let [opts (if (map? job-id-or-opts)
                job-id-or-opts
                {:job-id job-id-or-opts})
         {:keys [job-id org-id cursor limit cursor-mode]} opts
         limit (or limit 50)
         cursor-data (when cursor (pagination/decode-cursor cursor))
         conditions (cond-> []
                      job-id (conj [:= :job-id job-id])
                      org-id (conj [:= :org-id org-id]))
         base-where (when (seq conditions)
                      (if (= 1 (count conditions))
                        (first conditions)
                        (into [:and] conditions)))
         where (if cursor-data
                 (pagination/apply-cursor-where base-where cursor-data :created-at :id :desc)
                 base-where)
         ;; Fetch limit+1 to detect has-more
         fetch-limit (if cursor-mode (inc limit) limit)
         rows (mapv normalize-build
                (jdbc/execute! ds
                  (sql/format (cond-> {:select :*
                                       :from :builds
                                       :order-by [[:created-at :desc] [:id :desc]]
                                       :limit fetch-limit}
                                where (assoc :where where)))
                  {:builder-fn rs/as-unqualified-kebab-maps}))]
     (if cursor-mode
       (pagination/paginated-response rows limit :id :created-at)
       ;; Backward compat: return plain vector, truncated to limit
       (vec (take limit rows))))))

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

;; --- Statistics & History ---

(defn get-build-stats
  "Get build statistics for a job (or all jobs if job-id is nil).
   When :org-id is provided, scopes to that organization.
   Accepts a job-id string (backward compat) or an options map {:job-id :org-id}.
   Returns {:total N :success N :failure N :aborted N :success-rate 0.85}"
  ([ds] (get-build-stats ds {}))
  ([ds opts-or-job-id]
   (let [opts (if (or (nil? opts-or-job-id) (string? opts-or-job-id))
                {:job-id opts-or-job-id}
                opts-or-job-id)
         {:keys [job-id org-id]} opts
         builds (cond
                  job-id (list-builds ds {:job-id job-id :org-id org-id})
                  :else  (list-builds ds (if org-id {:org-id org-id} {})))
         total (count builds)
         success (count (filter #(= :success (:status %)) builds))
         failure (count (filter #(= :failure (:status %)) builds))
         aborted (count (filter #(= :aborted (:status %)) builds))
         success-rate (if (pos? total) (double (/ success total)) 0.0)]
     {:total total
      :success success
      :failure failure
      :aborted aborted
      :success-rate success-rate})))

(defn get-recent-build-history
  "Get the last N builds for a job (or all jobs), for chart rendering.
   When :org-id is provided, scopes to that organization.
   Accepts a job-id string (backward compat) or opts map {:job-id :org-id}.
   Returns a seq of {:id :build-number :status :started-at :completed-at}."
  ([ds limit] (get-recent-build-history ds nil limit))
  ([ds job-id-or-opts limit]
   (let [opts (if (or (nil? job-id-or-opts) (string? job-id-or-opts))
                {:job-id job-id-or-opts}
                job-id-or-opts)
         {:keys [job-id org-id]} opts
         conditions (cond-> []
                      job-id (conj [:= :job-id job-id])
                      org-id (conj [:= :org-id org-id]))
         where (when (seq conditions)
                 (if (= 1 (count conditions))
                   (first conditions)
                   (into [:and] conditions)))
         base-query (cond-> {:select [:id :build-number :status :started-at :completed-at :job-id]
                              :from :builds
                              :order-by [[:created-at :desc]]
                              :limit limit}
                      where (assoc :where where))]
     (mapv (fn [row]
             (update row :status util/ensure-keyword))
       (jdbc/execute! ds
         (sql/format base-query)
         {:builder-fn rs/as-unqualified-kebab-maps})))))

;; --- Build deduplication ---

(defn find-recent-build-by-commit
  "Find a recent successful or in-progress build for the same job+commit.
   Used for build deduplication: if a matching build exists within the
   time window, the new build can be skipped.
   Formats cutoff as 'YYYY-MM-DD HH:MM:SS' to match SQLite datetime('now') format.
   Returns the build map or nil if no match found."
  [ds job-id git-commit window-minutes]
  (when (and (seq git-commit) (pos? window-minutes))
    (let [cutoff-instant (.minus (java.time.Instant/now)
                                 (java.time.Duration/ofMinutes window-minutes))
          ;; Format as 'YYYY-MM-DD HH:MM:SS' to match SQLite's datetime('now') format
          cutoff (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
                          (.atZone cutoff-instant (java.time.ZoneOffset/UTC)))]
      (normalize-build
        (jdbc/execute-one! ds
          (sql/format {:select :*
                       :from :builds
                       :where [:and
                               [:= :job-id job-id]
                               [:= :git-commit git-commit]
                               [:in :status ["success" "running" "queued"]]
                               [:> :created-at cutoff]]
                       :order-by [[:created-at :desc]]
                       :limit 1})
          {:builder-fn rs/as-unqualified-kebab-maps})))))

;; --- Attempt tracking ---

(defn get-root-build-id
  "Follow the parent chain to find the original (root) build ID.
   Returns nil if the build has no parent (is itself the root)."
  [ds build-id]
  (let [build (jdbc/execute-one! ds
                (sql/format {:select [:root-build-id :parent-build-id]
                             :from :builds
                             :where [:= :id build-id]})
                {:builder-fn rs/as-unqualified-kebab-maps})]
    (cond
      (:root-build-id build) (:root-build-id build)
      (:parent-build-id build) (get-root-build-id* ds build-id)
      :else nil)))

(defn list-attempts
  "List all builds in a retry chain, ordered by attempt_number.
   Takes the root-build-id (the original build that started the chain).
   Returns the root build plus all retries sharing that root."
  [ds root-build-id]
  (mapv normalize-build
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :builds
                   :where [:or
                           [:= :id root-build-id]
                           [:= :root-build-id root-build-id]]
                   :order-by [[:attempt-number :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))
