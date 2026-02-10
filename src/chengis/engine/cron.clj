(ns chengis.engine.cron
  "Database-backed cron scheduler with missed-run detection.
   Polls the cron_schedules table for due schedules and triggers builds.

   Cron expression format (5-field):
     minute hour day-of-month month day-of-week
     e.g., '0 2 * * *' = every day at 2:00 AM
           '*/15 * * * *' = every 15 minutes
           '0 0 * * 1' = every Monday at midnight

   Uses a polling loop (same pattern as retention.clj, analytics.clj)
   with HA leader election when multiple instances are running."
  (:require [chengis.db.cron-store :as cron-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.job-store :as job-store]
            [chengis.feature-flags :as feature-flags]
            [chengis.engine.events :as events]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [chime.core :as chime])
  (:import [java.time Instant Duration ZonedDateTime ZoneId LocalDateTime]
           [java.time.temporal ChronoField]))

;; ---------------------------------------------------------------------------
;; Cron expression parsing (5-field standard cron)
;; ---------------------------------------------------------------------------

(defn- parse-cron-field
  "Parse a single cron field into a set of matching values.
   Supports: *, N, N-M, */N, N/M, comma-separated.
   min-val and max-val define the valid range."
  [field min-val max-val]
  (cond
    (= field "*")
    (set (range min-val (inc max-val)))

    (re-matches #"\*/\d+" field)
    (let [step (Long/parseLong (subs field 2))]
      (set (range min-val (inc max-val) step)))

    (re-matches #"\d+-\d+" field)
    (let [[_ start end] (re-find #"(\d+)-(\d+)" field)]
      (set (range (Long/parseLong start) (inc (Long/parseLong end)))))

    (re-matches #"\d+/\d+" field)
    (let [[_ start step] (re-find #"(\d+)/(\d+)" field)]
      (set (range (Long/parseLong start) (inc max-val) (Long/parseLong step))))

    (re-find #"," field)
    (reduce into #{}
      (map #(parse-cron-field (str/trim %) min-val max-val)
           (str/split field #",")))

    :else
    #{(Long/parseLong field)}))

(defn parse-cron-expression
  "Parse a 5-field cron expression into field sets.
   Returns {:minute #{...} :hour #{...} :day #{...} :month #{...} :dow #{...}}
   or nil on parse error."
  [expr]
  (try
    (let [fields (str/split (str/trim expr) #"\s+")]
      (when (= 5 (count fields))
        {:minute (parse-cron-field (nth fields 0) 0 59)
         :hour   (parse-cron-field (nth fields 1) 0 23)
         :day    (parse-cron-field (nth fields 2) 1 31)
         :month  (parse-cron-field (nth fields 3) 1 12)
         :dow    (parse-cron-field (nth fields 4) 0 6)}))
    (catch Exception e
      (log/warn "Failed to parse cron expression:" expr (.getMessage e))
      nil)))

(defn cron-matches?
  "Check if a ZonedDateTime matches a parsed cron expression."
  [parsed-cron ^ZonedDateTime dt]
  (and (contains? (:minute parsed-cron) (.getMinute dt))
       (contains? (:hour parsed-cron) (.getHour dt))
       (contains? (:day parsed-cron) (.getDayOfMonth dt))
       (contains? (:month parsed-cron) (.getMonthValue dt))
       ;; Day of week: Java 1=Monday..7=Sunday, cron 0=Sunday..6=Saturday
       (let [java-dow (.getValue (.getDayOfWeek dt))
             cron-dow (if (= java-dow 7) 0 java-dow)]
         (contains? (:dow parsed-cron) cron-dow))))

(defn next-run-time
  "Calculate the next run time from a given time for a cron expression.
   Steps forward minute-by-minute (up to 1 year ahead).
   Returns ISO-8601 string or nil if no match found."
  [cron-expression ^String from-time ^String timezone]
  (when-let [parsed (parse-cron-expression cron-expression)]
    (let [zone (ZoneId/of timezone)
          start (-> (Instant/parse from-time)
                    (.atZone zone)
                    (.plusMinutes 1)
                    ;; Reset seconds to 0
                    (.withSecond 0)
                    (.withNano 0))
          ;; Search up to 1 year ahead (525960 minutes)
          max-steps 525960]
      (loop [dt start
             steps 0]
        (if (>= steps max-steps)
          nil
          (if (cron-matches? parsed dt)
            (str (.toInstant dt))
            (recur (.plusMinutes dt 1) (inc steps))))))))

;; ---------------------------------------------------------------------------
;; Schedule processing
;; ---------------------------------------------------------------------------

(defn process-due-schedules!
  "Find and process all schedules that are due for execution.
   Returns the number of schedules processed."
  [system]
  (let [ds (:db system)
        config (:config system)
        now (Instant/now)
        now-str (str now)
        missed-threshold-min (get-in config [:cron :missed-run-threshold-minutes] 10)]
    (when (feature-flags/enabled? config :cron-scheduling)
      (let [due-schedules (cron-store/get-due-schedules ds now-str)]
        (doseq [schedule due-schedules]
          (let [next-run-at (:next-run-at schedule)
                scheduled-time (Instant/parse next-run-at)
                delay-minutes (/ (.toMillis (Duration/between scheduled-time now)) 60000.0)
                missed? (> delay-minutes missed-threshold-min)]
            (if missed?
              (do
                (log/warn "Cron schedule" (:id schedule)
                          "missed by" (format "%.1f" delay-minutes) "minutes")
                (cron-store/mark-schedule-missed! ds (:id schedule))
                (cron-store/record-cron-run! ds
                  {:schedule-id (:id schedule)
                   :job-id (:job-id schedule)
                   :org-id (:org-id schedule)
                   :scheduled-at next-run-at
                   :status :missed
                   :missed true
                   :error "Run missed — exceeded threshold"}))
              ;; Trigger the build
              (let [job (job-store/get-job-by-id ds (:job-id schedule)
                          :org-id (:org-id schedule))]
                (if-not job
                  (do
                    (log/warn "Cron schedule" (:id schedule)
                              "references missing job" (:job-id schedule))
                    (cron-store/record-cron-run! ds
                      {:schedule-id (:id schedule)
                       :job-id (:job-id schedule)
                       :org-id (:org-id schedule)
                       :scheduled-at next-run-at
                       :triggered-at now-str
                       :status :error
                       :error "Job not found"}))
                  (try
                    (let [build (build-store/create-build! ds
                                  {:job-id (:job-id schedule)
                                   :trigger-type :cron
                                   :org-id (:org-id schedule)
                                   :parameters (merge (:parameters schedule)
                                                      {:cron-schedule-id (:id schedule)
                                                       :cron-expression (:cron-expression schedule)})})]
                      (log/info "Cron triggered build #" (:build-number build)
                                "for job" (:name job))
                      ;; Publish event
                      (events/publish! {:build-id (:id build)
                                        :event-type :build-queued
                                        :timestamp now-str
                                        :data {:job-name (:name job)
                                               :trigger-type :cron}})
                      ;; Record run
                      (cron-store/record-cron-run! ds
                        {:schedule-id (:id schedule)
                         :job-id (:job-id schedule)
                         :build-id (:id build)
                         :org-id (:org-id schedule)
                         :scheduled-at next-run-at
                         :triggered-at now-str
                         :status :triggered}))
                    (catch Exception e
                      (log/error "Failed to trigger cron build:" (.getMessage e))
                      (cron-store/record-cron-run! ds
                        {:schedule-id (:id schedule)
                         :job-id (:job-id schedule)
                         :org-id (:org-id schedule)
                         :scheduled-at next-run-at
                         :triggered-at now-str
                         :status :error
                         :error (.getMessage e)}))))))
            ;; Compute and set next run time
            (let [new-next (next-run-time (:cron-expression schedule)
                                          now-str
                                          (or (:timezone schedule) "UTC"))]
              (cron-store/mark-schedule-run! ds (:id schedule)
                {:last-run-at now-str
                 :next-run-at new-next
                 :status (if missed? :missed :triggered)}))))
        (count due-schedules)))))

;; ---------------------------------------------------------------------------
;; Scheduler lifecycle (Chime-based polling)
;; ---------------------------------------------------------------------------

(defonce ^:private scheduler-atom (atom nil))

(defn start-cron-scheduler!
  "Start the cron scheduler polling loop."
  [system]
  (let [config (:config system)
        interval-s (get-in config [:cron :poll-interval-seconds] 60)]
    (when (feature-flags/enabled? config :cron-scheduling)
      (log/info "Starting cron scheduler (poll interval:" interval-s "s)")
      (let [sched (chime/chime-at
                    (chime/periodic-seq (Instant/now) (Duration/ofSeconds interval-s))
                    (fn [_time]
                      (try
                        (process-due-schedules! system)
                        (catch Exception e
                          (log/error "Cron scheduler error:" (.getMessage e)))))
                    {:error-handler (fn [e]
                                     (log/error "Cron scheduler error:" (.getMessage e))
                                     true)})]
        (reset! scheduler-atom sched)
        sched))))

(defn stop-cron-scheduler!
  "Stop the cron scheduler polling loop."
  []
  (when-let [sched @scheduler-atom]
    (log/info "Stopping cron scheduler")
    (.close sched)
    (reset! scheduler-atom nil)))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn validate-cron-expression
  "Validate a cron expression. Returns {:valid? bool :error str}."
  [expr]
  (if (str/blank? expr)
    {:valid? false :error "Cron expression is required"}
    (if-let [parsed (parse-cron-expression expr)]
      {:valid? true}
      {:valid? false :error "Invalid cron expression (must be 5 space-separated fields)"})))
