(ns chengis.engine.build-runner
  "Centralized build execution lifecycle.
   Handles: create-build -> run-build -> update workspace -> save results.
   All callers (CLI, web, webhook, scheduler) use this module.
   Also manages the active-builds registry for cancellation support."
  (:require [chengis.db.build-store :as build-store]
            [chengis.engine.executor :as executor]
            [chengis.engine.events :as events]
            [chengis.engine.scm-status :as scm-status]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util.concurrent Executors]))

;; Bounded thread pool for build execution (prevents resource exhaustion).
;; Shared by web handlers, webhook, and scheduler.
(defonce build-executor (Executors/newFixedThreadPool 4))

;; Active builds registry: maps build-id -> {:thread Thread, :cancelled? (atom false)}
;; Used for cancellation support.
(defonce active-builds (atom {}))

(defn- register-build!
  "Register a build as active (for cancellation tracking)."
  [build-id cancelled-atom]
  (swap! active-builds assoc build-id
         {:thread (Thread/currentThread)
          :cancelled? cancelled-atom}))

(defn- deregister-build!
  "Remove a build from the active registry."
  [build-id]
  (swap! active-builds dissoc build-id))

(defn cancel-build!
  "Cancel a running build. Sets the cancelled flag and interrupts the thread.
   Returns true if the build was found and cancelled, false otherwise."
  [build-id]
  (if-let [entry (get @active-builds build-id)]
    (do
      (log/info "Cancelling build:" build-id)
      (reset! (:cancelled? entry) true)
      (.interrupt (:thread entry))
      true)
    false))

(defn get-active-build-ids
  "Return the set of currently active build IDs."
  []
  (set (keys @active-builds)))

(defn build-active?
  "Check if a build is currently active."
  [build-id]
  (contains? @active-builds build-id))

(defn- persist-result!
  "Persist build results: update workspace, save stages/steps/git/pipeline-source."
  [ds build-id result]
  (build-store/update-build-workspace! ds build-id (:workspace result))
  (build-store/save-build-result! ds (assoc result :build-id build-id)))

(defn- report-scm-status!
  "Report build status to SCM if git-info is available.
   Extracts commit-sha and repo-url from the build result's git-info."
  [system build-id job-id result build-status description]
  (try
    (when-let [git-info (:git-info result)]
      (let [commit-sha (or (:commit git-info) (:sha git-info))
            repo-url   (or (:repo-url git-info) (:remote-url git-info))]
        (when (and (seq commit-sha) (seq repo-url))
          (scm-status/report! system
            {:commit-sha commit-sha
             :repo-url   repo-url
             :build-id   build-id
             :job-id     job-id}
            build-status description))))
    (catch Exception e
      (log/debug "SCM status report failed:" (.getMessage e)))))

(defn execute-build!
  "Full build lifecycle: create record, execute, persist results.
   Used by CLI and scheduler (synchronous callers).

   Arguments:
     system       - system map with :config and :db
     job          - job map from job-store (must have :id, :pipeline, :name)
     trigger-type - keyword (:manual, :cron, :scm, :retry)
     opts         - optional map:
                    :event-fn    - fn for live event streaming (SSE)
                    :parameters  - build parameters map

   Returns the build result map (augmented with :build-id and :build-number)."
  [system job trigger-type & [{:keys [event-fn parameters]}]]
  (let [ds (:db system)
        registry (:metrics system)
        pipeline (:pipeline job)
        build-record (build-store/create-build! ds
                       (cond-> {:job-id (:id job)
                                :trigger-type trigger-type
                                :parameters parameters}
                         (:org-id job) (assoc :org-id (:org-id job))))
        build-id (:id build-record)
        build-number (:build-number build-record)
        cancelled-atom (atom false)
        start-ns (System/nanoTime)]
    (log/info "Build #" build-number "triggered for" (:name job)
              "(id:" build-id "trigger:" (name trigger-type) ")")
    (try (metrics/record-build-start! registry)
         (catch Exception e (log/debug "Failed to record build-start metric:" (.getMessage e))))
    (register-build! build-id cancelled-atom)
    (try
      (let [result (executor/run-build system pipeline
                     (cond-> {:job-id (:id job)
                              :build-number build-number
                              :cancelled? cancelled-atom}
                       (:org-id job) (assoc :org-id (:org-id job))
                       event-fn   (assoc :event-fn event-fn)
                       parameters (assoc :parameters parameters)))]
        (persist-result! ds build-id result)
        (let [duration-s (/ (double (- (System/nanoTime) start-ns)) 1e9)]
          (try (metrics/record-build-end! registry (:build-status result) duration-s)
               (catch Exception e (log/debug "Failed to record build-end metric:" (.getMessage e)))))
        (log/info "Build #" build-number "for" (:name job)
                  "completed:" (name (:build-status result)))
        (report-scm-status! system build-id (:id job) result
          (:build-status result) (str "Build #" build-number " " (name (:build-status result))))
        (assoc result :build-id build-id :build-number build-number))
      (finally
        (deregister-build! build-id)))))

(defn execute-build-for-record!
  "Execute a build for a pre-created build record.
   Used by web handlers and webhook (where build-id is needed before execution,
   e.g. for the HTTP redirect response).

   Arguments:
     system       - system map with :config and :db
     job          - job map from job-store
     build-record - already-created build record (from build-store/create-build!)
     opts         - optional map:
                    :event-fn    - fn for live event streaming (SSE)
                    :parameters  - build parameters map

   Returns the build result map."
  [system job build-record & [{:keys [event-fn parameters]}]]
  (let [ds (:db system)
        registry (:metrics system)
        pipeline (:pipeline job)
        build-id (:id build-record)
        build-number (:build-number build-record)
        cancelled-atom (atom false)
        start-ns (System/nanoTime)]
    (try (metrics/record-build-start! registry)
         (catch Exception e (log/debug "Failed to record build-start metric:" (.getMessage e))))
    (register-build! build-id cancelled-atom)
    (try
      (let [result (executor/run-build system pipeline
                     (cond-> {:job-id (:id job)
                              :build-number build-number
                              :cancelled? cancelled-atom}
                       (:org-id job) (assoc :org-id (:org-id job))
                       event-fn   (assoc :event-fn event-fn)
                       parameters (assoc :parameters parameters)))]
        (persist-result! ds build-id result)
        (let [duration-s (/ (double (- (System/nanoTime) start-ns)) 1e9)]
          (try (metrics/record-build-end! registry (:build-status result) duration-s)
               (catch Exception e (log/debug "Failed to record build-end metric:" (.getMessage e)))))
        (report-scm-status! system build-id (:id job) result
          (:build-status result) (str "Build #" build-number " " (name (:build-status result))))
        (assoc result :build-id build-id :build-number build-number))
      (finally
        (deregister-build! build-id)))))
