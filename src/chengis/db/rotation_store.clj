(ns chengis.db.rotation-store
  "CRUD store for secret rotation policies and version history.
   Feature-flag gated via :secret-rotation."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Policy CRUD
;; ---------------------------------------------------------------------------

(defn- now-plus-days
  "Return a timestamp string for CURRENT_TIMESTAMP + n days.
   Uses Java time to be database-agnostic (works with both SQLite and PostgreSQL)."
  [n]
  (str (.plus (java.time.Instant/now)
              (java.time.Duration/ofDays n))))

(defn- now-str
  "Return current timestamp as ISO string."
  []
  (str (java.time.Instant/now)))

(defn create-policy!
  "Insert a rotation policy. Returns the policy map."
  [ds {:keys [org-id secret-name secret-scope rotation-interval-days
              max-versions notify-days-before created-by]}]
  (let [id (util/generate-id)
        interval (or rotation-interval-days 90)
        now (now-str)
        row {:id id
             :org-id org-id
             :secret-name secret-name
             :secret-scope (or secret-scope "global")
             :rotation-interval-days interval
             :max-versions (or max-versions 3)
             :notify-days-before (or notify-days-before 7)
             :next-rotation-at (now-plus-days interval)
             :enabled 1
             :created-by created-by
             :created-at now
             :updated-at now}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :secret-rotation-policies
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    ;; Read back the inserted row to return actual timestamps
    (jdbc/execute-one! ds
      (sql/format {:select [:*]
                   :from :secret-rotation-policies
                   :where [:= :id id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-policy
  "Get a rotation policy by id. Optionally scope by org-id."
  [ds id & {:keys [org-id]}]
  (let [conditions (cond-> [[:= :id id]]
                     org-id (conj [:= :org-id org-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute-one! ds
      (sql/format {:select [:*]
                   :from :secret-rotation-policies
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-policy-for-secret
  "Get the rotation policy for a specific secret."
  [ds org-id secret-name secret-scope]
  (jdbc/execute-one! ds
    (sql/format {:select [:*]
                 :from :secret-rotation-policies
                 :where [:and
                         [:= :org-id org-id]
                         [:= :secret-name secret-name]
                         [:= :secret-scope (or secret-scope "global")]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-policies
  "List rotation policies. Optionally filter by org-id and/or enabled-only."
  [ds & {:keys [org-id enabled-only]}]
  (let [conditions (cond-> []
                     org-id (conj [:= :org-id org-id])
                     enabled-only (conj [:= :enabled 1]))
        query (cond-> {:select [:*]
                        :from :secret-rotation-policies
                        :order-by [[:created-at :desc]]}
                (seq conditions) (assoc :where (if (= 1 (count conditions))
                                                 (first conditions)
                                                 (into [:and] conditions))))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn update-policy!
  "Update policy fields (interval, max-versions, notify-days, enabled).
   Automatically sets updated-at."
  [ds id updates]
  (let [allowed-keys #{:rotation-interval-days :max-versions
                        :notify-days-before :enabled}
        safe-updates (-> (select-keys updates allowed-keys)
                         (assoc :updated-at [:raw "CURRENT_TIMESTAMP"]))]
    (jdbc/execute-one! ds
      (sql/format {:update :secret-rotation-policies
                   :set safe-updates
                   :where [:= :id id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-policy!
  "Delete a rotation policy. Optionally check org ownership. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [conditions (cond-> [[:= :id id]]
                     org-id (conj [:= :org-id org-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :secret-rotation-policies
                              :where where}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

;; ---------------------------------------------------------------------------
;; Rotation scheduling queries
;; ---------------------------------------------------------------------------

(defn policies-due-for-rotation
  "Find policies where next_rotation_at <= CURRENT_TIMESTAMP AND enabled=1."
  [ds]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from :secret-rotation-policies
                 :where [:and
                         [:= :enabled 1]
                         [:<= :next-rotation-at [:raw "CURRENT_TIMESTAMP"]]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn policies-due-for-notification
  "Find enabled policies where next_rotation_at is within notify_days_before days.
   Filters in Clojure for database-agnostic compatibility."
  [ds]
  (let [now (java.time.Instant/now)
        all-enabled (jdbc/execute! ds
                      (sql/format {:select [:*]
                                   :from :secret-rotation-policies
                                   :where [:and
                                           [:= :enabled 1]
                                           [:!= :next-rotation-at nil]]})
                      {:builder-fn rs/as-unqualified-kebab-maps})]
    (filter (fn [policy]
              (try
                (let [next-at (java.time.Instant/parse (str (:next-rotation-at policy)))
                      days-until (/ (.toMillis (java.time.Duration/between now next-at))
                                    86400000.0)
                      notify-days (or (:notify-days-before policy) 7)]
                  (and (pos? days-until) (<= days-until notify-days)))
                (catch Exception _ false)))
            all-enabled)))

(defn mark-rotated!
  "Update last_rotated_at and compute next_rotation_at based on policy interval.
   Reads the policy first to compute next rotation in Java (database-agnostic)."
  [ds id]
  (let [policy (get-policy ds id)
        interval (or (:rotation-interval-days policy) 90)
        now (now-str)]
    (jdbc/execute-one! ds
      (sql/format {:update :secret-rotation-policies
                   :set {:last-rotated-at now
                         :next-rotation-at (now-plus-days interval)
                         :updated-at now}
                   :where [:= :id id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Version history
;; ---------------------------------------------------------------------------

(defn record-version!
  "Insert a version record into secret_versions."
  [ds {:keys [org-id secret-name secret-scope version rotated-by
              rotation-reason previous-value-hash]}]
  (let [id (util/generate-id)
        row {:id id
             :org-id org-id
             :secret-name secret-name
             :secret-scope (or secret-scope "global")
             :version version
             :rotated-by rotated-by
             :rotation-reason (or rotation-reason "scheduled")
             :previous-value-hash previous-value-hash}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :secret-versions
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    ;; Read back to return with actual rotated-at timestamp
    (jdbc/execute-one! ds
      (sql/format {:select [:*]
                   :from :secret-versions
                   :where [:= :id id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-versions
  "List version history for a secret."
  [ds org-id secret-name & {:keys [secret-scope limit]
                             :or {secret-scope "global" limit 20}}]
  (jdbc/execute! ds
    (sql/format {:select [:*]
                 :from :secret-versions
                 :where [:and
                         [:= :org-id org-id]
                         [:= :secret-name secret-name]
                         [:= :secret-scope secret-scope]]
                 :order-by [[:version :desc]]
                 :limit limit})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn latest-version
  "Get the latest version number for a secret. Returns integer or 0."
  [ds org-id secret-name secret-scope]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[[:max :version] :max-ver]]
                              :from :secret-versions
                              :where [:and
                                      [:= :org-id org-id]
                                      [:= :secret-name secret-name]
                                      [:= :secret-scope (or secret-scope "global")]]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (or (:max-ver result) 0)))

(defn cleanup-old-versions!
  "Delete versions older than max-versions for a given secret.
   Keeps the most recent max-versions entries."
  [ds org-id secret-name secret-scope max-versions]
  (let [scope (or secret-scope "global")
        ;; Find the version threshold: keep versions >= this
        keep-from (jdbc/execute-one! ds
                    (sql/format {:select [:version]
                                 :from :secret-versions
                                 :where [:and
                                         [:= :org-id org-id]
                                         [:= :secret-name secret-name]
                                         [:= :secret-scope scope]]
                                 :order-by [[:version :desc]]
                                 :offset max-versions
                                 :limit 1})
                    {:builder-fn rs/as-unqualified-kebab-maps})]
    (when-let [threshold (:version keep-from)]
      (let [result (jdbc/execute-one! ds
                     (sql/format {:delete-from :secret-versions
                                  :where [:and
                                          [:= :org-id org-id]
                                          [:= :secret-name secret-name]
                                          [:= :secret-scope scope]
                                          [:<= :version threshold]]}))]
        (or (:next.jdbc/update-count result) 0)))))
