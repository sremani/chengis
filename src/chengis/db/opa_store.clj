(ns chengis.db.opa-store
  "CRUD store for OPA (Open Policy Agent) Rego policies.
   Stores policy definitions with Rego source code for evaluation
   via the OPA engine. Follows store conventions: ds as first arg,
   org-id scoping, HoneySQL for query generation."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Policy CRUD
;; ---------------------------------------------------------------------------

(defn create-policy!
  "Create a new OPA policy record. If id is nil, generates one via util/generate-id.
   enabled defaults to 1 (true)."
  [ds {:keys [id org-id name description rego-source package-name input-schema enabled]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :name name
             :description description
             :rego-source rego-source
             :package-name package-name
             :input-schema input-schema
             :enabled (if (nil? enabled) 1 (if enabled 1 0))}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :opa-policies
                   :values [row]}))
    (log/info "Created OPA policy" {:id id :name name :package package-name})
    row))

(defn get-policy
  "Get an OPA policy by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :opa-policies
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-policies
  "List OPA policies with optional filters.
   When enabled-only is true, only returns enabled policies.
   Default limit 50, offset 0."
  [ds & {:keys [org-id enabled-only limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id       (conj [:= :org-id org-id])
                     enabled-only (conj [:= :enabled 1]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :opa-policies
                       :order-by [[:name :asc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn update-policy!
  "Update OPA policy fields: rego-source, description, enabled, package-name.
   Sets updated-at to CURRENT_TIMESTAMP."
  [ds id updates & {:keys [org-id]}]
  (let [set-map (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  (:rego-source updates)  (assoc :rego-source (:rego-source updates))
                  (:description updates)  (assoc :description (:description updates))
                  (:package-name updates) (assoc :package-name (:package-name updates))
                  (contains? updates :enabled)
                  (assoc :enabled (if (:enabled updates) 1 0)))
        where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:update :opa-policies
                   :set set-map
                   :where where}))))

(defn delete-policy!
  "Delete an OPA policy by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :opa-policies
                   :where where}))))
