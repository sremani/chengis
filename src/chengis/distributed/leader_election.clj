(ns chengis.distributed.leader-election
  "Leader election via PostgreSQL advisory locks for multi-master HA.

   PostgreSQL: Uses `pg_try_advisory_lock(bigint)` — session-scoped, non-blocking.
   Lock auto-releases on connection drop (crash = instant failover).

   SQLite: All locks granted unconditionally (single master assumed).

   Usage:
     (start-leader-loop! ds 100001 \"queue-processor\"
       #(start-processor! system)
       #(stop-processor!)
       15000)

   Returns a stop function to shut down the leader loop.

   Lock IDs:
     100001 — queue-processor
     100002 — orphan-monitor
     100003 — retention-scheduler"
  (:require [chengis.db.connection :as conn]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Advisory lock primitives
;; ---------------------------------------------------------------------------

(defn sqlite?
  "Returns true if the datasource is SQLite."
  [ds]
  (= :sqlite (conn/datasource-type ds)))

(defn try-acquire!
  "Attempt to acquire an advisory lock. Non-blocking.
   Returns true if lock acquired, false otherwise.
   SQLite: always returns true (single master assumed)."
  [ds lock-id]
  (if (sqlite? ds)
    true
    (try
      (let [result (jdbc/execute-one! ds
                     ["SELECT pg_try_advisory_lock(?) AS acquired" lock-id])]
        (boolean (:acquired result)))
      (catch Exception e
        (log/warn "Advisory lock acquisition failed for lock" lock-id ":" (.getMessage e))
        false))))

(defn release!
  "Release an advisory lock. Idempotent — no error if lock not held.
   SQLite: no-op."
  [ds lock-id]
  (when-not (sqlite? ds)
    (try
      (jdbc/execute-one! ds
        ["SELECT pg_advisory_unlock(?) AS released" lock-id])
      (catch Exception e
        (log/warn "Advisory lock release failed for lock" lock-id ":" (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; Leader loop
;; ---------------------------------------------------------------------------

(defn start-leader-loop!
  "Start a background leader election loop for a singleton service.

   Polls `try-acquire!` every `poll-ms` milliseconds.
   When leadership is acquired: calls `start-fn` (once).
   When leadership is lost (e.g., connection recycled): calls `stop-fn`, then retries.
   On shutdown: calls `stop-fn` if currently leading.

   Returns a map with:
     :stop! — function to stop the leader loop
     :leading? — atom that is true when this instance is the leader"
  [ds lock-id service-name start-fn stop-fn poll-ms]
  (let [running? (atom true)
        leading? (atom false)
        thread (Thread.
                 (fn []
                   (log/info "Leader loop started for" service-name
                             "(lock-id:" lock-id ", poll:" poll-ms "ms)")
                   (while @running?
                     (try
                       (let [acquired (try-acquire! ds lock-id)]
                         (cond
                           ;; Newly acquired leadership
                           (and acquired (not @leading?))
                           (do
                             (log/info "Leadership acquired for" service-name)
                             (reset! leading? true)
                             (try
                               (start-fn)
                               (catch Exception e
                                 (log/error "Failed to start" service-name ":"
                                            (.getMessage e))
                                 (reset! leading? false))))

                           ;; Leadership lost (was leading, now can't acquire)
                           (and (not acquired) @leading?)
                           (do
                             (log/warn "Leadership lost for" service-name)
                             (reset! leading? false)
                             (try
                               (stop-fn)
                               (catch Exception e
                                 (log/error "Failed to stop" service-name
                                            "after leadership loss:" (.getMessage e)))))

                           ;; else: steady state (still leading or still not leading)
                           ))
                       (catch Exception e
                         (log/error "Leader loop error for" service-name ":" (.getMessage e))))
                     ;; Sleep between polls
                     (try
                       (Thread/sleep poll-ms)
                       (catch InterruptedException _
                         ;; Thread interrupted (shutdown), exit loop
                         (reset! running? false))))
                   ;; Cleanup on exit
                   (when @leading?
                     (log/info "Leader loop shutting down for" service-name "— releasing leadership")
                     (reset! leading? false)
                     (try
                       (stop-fn)
                       (catch Exception e
                         (log/error "Failed to stop" service-name "during shutdown:"
                                    (.getMessage e))))
                     (release! ds lock-id))
                   (log/info "Leader loop stopped for" service-name))
                 (str "chengis-leader-" service-name))]
    (.setDaemon thread true)
    (.start thread)
    {:stop! (fn []
              (reset! running? false)
              (.interrupt thread)
              (try
                (.join thread 5000)
                (catch InterruptedException _)))
     :leading? leading?}))

(defn stop-leader-loop!
  "Stop a running leader loop. Accepts the map returned by `start-leader-loop!`."
  [{:keys [stop!]}]
  (when stop!
    (stop!)))
