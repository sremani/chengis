(ns chengis.db.shared-resource-store
  "CRUD store for cross-organization resource sharing grants.
   Supports sharing agent labels and pipeline templates across orgs.
   Feature-flag gated via :cross-org-sharing."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Row transformer
;; ---------------------------------------------------------------------------

(defn- row->grant [row]
  (when row row))

;; ---------------------------------------------------------------------------
;; Grant CRUD
;; ---------------------------------------------------------------------------

(defn create-grant!
  "Insert a cross-org resource sharing grant. Uses ON CONFLICT DO NOTHING
   so duplicate grants are silently ignored (idempotent).
   Returns the grant map."
  [ds {:keys [source-org-id target-org-id resource-type resource-id granted-by expires-at]}]
  {:pre [(some? source-org-id) (some? target-org-id)
         (some? resource-type) (some? resource-id)]}
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :source-org-id source-org-id
                     :target-org-id target-org-id
                     :resource-type resource-type
                     :resource-id resource-id
                     :granted-by granted-by}
              expires-at (assoc :expires-at expires-at))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :shared-resource-grants
                   :values [row]
                   :on-conflict [:source-org-id :target-org-id :resource-type :resource-id]
                   :do-nothing true}))
    (log/info "Created shared resource grant"
              {:id id :source-org-id source-org-id
               :target-org-id target-org-id :resource-type resource-type})
    row))

(defn revoke-grant!
  "Delete a grant by id. When source-org-id is provided, also checks ownership
   so only the granting org can revoke. Returns true if a row was deleted."
  [ds id & {:keys [source-org-id]}]
  (let [where (if source-org-id
                [:and [:= :id id] [:= :source-org-id source-org-id]]
                [:= :id id])
        result (jdbc/execute-one! ds
                 (sql/format {:delete-from :shared-resource-grants
                              :where where}))]
    (log/info "Revoked shared resource grant" {:id id :source-org-id source-org-id})
    (pos? (:next.jdbc/update-count result 0))))

(defn get-grant
  "Retrieve a single grant by id."
  [ds id]
  (row->grant
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :shared-resource-grants
                   :where [:= :id id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-grants-from
  "List all grants FROM a source org (resources this org has shared).
   Optionally filter by resource-type."
  [ds source-org-id & {:keys [resource-type]}]
  (let [where (if resource-type
                [:and [:= :source-org-id source-org-id]
                      [:= :resource-type resource-type]]
                [:= :source-org-id source-org-id])]
    (mapv row->grant
      (jdbc/execute! ds
        (sql/format {:select :*
                     :from :shared-resource-grants
                     :where where
                     :order-by [[:created-at :desc]]})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn list-grants-to
  "List all grants TO a target org (resources shared with this org).
   Optionally filter by resource-type."
  [ds target-org-id & {:keys [resource-type]}]
  (let [where (if resource-type
                [:and [:= :target-org-id target-org-id]
                      [:= :resource-type resource-type]]
                [:= :target-org-id target-org-id])]
    (mapv row->grant
      (jdbc/execute! ds
        (sql/format {:select :*
                     :from :shared-resource-grants
                     :where where
                     :order-by [[:created-at :desc]]})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn list-shared-resource-ids
  "Return a vector of resource-id strings shared with the target org
   for the given resource-type. Filters out expired grants
   (expires_at < CURRENT_TIMESTAMP). Grants with NULL expires_at never expire.
   This is the main query used by template_store and dispatcher."
  [ds target-org-id resource-type]
  (mapv :resource-id
    (jdbc/execute! ds
      (sql/format {:select [:resource-id]
                   :from :shared-resource-grants
                   :where [:and
                           [:= :target-org-id target-org-id]
                           [:= :resource-type resource-type]
                           [:or
                            [:= :expires-at nil]
                            [:>= :expires-at [:raw "CURRENT_TIMESTAMP"]]]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn has-grant?
  "Check if a specific grant exists (non-expired) between source and target
   for the given resource-type and resource-id. Returns boolean."
  [ds source-org-id target-org-id resource-type resource-id]
  (some?
    (jdbc/execute-one! ds
      (sql/format {:select [[[:raw "1"] :found]]
                   :from :shared-resource-grants
                   :where [:and
                           [:= :source-org-id source-org-id]
                           [:= :target-org-id target-org-id]
                           [:= :resource-type resource-type]
                           [:= :resource-id resource-id]
                           [:or
                            [:= :expires-at nil]
                            [:>= :expires-at [:raw "CURRENT_TIMESTAMP"]]]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn cleanup-expired-grants!
  "Delete all expired grants (expires_at < CURRENT_TIMESTAMP).
   Returns the count of deleted rows."
  [ds]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :shared-resource-grants
                              :where [:and
                                      [:!= :expires-at nil]
                                      [:< :expires-at [:raw "CURRENT_TIMESTAMP"]]]}))]
    (let [cnt (:next.jdbc/update-count result 0)]
      (when (pos? cnt)
        (log/info "Cleaned up expired shared resource grants" {:count cnt}))
      cnt)))
