(ns chengis.db.strategy-store
  "CRUD store for deployment strategies.
   Strategies define how deployments execute: direct, blue-green, canary, rolling."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(def default-strategies
  "Seed data for common deployment strategies."
  [{:name "Direct Deployment"
    :strategy-type "direct"
    :description "Immediate full replacement"
    :config {}}
   {:name "Blue-Green"
    :strategy-type "blue-green"
    :description "Switch traffic with old-stack retention"
    :config {:switch-timeout-ms 30000 :keep-old-ms 300000}}
   {:name "Canary 10-25-50-100"
    :strategy-type "canary"
    :description "Incremental rollout with error threshold"
    :config {:increments [10 25 50 100]
             :increment-interval-ms 60000
             :rollback-threshold 0.05}}
   {:name "Rolling 25%"
    :strategy-type "rolling"
    :description "Deploy in 4 waves with pauses"
    :config {:batch-percent 25 :pause-between-batches-ms 10000}}])

(defn- normalize-strategy
  "Parse config-json into :config map."
  [s]
  (when s
    (assoc s :config (when-let [cj (:config-json s)]
                       (try (json/read-str cj :key-fn keyword)
                            (catch Exception _ nil))))))

(defn create-strategy!
  "Create a new deployment strategy. Returns the created row map."
  [ds {:keys [id org-id name strategy-type config description]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :name name
             :strategy-type strategy-type
             :config-json (when config (json/write-str config))
             :description description}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :deployment-strategies :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (assoc row :config config)))

(defn get-strategy
  "Get strategy by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (normalize-strategy
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :deployment-strategies
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-strategies
  "List strategies for an org."
  [ds & {:keys [org-id strategy-type]}]
  (let [conditions (cond-> [:and]
                     org-id        (conj [:= :org-id org-id])
                     strategy-type (conj [:= :strategy-type strategy-type]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :deployment-strategies
                       :order-by [[:created-at :asc]]}
                where (assoc :where where))]
    (mapv normalize-strategy
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-strategy!
  "Update strategy fields. Returns update count."
  [ds id updates & {:keys [org-id]}]
  (let [clean (-> updates
                  (dissoc :id :org-id :created-at :config)
                  (cond->
                    (contains? updates :config)
                    (assoc :config-json (when-let [c (:config updates)]
                                          (json/write-str c)))))
        result (jdbc/execute-one! ds
                 (sql/format {:update :deployment-strategies
                              :set clean
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (or (:next.jdbc/update-count result) 0)))

(defn delete-strategy!
  "Delete strategy by ID. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :deployment-strategies
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn seed-default-strategies!
  "Insert default strategies for an org if they don't exist."
  [ds org-id]
  (doseq [s default-strategies]
    (try
      (create-strategy! ds (assoc s :org-id org-id))
      (catch Exception _ nil))))  ;; Ignore duplicate key errors
