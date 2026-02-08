(ns chengis.engine.retention
  "Automated data retention scheduler.
   Periodically runs cleanup functions to remove expired data:
   audit logs, webhook events, secret access logs, JWT blacklist entries,
   and old workspaces. Config-gated via :retention :enabled."
  (:require [chengis.db.audit-store :as audit-store]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.db.secret-audit :as secret-audit]
            [chengis.engine.cleanup :as cleanup]
            [chengis.metrics :as metrics]
            [chengis.web.auth :as auth]
            [chime.core :as chime]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private retention-scheduler (atom nil))
(defonce ^:private last-run-result (atom nil))

;; ---------------------------------------------------------------------------
;; Retention logic (testable unit)
;; ---------------------------------------------------------------------------

(defn run-retention!
  "Execute all retention cleanup tasks. Returns a summary map."
  [system]
  (let [ds (:db system)
        config (:config system)
        registry (:metrics system)
        retention-config (:retention config)
        audit-days (get retention-config :audit-days 90)
        webhook-days (get retention-config :webhook-events-days 30)
        secret-audit-days (get retention-config :secret-access-days
                            (get retention-config :audit-days 90))]
    (log/info "Running data retention cleanup...")
    (let [results
          {:audit-logs
           (try
             (let [n (audit-store/purge-old! ds audit-days)]
               (when (pos? n)
                 (try (metrics/record-retention-cleaned! registry :audit-logs n)
                      (catch Exception _)))
               n)
             (catch Exception e
               (log/warn "Audit log cleanup failed:" (.getMessage e))
               0))

           :webhook-events
           (try
             (let [n (webhook-log/cleanup-old-events! ds webhook-days)]
               (when (pos? n)
                 (try (metrics/record-retention-cleaned! registry :webhook-events n)
                      (catch Exception _)))
               n)
             (catch Exception e
               (log/warn "Webhook event cleanup failed:" (.getMessage e))
               0))

           :secret-access-log
           (try
             (let [n (secret-audit/cleanup-old-accesses! ds secret-audit-days)]
               (when (pos? n)
                 (try (metrics/record-retention-cleaned! registry :secret-access-log n)
                      (catch Exception _)))
               n)
             (catch Exception e
               (log/warn "Secret audit cleanup failed:" (.getMessage e))
               0))

           :jwt-blacklist
           (try
             (let [n (auth/cleanup-expired-blacklist! ds)]
               (when (pos? n)
                 (try (metrics/record-retention-cleaned! registry :jwt-blacklist n)
                      (catch Exception _)))
               n)
             (catch Exception e
               (log/warn "JWT blacklist cleanup failed:" (.getMessage e))
               0))

           :workspaces
           (try
             (let [result (cleanup/cleanup-workspaces! config)]
               (when (pos? (:cleaned result))
                 (try (metrics/record-retention-cleaned! registry :workspaces (:cleaned result))
                      (catch Exception _)))
               (:cleaned result))
             (catch Exception e
               (log/warn "Workspace cleanup failed:" (.getMessage e))
               0))}

          total (reduce + (vals results))]
      (log/info "Retention cleanup complete:" results)
      (let [summary (assoc results :total total :timestamp (str (Instant/now)))]
        (reset! last-run-result summary)
        summary))))

;; ---------------------------------------------------------------------------
;; Scheduler
;; ---------------------------------------------------------------------------

(defn start-retention!
  "Start the periodic retention scheduler.
   Runs every :retention :interval-hours (default 24h).
   First run after one interval (not immediately on startup)."
  [system]
  (when-let [existing @retention-scheduler]
    (try (.close existing) (catch Exception _))
    (reset! retention-scheduler nil))
  (let [interval-hours (get-in system [:config :retention :interval-hours] 24)
        interval-ms (* interval-hours 3600000)
        start-time (.plus (Instant/now) (Duration/ofMillis interval-ms))
        times (chime/periodic-seq start-time (Duration/ofMillis interval-ms))
        sched (chime/chime-at
                times
                (fn [_time]
                  (try
                    (run-retention! system)
                    (catch Exception e
                      (log/error "Retention scheduler error:" (.getMessage e))))))]
    (reset! retention-scheduler sched)
    (log/info "Retention scheduler started: running every" interval-hours "hours")
    (fn []
      (when-let [s @retention-scheduler]
        (.close s)
        (reset! retention-scheduler nil)
        (log/info "Retention scheduler stopped")))))

(defn stop-retention!
  "Stop the retention scheduler if running."
  []
  (when-let [sched @retention-scheduler]
    (.close sched)
    (reset! retention-scheduler nil)
    (log/info "Retention scheduler stopped")))

(defn running?*
  "Check if the retention scheduler is currently running."
  []
  (some? @retention-scheduler))

(defn last-run
  "Get the result of the last retention run."
  []
  @last-run-result)
