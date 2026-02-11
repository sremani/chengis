(ns chengis.db.deployment-store
  "CRUD store for deployment execution records and deployment steps.
   Tracks deployment lifecycle: pending -> in-progress -> succeeded/failed/cancelled."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(defn- normalize-deployment
  "Parse metadata-json into :metadata map."
  [d]
  (when d
    (assoc d :metadata (when-let [mj (:metadata-json d)]
                         (try (json/read-str mj :key-fn keyword)
                              (catch Exception _ nil))))))

(defn create-deployment!
  "Create a new deployment. Returns the created row map."
  [ds {:keys [id org-id environment-id release-id build-id strategy-id
              initiated-by rollback-of metadata]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :environment-id environment-id
             :release-id release-id
             :build-id build-id
             :strategy-id strategy-id
             :status "pending"
             :initiated-by initiated-by
             :rollback-of rollback-of
             :metadata-json (when metadata (json/write-str metadata))}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :deployments :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-deployment row)))

(defn get-deployment
  "Get deployment by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (normalize-deployment
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :deployments
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-deployments
  "List deployments with optional filters."
  [ds & {:keys [org-id environment-id status limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id          (conj [:= :org-id org-id])
                     environment-id  (conj [:= :environment-id environment-id])
                     status          (conj [:= :status status]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :deployments
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (mapv normalize-deployment
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-deployment-status!
  "Update deployment status and optional timestamp fields."
  [ds id new-status & {:keys [started-at completed-at]}]
  (let [updates (cond-> {:status new-status}
                  started-at   (assoc :started-at [:raw "CURRENT_TIMESTAMP"])
                  completed-at (assoc :completed-at [:raw "CURRENT_TIMESTAMP"]))
        result (jdbc/execute-one! ds
                 (sql/format {:update :deployments
                              :set updates
                              :where [:= :id id]}))]
    (or (:next.jdbc/update-count result) 0)))

(defn cancel-deployment!
  "Cancel a pending or in-progress deployment."
  [ds id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :deployments
                              :set {:status "cancelled"
                                    :completed-at [:raw "CURRENT_TIMESTAMP"]}
                              :where [:and
                                      [:= :id id]
                                      [:in :status ["pending" "in-progress"]]]}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn get-active-deployment
  "Get the current active (pending or in-progress) deployment for an environment."
  [ds environment-id]
  (normalize-deployment
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :deployments
                   :where [:and
                           [:= :environment-id environment-id]
                           [:in :status ["pending" "in-progress"]]]
                   :order-by [[:created-at :desc]]
                   :limit 1})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-previous-successful-deployment
  "Get the most recent successful deployment for an environment before a given deployment."
  [ds environment-id before-deployment-id]
  (let [dep (jdbc/execute-one! ds
              (sql/format {:select [:created-at]
                           :from :deployments
                           :where [:= :id before-deployment-id]})
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when dep
      (normalize-deployment
        (jdbc/execute-one! ds
          (sql/format {:select :*
                       :from :deployments
                       :where [:and
                               [:= :environment-id environment-id]
                               [:= :status "succeeded"]
                               [:< :created-at (:created-at dep)]]
                       :order-by [[:created-at :desc]]
                       :limit 1})
          {:builder-fn rs/as-unqualified-kebab-maps})))))

(defn count-deployments-by-status
  "Count deployments grouped by status for an org."
  [ds org-id]
  (let [rows (jdbc/execute! ds
               (sql/format {:select [:status [[:count :*] :cnt]]
                            :from :deployments
                            :where [:= :org-id org-id]
                            :group-by [:status]})
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (into {} (map (fn [r] [(:status r) (:cnt r)])) rows)))

;; ---------------------------------------------------------------------------
;; Deployment Steps
;; ---------------------------------------------------------------------------

(defn add-deployment-step!
  "Add a step to a deployment. Returns the step row map."
  [ds {:keys [id deployment-id step-name step-order]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :deployment-id deployment-id
             :step-name step-name
             :step-order step-order
             :status "pending"}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :deployment-steps :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn update-step-status!
  "Update a deployment step's status and optional output."
  [ds step-id new-status & {:keys [output]}]
  (let [updates (cond-> {:status new-status}
                  (= new-status "in-progress") (assoc :started-at [:raw "CURRENT_TIMESTAMP"])
                  (#{"succeeded" "failed" "skipped"} new-status) (assoc :completed-at [:raw "CURRENT_TIMESTAMP"])
                  output (assoc :output output))
        result (jdbc/execute-one! ds
                 (sql/format {:update :deployment-steps
                              :set updates
                              :where [:= :id step-id]}))]
    (or (:next.jdbc/update-count result) 0)))

(defn get-deployment-steps
  "Get all steps for a deployment, ordered by step_order."
  [ds deployment-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :deployment-steps
                 :where [:= :deployment-id deployment-id]
                 :order-by [[:step-order :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
