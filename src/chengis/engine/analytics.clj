(ns chengis.engine.analytics
  "Build analytics aggregation engine.
   Computes daily/weekly build and stage statistics from raw build data.
   Runs on a chime-based periodic scheduler (same pattern as retention.clj).
   Feature-flag gated via :build-analytics."
  (:require [chengis.db.analytics-store :as analytics-store]
            [chengis.db.build-store :as build-store]
            [chengis.feature-flags :as ff]
            [chengis.metrics :as metrics]
            [chime.core :as chime]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration LocalDate ZoneOffset]
           [java.time.format DateTimeFormatter]
           [java.time.temporal ChronoUnit]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private analytics-scheduler (atom nil))
(defonce ^:private last-run-result (atom nil))

;; ---------------------------------------------------------------------------
;; Percentile computation
;; ---------------------------------------------------------------------------

(defn compute-percentile
  "Compute the p-th percentile from a sorted seq of numbers.
   Returns nil for empty sequences."
  [sorted-vals p]
  (when (seq sorted-vals)
    (let [n (count sorted-vals)
          idx (min (dec n) (int (Math/floor (* (/ p 100.0) n))))]
      (nth sorted-vals idx))))

;; ---------------------------------------------------------------------------
;; Flakiness score
;; ---------------------------------------------------------------------------

(defn compute-flakiness-score
  "Compute a flakiness score from 0.0 (perfectly stable) to 1.0 (maximum flaky).
   Formula: 1 - |2*success_rate - 1|
   A stage that always passes (1.0) or always fails (0.0) scores 0.0.
   A stage that fails 50% of the time scores 1.0 (max flaky)."
  [success-rate]
  (when success-rate
    (- 1.0 (Math/abs (- (* 2.0 success-rate) 1.0)))))

;; ---------------------------------------------------------------------------
;; Build duration extraction
;; ---------------------------------------------------------------------------

