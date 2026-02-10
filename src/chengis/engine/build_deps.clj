(ns chengis.engine.build-deps
  "Build dependency engine — triggers downstream jobs when upstream completes.

   When a build completes, checks if any jobs depend on the completed job.
   If the completion status matches the trigger condition (success/failure/any),
   triggers a new build for each downstream job."
  (:require [chengis.db.dependency-store :as dep-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.feature-flags :as feature-flags]
            [chengis.engine.events :as events]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Trigger evaluation
;; ---------------------------------------------------------------------------

(defn should-trigger-dependent?
  "Check if a dependent job should be triggered based on the build's status.
   trigger-on can be 'success', 'failure', or 'any'."
  [trigger-on build-status]
  (let [status (keyword build-status)]
    (case trigger-on
      "success" (= status :success)
      "failure" (= status :failure)
      "any"     (contains? #{:success :failure} status)
      false)))

;; ---------------------------------------------------------------------------
;; Downstream trigger
;; ---------------------------------------------------------------------------

(defn trigger-downstream-jobs!
  "After a build completes, trigger any downstream dependent jobs.
   Returns a seq of triggered build records (or empty if none).

   Arguments:
     system       - system map with :db, :config
     completed-build - map with :build-id, :job-id, :org-id, :status
     build-executor  - ExecutorService for running builds"
  [system completed-build build-executor]
  (let [ds (:db system)
        config (:config system)
        {:keys [build-id job-id org-id status]} completed-build]
    (when (feature-flags/enabled? config :build-dependencies)
      (let [dependents (dep-store/list-dependents ds job-id :org-id org-id)]
        (when (seq dependents)
          (log/info "Build" build-id "completed with" (name status)
                    "— checking" (count dependents) "dependent jobs")
          (doall
            (for [dep dependents
                  :when (should-trigger-dependent? (:trigger-on dep) status)]
              (let [target-job-id (:job-id dep)
                    target-job (job-store/get-job-by-id ds target-job-id :org-id org-id)]
                (if-not target-job
                  (do (log/warn "Dependent job" target-job-id "not found, skipping")
                      nil)
                  (let [target-build (build-store/create-build! ds
                                       {:job-id target-job-id
                                        :trigger-type :dependency
                                        :org-id org-id
                                        :parameters {:triggered-by-build build-id
                                                     :triggered-by-job job-id}})
                        target-build-id (:id target-build)]
                    ;; Record the trigger relationship
                    (dep-store/record-trigger! ds
                      {:source-build-id build-id
                       :source-job-id job-id
                       :target-build-id target-build-id
                       :target-job-id target-job-id
                       :org-id (or org-id "default-org")
                       :trigger-status (name status)})
                    (log/info "Triggered dependent job" (:name target-job)
                              "build" (:build-number target-build)
                              "from build" build-id)
                    ;; Publish event
                    (events/publish! {:build-id target-build-id
                                     :event-type :build-queued
                                     :timestamp (str (java.time.Instant/now))
                                     :data {:job-name (:name target-job)
                                            :trigger-type :dependency
                                            :triggered-by build-id}})
                    target-build))))))))))

;; ---------------------------------------------------------------------------
;; Dependency management
;; ---------------------------------------------------------------------------

(defn add-dependency!
  "Add a job dependency with cycle detection.
   Returns {:ok dep-map} or {:error message}."
  [ds {:keys [job-id depends-on-job-id org-id trigger-on]
       :as dep-config}]
  (cond
    (= job-id depends-on-job-id)
    {:error "A job cannot depend on itself"}

    (dep-store/has-cycle? ds job-id depends-on-job-id :org-id org-id)
    {:error "Adding this dependency would create a cycle"}

    :else
    (try
      {:ok (dep-store/create-dependency! ds dep-config)}
      (catch Exception e
        (if (re-find #"UNIQUE" (.getMessage e))
          {:error "This dependency already exists"}
          {:error (.getMessage e)})))))

(defn get-dependency-graph
  "Get the full dependency graph for an org as an adjacency list.
   Returns {job-id [depends-on-job-id ...]}"
  [ds & {:keys [org-id]}]
  (let [all-deps (jdbc/execute! ds
                   (sql/format
                     (cond-> {:select [:job-id :depends-on-job-id]
                              :from :job-dependencies}
                       org-id (assoc :where [:= :org-id org-id])))
                   {:builder-fn rs/as-unqualified-kebab-maps})]
    (reduce (fn [graph dep]
              (update graph (:job-id dep) (fnil conj []) (:depends-on-job-id dep)))
            {} all-deps)))
