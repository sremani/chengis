(ns chengis.engine.scheduler
  "Cron-based job scheduling using chime.
   Supports triggering builds on a schedule defined in job triggers."
  (:require [chime.core :as chime]
            [chengis.db.job-store :as job-store]
            [chengis.engine.build-runner :as build-runner]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration ZonedDateTime ZoneId]
           [java.time.temporal ChronoUnit]))

;; --- Simple cron-like scheduling ---
;; We support interval-based scheduling for simplicity.
;; Format: {:type :interval :minutes N} or {:type :cron :expression "..."}

(defn- next-intervals
  "Generate a sequence of instants at a fixed interval from now."
  [interval-minutes]
  (let [now (Instant/now)
        duration (Duration/ofMinutes interval-minutes)]
    (chime/periodic-seq (.plus now duration) duration)))

(defn- trigger-build-for-job!
  "Trigger and execute a build for a job."
  [system job trigger-type]
  (log/info "Scheduled build triggered for" (:name job))
  (future
    (try
      (let [result (build-runner/execute-build! system job trigger-type)]
        (log/info "Scheduled build #" (:build-number result) "for" (:name job)
                  "completed:" (name (:build-status result))))
      (catch Exception e
        (log/error e "Scheduled build failed for" (:name job))))))

(defn start-scheduler
  "Start the scheduler. Scans all jobs for trigger configurations
   and sets up recurring schedules. Returns a map of job-id -> closeable schedule."
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
                (swap! schedules assoc (:id job) schedule)
                (log/info "Scheduled" (:name job) "every" minutes "minutes")))))))
    schedules))

(defn stop-scheduler
  "Stop all scheduled jobs."
  [schedules]
  (doseq [[job-id schedule] @schedules]
    (.close schedule)
    (log/info "Stopped schedule for job" job-id))
  (reset! schedules {}))
