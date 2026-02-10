(ns chengis.db.cron-store
  "Persistence for database-backed cron schedules and run history."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

;; ---------------------------------------------------------------------------
;; Schedule CRUD
;; ---------------------------------------------------------------------------

(defn create-schedule!
  "Create a new cron schedule for a job. Returns the schedule map."
  [ds {:keys [job-id org-id cron-expression description enabled timezone parameters next-run-at]
       :or {org-id "default-org" enabled true timezone "UTC"}}]
  (let [id (util/generate-id)
        row {:id id
             :job-id job-id
             :org-id org-id
             :cron-expression cron-expression
             :description description
             :enabled (if enabled 1 0)
             :timezone timezone
             :parameters (when parameters (util/serialize-edn parameters))
             :next-run-at next-run-at}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :cron-schedules
                   :values [row]}))
    (-> row
        (assoc :run-count 0 :miss-count 0)
        (update :enabled #(pos? %)))))

(defn get-schedule
  "Get a schedule by ID."
  [ds schedule-id & {:keys [org-id]}]
  (let [row (jdbc/execute-one! ds
              (sql/format (cond-> {:select :*
                                   :from :cron-schedules
                                   :where [:= :id schedule-id]}
                            org-id (assoc :where [:and [:= :id schedule-id] [:= :org-id org-id]])))
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (-> row
          (update :parameters util/deserialize-edn)
          (update :enabled #(if (number? %) (pos? %) (boolean %)))))))

(defn list-schedules
  "List schedules. When job-id or org-id provided, filters accordingly."
  [ds & {:keys [job-id org-id enabled-only]}]
  (let [conditions (cond-> [:and]
                     job-id (conj [:= :job-id job-id])
                     org-id (conj [:= :org-id org-id])
                     enabled-only (conj [:= :enabled 1]))
        where (if (> (count conditions) 1) conditions nil)]
    (mapv (fn [row]
            (-> row
                (update :parameters util/deserialize-edn)
                (update :enabled #(if (number? %) (pos? %) (boolean %)))))
      (jdbc/execute! ds
        (sql/format (cond-> {:select :*
                             :from :cron-schedules
                             :order-by [[:created-at :asc]]}
                      where (assoc :where where)))
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-schedule!
  "Update a schedule's properties."
  [ds schedule-id {:keys [cron-expression description enabled timezone parameters next-run-at]}
   & {:keys [org-id]}]
  (let [updates (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  (some? cron-expression) (assoc :cron-expression cron-expression)
                  (some? description) (assoc :description description)
                  (some? enabled) (assoc :enabled (if enabled 1 0))
                  (some? timezone) (assoc :timezone timezone)
                  (some? parameters) (assoc :parameters (util/serialize-edn parameters))
                  (some? next-run-at) (assoc :next-run-at next-run-at))]
    (jdbc/execute-one! ds
      (sql/format {:update :cron-schedules
                   :set updates
                   :where (if org-id
                            [:and [:= :id schedule-id] [:= :org-id org-id]]
                            [:= :id schedule-id])}))))

(defn delete-schedule!
  "Delete a schedule. When org-id provided, verifies ownership."
  [ds schedule-id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :cron-schedules
                              :where (if org-id
                                       [:and [:= :id schedule-id] [:= :org-id org-id]]
                                       [:= :id schedule-id])}))]
    (pos? (:next.jdbc/update-count result 0))))

;; ---------------------------------------------------------------------------
;; Schedule execution tracking
;; ---------------------------------------------------------------------------

(defn get-due-schedules
  "Get all enabled schedules whose next-run-at is at or before the given time.
   Returns schedules ready to be triggered."
  [ds now-str]
  (mapv (fn [row]
          (-> row
              (update :parameters util/deserialize-edn)
              (update :enabled #(if (number? %) (pos? %) (boolean %)))))
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :cron-schedules
                   :where [:and
                           [:= :enabled 1]
                           [:not= :next-run-at nil]
                           [:<= :next-run-at now-str]]
                   :order-by [[:next-run-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn mark-schedule-run!
  "Update schedule after a run: set last-run-at, next-run-at, increment count."
  [ds schedule-id {:keys [last-run-at next-run-at status]}]
  (jdbc/execute-one! ds
    [(str "UPDATE cron_schedules SET "
          "last_run_at = ?, next_run_at = ?, last_status = ?, "
          "run_count = run_count + 1, "
          "updated_at = CURRENT_TIMESTAMP "
          "WHERE id = ?")
     last-run-at next-run-at (when status (name status)) schedule-id]))

(defn mark-schedule-missed!
  "Increment the miss_count for a schedule (when run was missed)."
  [ds schedule-id]
  (jdbc/execute-one! ds
    [(str "UPDATE cron_schedules SET "
          "miss_count = miss_count + 1, "
          "updated_at = CURRENT_TIMESTAMP "
          "WHERE id = ?")
     schedule-id]))

;; ---------------------------------------------------------------------------
;; Run history
;; ---------------------------------------------------------------------------

(defn record-cron-run!
  "Record a cron run in history."
  [ds {:keys [schedule-id job-id build-id org-id scheduled-at triggered-at status missed error]
       :or {org-id "default-org" status "triggered" missed false}}]
  (let [id (util/generate-id)
        row {:id id
             :schedule-id schedule-id
             :job-id job-id
             :build-id build-id
             :org-id org-id
             :scheduled-at scheduled-at
             :triggered-at triggered-at
             :status (name status)
             :missed (if missed 1 0)
             :error error}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :cron-run-history
                   :values [row]}))
    row))

(defn list-cron-runs
  "List cron run history for a schedule."
  [ds schedule-id & {:keys [limit offset] :or {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :cron-run-history
                 :where [:= :schedule-id schedule-id]
                 :order-by [[:created-at :desc]]
                 :limit limit
                 :offset offset})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn cleanup-old-runs!
  "Delete cron run history older than retention-days."
  [ds retention-days]
  (let [cutoff (str (.minus (java.time.Instant/now) (java.time.Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :cron-run-history
                              :where [:< :created-at cutoff]}))]
    (:next.jdbc/update-count result 0)))
