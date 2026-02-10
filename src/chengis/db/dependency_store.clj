(ns chengis.db.dependency-store
  "Persistence for job-to-job build dependencies.
   Jobs can declare dependencies on other jobs. When a dependency completes
   with a matching status (e.g., success), downstream jobs are triggered."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

;; ---------------------------------------------------------------------------
;; Job Dependencies (configuration)
;; ---------------------------------------------------------------------------

(defn create-dependency!
  "Create a dependency: job-id depends on depends-on-job-id.
   trigger-on: 'success' (default), 'failure', 'any'.
   Returns the dependency map."
  [ds {:keys [job-id depends-on-job-id org-id trigger-on]
       :or {org-id "default-org" trigger-on "success"}}]
  (let [id (util/generate-id)
        row {:id id
             :job-id job-id
             :depends-on-job-id depends-on-job-id
             :org-id org-id
             :trigger-on trigger-on}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :job-dependencies
                   :values [row]}))
    row))

(defn list-dependencies
  "List dependencies for a job (what this job depends on).
   When org-id provided, scopes to that org."
  [ds job-id & {:keys [org-id]}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select :*
                         :from :job-dependencies
                         :where [:= :job-id job-id]
                         :order-by [[:created-at :asc]]}
                  org-id (assoc :where [:and [:= :job-id job-id] [:= :org-id org-id]])))
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-dependents
  "List jobs that depend on the given job (downstream dependents).
   This is the reverse lookup: given a completed job, find what to trigger."
  [ds depends-on-job-id & {:keys [org-id]}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select :*
                         :from :job-dependencies
                         :where [:= :depends-on-job-id depends-on-job-id]
                         :order-by [[:created-at :asc]]}
                  org-id (assoc :where [:and [:= :depends-on-job-id depends-on-job-id]
                                            [:= :org-id org-id]])))
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-dependency
  "Get a single dependency by ID. When org-id provided, verifies ownership."
  [ds dep-id & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format (cond-> {:select :*
                         :from :job-dependencies
                         :where [:= :id dep-id]}
                  org-id (assoc :where [:and [:= :id dep-id] [:= :org-id org-id]])))
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn delete-dependency!
  "Remove a dependency. When org-id provided, verifies ownership."
  [ds dep-id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :job-dependencies
                              :where (if org-id
                                       [:and [:= :id dep-id] [:= :org-id org-id]]
                                       [:= :id dep-id])}))]
    (pos? (:next.jdbc/update-count result 0))))

(defn delete-job-dependencies!
  "Remove all dependencies for a job (both directions). Used when deleting a job."
  [ds job-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :job-dependencies
                 :where [:or [:= :job-id job-id] [:= :depends-on-job-id job-id]]})))

;; ---------------------------------------------------------------------------
;; Cycle detection
;; ---------------------------------------------------------------------------

(defn has-cycle?
  "Check if adding a dependency from job-id -> depends-on-job-id would create a cycle.
   Uses BFS from depends-on-job-id to see if it can reach job-id."
  [ds job-id depends-on-job-id & {:keys [org-id]}]
  (loop [queue [depends-on-job-id]
         visited #{}]
    (if (empty? queue)
      false
      (let [current (first queue)]
        (cond
          ;; Found a cycle
          (= current job-id) true
          ;; Already visited
          (visited current) (recur (rest queue) visited)
          ;; Explore dependents of current
          :else
          (let [deps (list-dependencies ds current :org-id org-id)
                next-ids (map :depends-on-job-id deps)]
            (recur (concat (rest queue) next-ids)
                   (conj visited current))))))))

;; ---------------------------------------------------------------------------
;; Dependency Triggers (per-build tracking)
;; ---------------------------------------------------------------------------

(defn record-trigger!
  "Record that a build triggered a downstream build via dependency."
  [ds {:keys [source-build-id source-job-id target-build-id target-job-id
              org-id trigger-status]
       :or {org-id "default-org"}}]
  (let [id (util/generate-id)
        row {:id id
             :source-build-id source-build-id
             :source-job-id source-job-id
             :target-build-id target-build-id
             :target-job-id target-job-id
             :org-id org-id
             :trigger-status trigger-status}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :dependency-triggers
                   :values [row]}))
    row))

(defn get-triggered-builds
  "Get all builds triggered by a source build's completion."
  [ds source-build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :dependency-triggers
                 :where [:= :source-build-id source-build-id]
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-trigger-chain
  "Get the full trigger chain for a build (what triggered it)."
  [ds target-build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :dependency-triggers
                 :where [:= :target-build-id target-build-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