(defn- parse-duration-seconds
  "Compute duration in seconds between two ISO timestamp strings.
   Returns nil if either is nil or parsing fails."
  [started-at completed-at]
  (when (and started-at completed-at)
    (try
      (let [start (Instant/parse (str started-at))
            end (Instant/parse (str completed-at))
            ms (.between ChronoUnit/MILLIS start end)]
        (/ (double ms) 1000.0))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Query raw build data for a period
;; ---------------------------------------------------------------------------

(defn- query-builds-for-period
  "Query completed builds for a given date range, org-scoped."
  [ds {:keys [org-id job-id period-start period-end]}]
  (let [conditions (cond-> [[:>= :completed-at period-start]
                            [:< :completed-at period-end]
                            [:in :status ["success" "failure" "aborted"]]]
                     org-id (conj [:= :org-id org-id])
                     job-id (conj [:= :job-id job-id]))
        where (into [:and] conditions)]
    (jdbc/execute! ds
      (sql/format {:select [:id :job-id :status :started-at :completed-at :org-id]
                   :from [:builds]
                   :where where
                   :order-by [[:completed-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- query-stages-for-period
  "Query stage results for builds in a given date range.
   Uses raw SQL for the join to avoid HoneySQL aliased column issues."
  [ds {:keys [org-id job-id period-start period-end]}]
  (let [base-sql (str "SELECT bs.stage_name, bs.status, bs.started_at, bs.completed_at,"
                      " b.job_id, b.org_id"
                      " FROM builds b"
                      " JOIN build_stages bs ON b.id = bs.build_id"
                      " WHERE b.completed_at >= ? AND b.completed_at < ?"
                      " AND b.status IN ('success', 'failure', 'aborted')")
        org-clause (when org-id " AND b.org_id = ?")
        job-clause (when job-id " AND b.job_id = ?")
        order-clause " ORDER BY bs.started_at"
        full-sql (str base-sql org-clause job-clause order-clause)
        params (cond-> [full-sql period-start period-end]
                 org-id (conj org-id)
                 job-id (conj job-id))]
    (jdbc/execute! ds params
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Aggregation logic
;; ---------------------------------------------------------------------------

(defn compute-build-analytics
  "Compute build analytics for a period. Returns a map ready for upsert."
  [builds {:keys [org-id job-id period-type period-start period-end]}]
  (let [total (count builds)
        success (count (filter #(= "success" (str (:status %))) builds))
        failure (count (filter #(= "failure" (str (:status %))) builds))
        aborted (count (filter #(= "aborted" (str (:status %))) builds))
        success-rate (if (pos? total) (double (/ success total)) 0.0)
        durations (->> builds
                       (map #(parse-duration-seconds (:started-at %) (:completed-at %)))
                       (filter some?)
                       sort
                       vec)
        avg-dur (when (seq durations)
                  (/ (reduce + durations) (count durations)))
        p50 (compute-percentile durations 50)
        p90 (compute-percentile durations 90)
        p99 (compute-percentile durations 99)
        max-dur (when (seq durations) (last durations))]
    {:org-id org-id
     :job-id job-id
     :period-type period-type
     :period-start period-start
     :period-end period-end
     :total-builds total
     :success-count success
     :failure-count failure
     :aborted-count aborted
     :success-rate success-rate
     :avg-duration-s avg-dur
     :p50-duration-s p50
     :p90-duration-s p90
     :p99-duration-s p99
     :max-duration-s max-dur}))

(defn compute-stage-analytics
  "Compute stage analytics for a period. Returns a seq of maps, one per stage."
  [stages {:keys [org-id job-id period-type period-start period-end]}]
  (let [by-stage (group-by :stage-name stages)]
    (mapv (fn [[stage-name stage-rows]]
            (let [total (count stage-rows)
                  success (count (filter #(= "success" (str (:status %))) stage-rows))
                  failure (count (filter #(= "failure" (str (:status %))) stage-rows))
                  success-rate (if (pos? total) (double (/ success total)) 0.0)
                  durations (->> stage-rows
                                 (map #(parse-duration-seconds (:started-at %) (:completed-at %)))
                                 (filter some?)
                                 sort
                                 vec)
                  avg-dur (when (seq durations)
                            (/ (reduce + durations) (count durations)))
                  p90 (compute-percentile durations 90)
                  max-dur (when (seq durations) (last durations))]
              {:org-id org-id
               :job-id job-id
               :stage-name (str stage-name)
               :period-type period-type
               :period-start period-start
               :period-end period-end
               :total-runs total
               :success-count success
               :failure-count failure
               :avg-duration-s avg-dur
               :p90-duration-s p90
               :max-duration-s max-dur
               :flakiness-score (compute-flakiness-score success-rate)}))
          by-stage)))

;; ---------------------------------------------------------------------------
;; Period helpers
;; ---------------------------------------------------------------------------

(defn- daily-period
  "Get the period boundaries for a given date (start of day to start of next day)."
  [^LocalDate date]
  (let [start (.atStartOfDay date ZoneOffset/UTC)
        end (.atStartOfDay (.plusDays date 1) ZoneOffset/UTC)]
    {:period-start (str (.toInstant start))
     :period-end (str (.toInstant end))
     :period-type "daily"}))

(defn- weekly-period
  "Get the period boundaries for the week containing the given date (Monday to Monday)."
  [^LocalDate date]
  (let [monday (.with date (java.time.temporal.TemporalAdjusters/previousOrSame java.time.DayOfWeek/MONDAY))
        start (.atStartOfDay monday ZoneOffset/UTC)
        end (.atStartOfDay (.plusWeeks monday 1) ZoneOffset/UTC)]
    {:period-start (str (.toInstant start))
     :period-end (str (.toInstant end))
     :period-type "weekly"}))

;; ---------------------------------------------------------------------------
;; Main aggregation run
;; ---------------------------------------------------------------------------

(defn run-aggregation!
  "Run analytics aggregation for the previous day and week.
   Queries raw build/stage data and persists computed analytics.
   Returns a summary map."
  [system]
  (let [ds (:db system)
        config (:config system)
        registry (:metrics system)
        start-time (System/currentTimeMillis)]
    (if-not (ff/enabled? config :build-analytics)
      {:status :disabled}
      (try
        (log/info "Running build analytics aggregation...")
        (let [yesterday (.minusDays (LocalDate/now ZoneOffset/UTC) 1)
              daily (daily-period yesterday)
              weekly (weekly-period yesterday)
              ;; Aggregate daily builds
              daily-builds (query-builds-for-period ds daily)
              daily-build-stats (compute-build-analytics daily-builds daily)
              _ (when (pos? (:total-builds daily-build-stats))
                  (analytics-store/upsert-build-analytics! ds daily-build-stats))
              ;; Aggregate daily stages
              daily-stages (query-stages-for-period ds daily)
              daily-stage-stats (compute-stage-analytics daily-stages daily)
              _ (doseq [ss daily-stage-stats]
                  (when (pos? (:total-runs ss))
                    (analytics-store/upsert-stage-analytics! ds ss)))
              ;; Aggregate weekly builds
              weekly-builds (query-builds-for-period ds weekly)
              weekly-build-stats (compute-build-analytics weekly-builds weekly)
              _ (when (pos? (:total-builds weekly-build-stats))
                  (analytics-store/upsert-build-analytics! ds weekly-build-stats))
              ;; Aggregate weekly stages
              weekly-stages (query-stages-for-period ds weekly)
              weekly-stage-stats (compute-stage-analytics weekly-stages weekly)
              _ (doseq [ss weekly-stage-stats]
                  (when (pos? (:total-runs ss))
                    (analytics-store/upsert-stage-analytics! ds ss)))
              duration-s (/ (- (System/currentTimeMillis) start-time) 1000.0)
              summary {:status :success
                       :daily-builds (:total-builds daily-build-stats)
                       :daily-stages (count daily-stage-stats)
                       :weekly-builds (:total-builds weekly-build-stats)
                       :weekly-stages (count weekly-stage-stats)
                       :duration-s duration-s
                       :timestamp (str (Instant/now))}]
          (try (metrics/record-analytics-aggregation-run! registry) (catch Exception _))
          (try (metrics/record-analytics-aggregation-duration! registry duration-s) (catch Exception _))
          (log/info "Analytics aggregation complete:" summary)
          (reset! last-run-result summary)
          summary)
        (catch Exception e
          (log/error "Analytics aggregation failed:" (.getMessage e))
          {:status :error :error (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Scheduler
;; ---------------------------------------------------------------------------

(defn start-analytics!
  "Start the periodic analytics aggregation scheduler.
   Runs every :analytics :aggregation-interval-hours (default 6h)."
  [system]
  (when-let [existing @analytics-scheduler]
    (try (.close existing) (catch Exception _))
    (reset! analytics-scheduler nil))
  (let [interval-hours (get-in system [:config :analytics :aggregation-interval-hours] 6)
        interval-ms (* interval-hours 3600000)
        start-time (.plus (Instant/now) (Duration/ofMillis interval-ms))
        times (chime/periodic-seq start-time (Duration/ofMillis interval-ms))
        sched (chime/chime-at
                times
                (fn [_time]
                  (try
                    (run-aggregation! system)
                    (catch Exception e
                      (log/error "Analytics scheduler error:" (.getMessage e))))))]
    (reset! analytics-scheduler sched)
    (log/info "Analytics scheduler started: running every" interval-hours "hours")
    (fn []
      (when-let [s @analytics-scheduler]
        (.close s)
        (reset! analytics-scheduler nil)
        (log/info "Analytics scheduler stopped")))))

(defn stop-analytics!
  "Stop the analytics scheduler if running."
  []
  (when-let [sched @analytics-scheduler]
    (.close sched)
    (reset! analytics-scheduler nil)
    (log/info "Analytics scheduler stopped")))

(defn running?*
  "Check if the analytics scheduler is currently running."
  []
  (some? @analytics-scheduler))

(defn last-run
  "Get the result of the last analytics aggregation run."
  []
  @last-run-result)

;; ---------------------------------------------------------------------------
;; Public query wrappers (delegate to store with feature-flag check)
;; ---------------------------------------------------------------------------

(defn get-build-trends
  "Get build trends. Returns empty seq when analytics disabled."
  [system & {:keys [org-id job-id period-type limit]}]
  (if-not (ff/enabled? (:config system) :build-analytics)
    []
    (analytics-store/get-build-trends (:db system)
      :org-id org-id :job-id job-id
      :period-type (or period-type "daily")
      :limit (or limit 90))))

(defn get-slowest-stages
  "Get slowest stages. Returns empty seq when analytics disabled."
  [system & {:keys [org-id job-id period-type limit]}]
  (if-not (ff/enabled? (:config system) :build-analytics)
    []
    (analytics-store/get-slowest-stages (:db system)
      :org-id org-id :job-id job-id
      :period-type (or period-type "daily")
      :limit (or limit 20))))

(defn get-flaky-stages
  "Get flaky stages. Returns empty seq when analytics disabled."
  [system & {:keys [org-id period-type threshold limit]}]
  (if-not (ff/enabled? (:config system) :build-analytics)
    []
    (analytics-store/get-flaky-stages (:db system)
      :org-id org-id
      :period-type (or period-type "daily")
      :threshold (or threshold 0.15)
      :limit (or limit 20))))
