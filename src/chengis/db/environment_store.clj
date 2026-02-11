(ns chengis.db.environment-store
  "CRUD store for deployment environments.
   Environments are ordered by env_order (e.g., dev=10, staging=20, prod=30).
   Supports atomic locking to prevent concurrent deployments."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(defn- normalize-bool
  "Convert SQLite integer (0/1) or nil to boolean."
  [v]
  (if (number? v) (pos? v) (boolean v)))

(defn- normalize-env
  "Normalize boolean fields and parse config JSON."
  [env]
  (when env
    (-> env
        (update :requires-approval normalize-bool)
        (update :auto-promote normalize-bool)
        (update :locked normalize-bool)
        (assoc :config (when-let [cj (:config-json env)]
                         (try (json/read-str cj :key-fn keyword)
                              (catch Exception _ nil)))))))

(defn create-environment!
  "Create a new environment. Returns the created row map."
  [ds {:keys [id org-id name slug env-order description
              requires-approval auto-promote config]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :name name
             :slug slug
             :env-order (or env-order 0)
             :description description
             :requires-approval (if requires-approval 1 0)
             :auto-promote (if auto-promote 1 0)
             :config-json (when config (json/write-str config))}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :environments :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-env (merge row {:locked 0 :locked-by nil :locked-at nil
                               :created-at nil :updated-at nil}))))

(defn get-environment
  "Get environment by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (normalize-env
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :environments
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-environment-by-slug
  "Get environment by slug within an org."
  [ds org-id slug]
  (normalize-env
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :environments
                   :where [:and [:= :org-id org-id] [:= :slug slug]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-environments
  "List environments for an org, ordered by env_order."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select :*
                       :from :environments
                       :order-by [[:env-order :asc] [:created-at :asc]]}
                org-id (assoc :where [:= :org-id org-id]))]
    (mapv normalize-env
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-environment!
  "Update environment fields. Returns update count."
  [ds id updates & {:keys [org-id]}]
  (let [clean (-> updates
                  (dissoc :id :org-id :created-at :config)
                  (cond->
                    (contains? updates :requires-approval)
                    (assoc :requires-approval (if (:requires-approval updates) 1 0))
                    (contains? updates :auto-promote)
                    (assoc :auto-promote (if (:auto-promote updates) 1 0))
                    (contains? updates :config)
                    (assoc :config-json (when-let [c (:config updates)]
                                          (json/write-str c)))))
        result (jdbc/execute-one! ds
                 (sql/format {:update :environments
                              :set clean
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (or (:next.jdbc/update-count result) 0)))

(defn lock-environment!
  "Atomically lock an environment. Returns true if lock acquired, false if already locked."
  [ds id user-id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :environments
                              :set {:locked 1
                                    :locked-by user-id
                                    :locked-at [:raw "CURRENT_TIMESTAMP"]}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id] [:= :locked 0]]
                                       [:and [:= :id id] [:= :locked 0]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn unlock-environment!
  "Unlock an environment. Returns true if unlocked."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :environments
                              :set {:locked 0 :locked-by nil :locked-at nil}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn delete-environment!
  "Delete environment by ID. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :environments
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))
