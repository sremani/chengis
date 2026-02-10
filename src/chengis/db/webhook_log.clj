(ns chengis.db.webhook-log
  "Webhook event persistence — logs every incoming webhook for audit + debugging."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn log-webhook-event!
  "Insert a webhook event record. Returns the generated ID.
   When :org-id is provided, associates the event with that organization."
  [ds {:keys [provider event-type repo-url repo-name branch commit-sha
              signature-valid status matched-jobs triggered-builds
              error payload-size processing-ms org-id payload-body]}]
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :provider (if provider (name provider) "unknown")
                     :event-type event-type
                     :repo-url repo-url
                     :repo-name repo-name
                     :branch branch
                     :commit-sha commit-sha
                     :signature-valid (if signature-valid 1 0)
                     :status (if status (name status) "processed")
                     :matched-jobs (or matched-jobs 0)
                     :triggered-builds (or triggered-builds 0)
                     :error error
                     :payload-size payload-size
                     :processing-ms processing-ms}
              org-id (assoc :org-id org-id)
              payload-body (assoc :payload-body payload-body))]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :webhook-events
                     :values [row]}))
      id
      (catch Exception _
        ;; Table may not exist yet (migration not applied)
        nil))))

(defn list-webhook-events
  "List webhook events with optional filters and pagination.
   Options: :provider, :status, :org-id, :limit, :offset"
  [ds & {:keys [provider status org-id limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     provider (conj [:= :provider provider])
                     status   (conj [:= :status status])
                     org-id   (conj [:= :org-id org-id]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select :*
                       :from :webhook-events
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-webhook-event
  "Retrieve a single webhook event by ID."
  [ds event-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :webhook-events
                 :where [:= :id event-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn count-webhook-events
  "Count webhook events matching optional filters."
  [ds & {:keys [provider status org-id]}]
  (let [conditions (cond-> [:and]
                     provider (conj [:= :provider provider])
                     status   (conj [:= :status status])
                     org-id   (conj [:= :org-id org-id]))
        where (if (> (count conditions) 1) conditions nil)
        query (cond-> {:select [[[:count :*] :count]]
                       :from :webhook-events}
                where (assoc :where where))]
    (:count
      (jdbc/execute-one! ds
        (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn mark-replayed!
  "Update a webhook event to track replay. Increments replay_count."
  [ds event-id]
  (try
    (jdbc/execute-one! ds
      [(str "UPDATE webhook_events "
            "SET replay_count = COALESCE(replay_count, 0) + 1, "
            "last_replayed_at = CURRENT_TIMESTAMP "
            "WHERE id = ?")
       event-id])
    (catch Exception _ nil)))

(defn list-replayable-events
  "List webhook events that have a stored payload (can be replayed).
   Options: :provider, :org-id, :limit, :offset"
  [ds & {:keys [provider org-id limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and [:not= :payload-body nil]]
                     provider (conj [:= :provider provider])
                     org-id   (conj [:= :org-id org-id]))
        query {:select [:id :provider :event-type :repo-url :repo-name :branch
                        :commit-sha :status :matched-jobs :triggered-builds
                        :replay-count :last-replayed-at :created-at]
               :from :webhook-events
               :where conditions
               :order-by [[:created-at :desc]]
               :limit limit
               :offset offset}]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn cleanup-old-events!
  "Delete webhook events older than retention-days. Returns number of rows deleted."
  [ds retention-days]
  (let [cutoff (str (.minus (java.time.Instant/now) (java.time.Duration/ofDays retention-days)))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :webhook-events
                              :where [:< :created-at cutoff]}))]
    (:next.jdbc/update-count result 0)))
