(ns chengis.engine.scheduler
  "Cron-based job scheduling using chime.
   Supports triggering builds on a schedule defined in job triggers."
  (:require [chime.core :as chime]
            [chengis.db.job-store :as job-store]
            [chengis.engine.build-runner :as build-runner]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]
           [java.util.concurrent Executors Callable]))

;; --- Simple cron-like scheduling ---
;; We support interval-based scheduling for simplicity.
;; Format: {:type :interval :minutes N} or {:type :cron :expression "..."}

;; Bounded thread pool to prevent unbounded future creation from scheduled builds.
;; Limits concurrent scheduled builds to 4. Builds exceeding this are queued.
(defonce ^:private scheduler-pool (Executors/newFixedThreadPool 4))

(defn- next-intervals
  "Generate a sequence of instants at a fixed interval from now."
  [interval-minutes]
  (let [now (Instant/now)
        duration (Duration/ofMinutes interval-minutes)]
    (chime/periodic-seq (.plus now duration) duration)))

(defn- trigger-build-for-job!
  "Trigger and execute a build for a job.
   Uses a bounded thread pool to prevent unbounded thread creation."
  [system job trigger-type]
  (log/info "Scheduled build triggered for" (:name job))
  (.submit scheduler-pool
    ^Callable (fn []
                (try
                  (let [result (build-runner/execute-build! system job trigger-type)]
                    (log/info "Scheduled build #" (:build-number result) "for" (:name job)
                              "completed:" (name (:build-status result))))
                  (catch Exception e
                    (log/error e "Scheduled build failed for" (:name job)))))))

(defn start-scheduler
  "Start the scheduler. Scans all jobs for trigger configurations
   and sets up recurring schedules. Returns a map of job-id -> closeable schedule.

   NOTE: The scheduler is a system-level component that intentionally lists jobs
   across ALL organizations (no org-id filter). This is correct — it functions
   like a cron daemon that must trigger scheduled builds for every tenant. Org
   isolation is enforced at the build level (each build carries its job's org-id)."
  [system]
  (let [ds (:db system)
        jobs (job-store/list-jobs ds)
        schedules (atom {})]
    (doseq [job jobs]
      (let [triggers (:triggers job)]
        (when (seq triggers)
          (doseq [trigger triggers]
            (when (= :interval (:type trigger))
              (let [minutes (:minutes trigger)
                    schedule (chime/chime-at
                               (next-intervals minutes)
                               (fn [_time]
                                 (trigger-build-for-job! system job :cron))
                               {:error-handler (fn [e]
                                                 (log/error e "Scheduler error for" (:name job)))})]
                (swap! schedules update (:id job) (fnil conj []) schedule)
                (log/info "Scheduled" (:name job) "every" minutes "minutes")))))))
    schedules))

(defn stop-scheduler
  "Stop all scheduled jobs. Each job may have multiple schedules (vector per job-id)."
  [schedules]
  (doseq [[job-id job-schedules] @schedules]
    (doseq [schedule (if (vector? job-schedules) job-schedules [job-schedules])]
      (.close schedule))
    (log/info "Stopped" (count (if (vector? job-schedules) job-schedules [job-schedules]))
              "schedule(s) for job" job-id))
  (reset! schedules {}))
