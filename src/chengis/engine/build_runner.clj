(ns chengis.engine.build-runner
  "Centralized build execution lifecycle.
   Handles: create-build -> run-build -> update workspace -> save results.
   All callers (CLI, web, webhook, scheduler) use this module.
   Also manages the active-builds registry for cancellation support."
  (:require [chengis.db.build-store :as build-store]
            [chengis.engine.executor :as executor]
            [chengis.engine.events :as events]
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
        pipeline (:pipeline job)
        build-record (build-store/create-build! ds
                       {:job-id (:id job)
                        :trigger-type trigger-type
                        :parameters parameters})
        build-id (:id build-record)
        build-number (:build-number build-record)
        cancelled-atom (atom false)]
    (log/info "Build #" build-number "triggered for" (:name job)
              "(id:" build-id "trigger:" (name trigger-type) ")")
    (register-build! build-id cancelled-atom)
    (try
      (let [result (executor/run-build system pipeline
                     (cond-> {:job-id (:id job)
                              :build-number build-number
                              :cancelled? cancelled-atom}
                       event-fn   (assoc :event-fn event-fn)
                       parameters (assoc :parameters parameters)))]
        (persist-result! ds build-id result)
        (log/info "Build #" build-number "for" (:name job)
                  "completed:" (name (:build-status result)))
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
        pipeline (:pipeline job)
        build-id (:id build-record)
        build-number (:build-number build-record)
        cancelled-atom (atom false)]
    (register-build! build-id cancelled-atom)
    (try
      (let [result (executor/run-build system pipeline
                     (cond-> {:job-id (:id job)
                              :build-number build-number
                              :cancelled? cancelled-atom}
                       event-fn   (assoc :event-fn event-fn)
                       parameters (assoc :parameters parameters)))]
        (persist-result! ds build-id result)
        (assoc result :build-id build-id :build-number build-number))
      (finally
        (deregister-build! build-id)))))
