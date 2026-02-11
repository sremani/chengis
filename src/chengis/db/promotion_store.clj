(ns chengis.db.promotion-store
  "CRUD store for artifact promotions and environment artifact tracking.
   Manages the promotion pipeline (dev -> staging -> prod) and tracks
   which build/artifact is currently active in each environment."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

;; ---------------------------------------------------------------------------
;; Artifact Promotions
;; ---------------------------------------------------------------------------

(defn create-promotion!
  "Create a new promotion request. Returns the created row map."
  [ds {:keys [id org-id build-id artifact-id from-environment-id
              to-environment-id promoted-by]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :build-id build-id
             :artifact-id artifact-id
             :from-environment-id from-environment-id
             :to-environment-id to-environment-id
             :promoted-by promoted-by
             :status "pending"}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :artifact-promotions :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn get-promotion
  "Get promotion by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :artifact-promotions
                 :where (if org-id
                          [:and [:= :id id] [:= :org-id org-id]]
                          [:= :id id])})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-promotions
  "List promotions with optional filters."
  [ds & {:keys [org-id build-id to-environment-id status limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id             (conj [:= :org-id org-id])
                     build-id           (conj [:= :build-id build-id])
                     to-environment-id  (conj [:= :to-environment-id to-environment-id])
                     status             (conj [:= :status status]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :artifact-promotions
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn approve-promotion!
  "Approve a pending promotion. Returns true if approved."
  [ds id promoted-by & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :artifact-promotions
                              :set {:status "approved"
                                    :promoted-by promoted-by
                                    :promoted-at [:raw "CURRENT_TIMESTAMP"]}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id] [:= :status "pending"]]
                                       [:and [:= :id id] [:= :status "pending"]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn reject-promotion!
  "Reject a pending promotion with reason. Returns true if rejected."
  [ds id reason & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :artifact-promotions
                              :set {:status "rejected"
                                    :rejection-reason reason}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id] [:= :status "pending"]]
                                       [:and [:= :id id] [:= :status "pending"]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn complete-promotion!
  "Complete a promotion: mark as promoted and insert/supersede environment artifact.
   Uses a transaction to ensure atomicity."
  [ds id & {:keys [org-id release-id]}]
  (jdbc/with-transaction [tx ds]
    ;; Get promotion details
    (let [promo (jdbc/execute-one! tx
                  (sql/format {:select :*
                               :from :artifact-promotions
                               :where [:= :id id]})
                  {:builder-fn rs/as-unqualified-kebab-maps})]
      (when promo
        ;; Mark promotion as promoted
        (jdbc/execute-one! tx
          (sql/format {:update :artifact-promotions
                       :set {:status "promoted"
                             :promoted-at [:raw "CURRENT_TIMESTAMP"]}
                       :where [:= :id id]}))
        ;; Supersede previous active artifacts in target environment
        (jdbc/execute-one! tx
          (sql/format {:update :environment-artifacts
                       :set {:status "superseded"}
                       :where [:and
                               [:= :environment-id (:to-environment-id promo)]
                               [:= :status "active"]]}))
        ;; Insert new active artifact
        (let [ea-row {:id (util/generate-id)
                      :org-id (:org-id promo)
                      :environment-id (:to-environment-id promo)
                      :build-id (:build-id promo)
                      :artifact-id (:artifact-id promo)
                      :release-id release-id
                      :status "active"}]
          (jdbc/execute-one! tx
            (sql/format {:insert-into :environment-artifacts :values [ea-row]})))
        true))))

;; ---------------------------------------------------------------------------
;; Environment Artifacts
;; ---------------------------------------------------------------------------

(defn get-current-artifact
  "Get the currently active artifact for an environment."
  [ds environment-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :environment-artifacts
                 :where [:and
                         [:= :environment-id environment-id]
                         [:= :status "active"]]
                 :limit 1})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-environment-history
  "List all artifacts deployed to an environment, ordered by deployed_at desc."
  [ds environment-id & {:keys [limit offset] :or {limit 50 offset 0}}]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :environment-artifacts
                 :where [:= :environment-id environment-id]
                 :order-by [[:deployed-at :desc]]
                 :limit limit
                 :offset offset})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn count-promotions-by-status
  "Count promotions grouped by status for an org."
  [ds org-id]
  (let [rows (jdbc/execute! ds
               (sql/format {:select [:status [[:count :*] :cnt]]
                            :from :artifact-promotions
                            :where [:= :org-id org-id]
                            :group-by [:status]})
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (into {} (map (fn [r] [(:status r) (:cnt r)])) rows)))
