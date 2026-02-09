(ns chengis.db.policy-store
  "CRUD for policies and evaluation logging."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Row transformers
;; ---------------------------------------------------------------------------

(defn- row->policy
  "Deserialize the rules JSON column and normalize enabled to boolean."
  [row]
  (when row
    (-> row
        (update :rules #(when % (try (json/read-str % :key-fn keyword) (catch Exception _ %))))
        (update :enabled #(if (number? %) (pos? %) (boolean %))))))

;; ---------------------------------------------------------------------------
;; Policy CRUD
;; ---------------------------------------------------------------------------

(defn create-policy!
  "Create a new policy. Rules are stored as JSON."
  [ds {:keys [org-id name description policy-type rules priority enabled created-by]}]
  (let [id (util/generate-id)
        row {:id id
             :org-id org-id
             :name name
             :description description
             :policy-type policy-type
             :rules (json/write-str rules)
             :priority (or priority 100)
             :enabled (if (nil? enabled) 1 (if enabled 1 0))
             :created-by created-by}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :policies
                   :values [row]}))
    (log/info "Created policy" {:id id :name name :type policy-type})
    (assoc row :id id :rules rules :enabled (pos? (:enabled row)))))

(defn get-policy
  "Get a policy by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (row->policy
      (jdbc/execute-one! ds
        (sql/format {:select :*
                     :from :policies
                     :where where})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn list-policies
  "List policies, optionally filtered by org-id, type, enabled-only.
   Ordered by priority ASC (lower priority number = evaluated first)."
  [ds & {:keys [org-id policy-type enabled-only]}]
  (let [conditions (cond-> [:and]
                     org-id       (conj [:= :org-id org-id])
                     policy-type  (conj [:= :policy-type policy-type])
                     enabled-only (conj [:= :enabled 1]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :policies
                       :order-by [[:priority :asc] [:name :asc]]}
                where (assoc :where where))]
    (mapv row->policy
      (jdbc/execute! ds
        (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-policy!
  "Update policy fields."
  [ds id updates & {:keys [org-id]}]
  (let [set-map (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  (:name updates)        (assoc :name (:name updates))
                  (:description updates) (assoc :description (:description updates))
                  (:policy-type updates) (assoc :policy-type (:policy-type updates))
                  (:rules updates)       (assoc :rules (json/write-str (:rules updates)))
                  (:priority updates)    (assoc :priority (:priority updates)))
        where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:update :policies
                   :set set-map
                   :where where}))))

(defn delete-policy!
  "Delete a policy and its evaluations.
   When org-id is provided, verifies ownership BEFORE deleting child rows
   to prevent cross-org evaluation log tampering."
  [ds id & {:keys [org-id]}]
  (jdbc/with-transaction [tx ds]
    ;; When org-scoped, verify ownership first — abort if policy doesn't belong to this org
    (when org-id
      (let [owner-check (jdbc/execute-one! tx
                          (sql/format {:select [:id]
                                       :from :policies
                                       :where [:and [:= :id id] [:= :org-id org-id]]})
                          {:builder-fn rs/as-unqualified-kebab-maps})]
        (when-not owner-check
          (throw (ex-info "Policy not found or not owned by org"
                          {:policy-id id :org-id org-id})))))
    ;; Safe to delete: ownership verified (or no org-scoping)
    (jdbc/execute-one! tx
      (sql/format {:delete-from :policy-evaluations
                   :where [:= :policy-id id]}))
    (let [where (if org-id
                  [:and [:= :id id] [:= :org-id org-id]]
                  [:= :id id])]
      (jdbc/execute-one! tx
        (sql/format {:delete-from :policies
                     :where where})))))

(defn toggle-policy!
  "Enable or disable a policy."
  [ds id enabled & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:update :policies
                   :set {:enabled (if enabled 1 0)
                         :updated-at [:raw "CURRENT_TIMESTAMP"]}
                   :where where}))))

;; ---------------------------------------------------------------------------
;; Evaluation logging
;; ---------------------------------------------------------------------------

(defn log-evaluation!
  "Log a policy evaluation result."
  [ds {:keys [policy-id build-id stage-name result reason context]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :policy-evaluations
                   :values [{:id id
                             :policy-id policy-id
                             :build-id build-id
                             :stage-name stage-name
                             :result (if (keyword? result) (name result) (str result))
                             :reason reason
                             :context (when context (pr-str context))}]}))
    id))

(defn list-evaluations
  "List policy evaluations with optional filters.
   When :org-id is provided, joins to policies to ensure org isolation."
  [ds & {:keys [build-id policy-id org-id limit offset]
         :or {limit 50 offset 0}}]
  (let [use-join? (some? org-id)
        ;; Use table-qualified column names only when joining
        conditions (if use-join?
                     (cond-> [:and]
                       build-id  (conj [:= :pe.build-id build-id])
                       policy-id (conj [:= :pe.policy-id policy-id])
                       org-id    (conj [:= :p.org-id org-id]))
                     (cond-> [:and]
                       build-id  (conj [:= :build-id build-id])
                       policy-id (conj [:= :policy-id policy-id])))
        where (when (> (count conditions) 1) conditions)
        query (cond-> (if use-join?
                        {:select [:pe.*]
                         :from [[:policy-evaluations :pe]]
                         :join [[:policies :p] [:= :pe.policy-id :p.id]]
                         :order-by [[:pe.evaluated-at :desc]]
                         :limit limit
                         :offset offset}
                        {:select :*
                         :from :policy-evaluations
                         :order-by [[:evaluated-at :desc]]
                         :limit limit
                         :offset offset})
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))
