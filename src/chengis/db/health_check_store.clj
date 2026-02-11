(ns chengis.db.health-check-store
  "CRUD store for health check definitions and results.
   Health checks verify environment health during/after deployments."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(defn- normalize-check
  "Parse config-json into :config map and normalize boolean fields."
  [c]
  (when c
    (-> c
        (update :enabled #(if (number? %) (pos? %) (boolean %)))
        (assoc :config (when-let [cj (:config-json c)]
                         (try (json/read-str cj :key-fn keyword)
                              (catch Exception _ nil)))))))

(defn create-health-check!
  "Create a new health check definition. Returns the created row."
  [ds {:keys [id org-id environment-id name check-type config enabled]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :environment-id environment-id
             :name name
             :check-type check-type
             :config-json (when config (json/write-str config))
             :enabled (if (false? enabled) 0 1)}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :health-check-definitions :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-check (merge row {:created-at nil :updated-at nil}))))

(defn get-health-check
  "Get health check definition by ID."
  [ds id & {:keys [org-id]}]
  (normalize-check
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :health-check-definitions
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-health-checks
  "List health checks for an environment, optionally only enabled."
  [ds environment-id & {:keys [enabled-only]}]
  (let [query (cond-> {:select :*
                       :from :health-check-definitions
                       :where (if enabled-only
                                [:and [:= :environment-id environment-id] [:= :enabled 1]]
                                [:= :environment-id environment-id])
                       :order-by [[:created-at :asc]]})]
    (mapv normalize-check
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-health-check!
  "Update health check fields. Returns update count."
  [ds id updates & {:keys [org-id]}]
  (let [clean (-> updates
                  (dissoc :id :org-id :created-at :config)
                  (cond->
                    (contains? updates :enabled)
                    (assoc :enabled (if (:enabled updates) 1 0))
                    (contains? updates :config)
                    (assoc :config-json (when-let [c (:config updates)]
                                          (json/write-str c)))))
        result (jdbc/execute-one! ds
                 (sql/format {:update :health-check-definitions
                              :set clean
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (or (:next.jdbc/update-count result) 0)))

(defn delete-health-check!
  "Delete health check definition by ID. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :health-check-definitions
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

;; ---------------------------------------------------------------------------
;; Health Check Results
;; ---------------------------------------------------------------------------

(defn save-health-check-result!
  "Save a health check result. Returns the result row."
  [ds {:keys [id health-check-id deployment-id status response-time-ms output]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :health-check-id health-check-id
             :deployment-id deployment-id
             :status status
             :response-time-ms response-time-ms
             :output (when output (subs output 0 (min (count output) 2000)))}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :health-check-results :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn get-latest-results
  "Get the most recent health check result for each check in an environment."
  [ds environment-id]
  (let [checks (list-health-checks ds environment-id)]
    (mapv (fn [check]
            (let [result (jdbc/execute-one! ds
                           (sql/format {:select :*
                                        :from :health-check-results
                                        :where [:= :health-check-id (:id check)]
                                        :order-by [[:checked-at :desc]]
                                        :limit 1})
                           {:builder-fn rs/as-unqualified-kebab-maps})]
              (merge check {:latest-result result})))
          checks)))

(defn get-check-history
  "Get result history for a specific health check."
  [ds health-check-id & {:keys [limit] :or {limit 50}}]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :health-check-results
                 :where [:= :health-check-id health-check-id]
                 :order-by [[:checked-at :desc]]
                 :limit limit})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-deployment-health-results
  "Get all health check results for a specific deployment."
  [ds deployment-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :health-check-results
                 :where [:= :deployment-id deployment-id]
                 :order-by [[:checked-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
