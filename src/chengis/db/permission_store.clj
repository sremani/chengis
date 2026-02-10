(ns chengis.db.permission-store
  "CRUD store for resource-level permissions and permission groups.
   Supports direct grants and group-based permissions.
   Feature-flag gated via :fine-grained-rbac."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Direct permission grants
;; ---------------------------------------------------------------------------

(defn grant-permission!
  "Insert a resource permission. Uses ON CONFLICT DO NOTHING for idempotency.
   Returns the created permission map."
  [ds {:keys [org-id user-id resource-type resource-id action granted-by expires-at]}]
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :org-id org-id
                     :user-id user-id
                     :resource-type resource-type
                     :resource-id resource-id
                     :action action}
              granted-by (assoc :granted-by granted-by)
              expires-at (assoc :expires-at expires-at))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :resource-permissions
                   :values [row]
                   :on-conflict [:org-id :user-id :resource-type :resource-id :action]
                   :do-nothing true})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Granted permission" {:id id :user-id user-id :resource-type resource-type
                                    :resource-id resource-id :action action})
    row))

(defn revoke-permission!
  "Delete a permission by id with optional org-id ownership check."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :resource-permissions
                   :where where}))
    (log/info "Revoked permission" {:id id})))

(defn check-permission
  "Check if a user has a specific permission on a resource.
   Checks both direct grants AND group-based grants.
   Returns boolean. Respects expires_at."
  [ds org-id user-id resource-type resource-id action]
  ;; Check direct grant
  (let [direct (jdbc/execute-one! ds
                 (sql/format {:select [[[:count :*] :cnt]]
                              :from :resource-permissions
                              :where [:and
                                      [:= :org-id org-id]
                                      [:= :user-id user-id]
                                      [:= :resource-type resource-type]
                                      [:= :resource-id resource-id]
                                      [:= :action action]
                                      [:or
                                       [:is :expires-at nil]
                                       [:> :expires-at [:raw "CURRENT_TIMESTAMP"]]]]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (if (pos? (:cnt direct 0))
      true
      ;; Check group-based grant
      (let [group-check (jdbc/execute-one! ds
                          (sql/format {:select [[[:count :*] :cnt]]
                                       :from [[:permission-group-members :pgm]]
                                       :join [[:permission-group-entries :pge]
                                              [:= :pgm.group-id :pge.group-id]]
                                       :where [:and
                                               [:= :pgm.user-id user-id]
                                               [:= :pge.resource-type resource-type]
                                               [:= :pge.resource-id resource-id]
                                               [:= :pge.action action]]})
                          {:builder-fn rs/as-unqualified-kebab-maps})]
        (pos? (:cnt group-check 0))))))

(defn list-user-permissions
  "List all direct permissions for a user with optional filters."
  [ds user-id & {:keys [org-id resource-type]}]
  (let [conditions (cond-> [:and [:= :user-id user-id]]
                     org-id (conj [:= :org-id org-id])
                     resource-type (conj [:= :resource-type resource-type]))]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :resource-permissions
                   :where conditions
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-org-permissions
  "List all permissions for an organization."
  [ds org-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :resource-permissions
                 :where [:= :org-id org-id]
                 :order-by [[:created-at :desc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-resource-permissions
  "List all permissions for a specific resource."
  [ds resource-type resource-id & {:keys [org-id]}]
  (let [conditions (cond-> [:and
                            [:= :resource-type resource-type]
                            [:= :resource-id resource-id]]
                     org-id (conj [:= :org-id org-id]))]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :resource-permissions
                   :where conditions
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn effective-permissions
  "Return all effective permissions for a user (direct + group-based, merged, deduplicated).
   Returns a vector of maps with :resource-type :resource-id :action :source."
  [ds org-id user-id]
  (let [;; Direct permissions
        direct (jdbc/execute! ds
                 (sql/format {:select [:resource-type :resource-id :action]
                              :from :resource-permissions
                              :where [:and
                                      [:= :org-id org-id]
                                      [:= :user-id user-id]
                                      [:or
                                       [:is :expires-at nil]
                                       [:> :expires-at [:raw "CURRENT_TIMESTAMP"]]]]})
                 {:builder-fn rs/as-unqualified-kebab-maps})
        direct-with-source (mapv #(assoc % :source "direct") direct)
        ;; Group-based permissions
        group-based (jdbc/execute! ds
                      (sql/format {:select [:pge.resource-type :pge.resource-id :pge.action]
                                   :from [[:permission-group-members :pgm]]
                                   :join [[:permission-group-entries :pge]
                                          [:= :pgm.group-id :pge.group-id]
                                         [:permission-groups :pg]
                                          [:= :pgm.group-id :pg.id]]
                                   :where [:and
                                           [:= :pgm.user-id user-id]
                                           [:= :pg.org-id org-id]]})
                      {:builder-fn rs/as-unqualified-kebab-maps})
        group-with-source (mapv #(assoc % :source "group") group-based)
        ;; Merge and deduplicate by [resource-type resource-id action]
        all-perms (concat direct-with-source group-with-source)
        deduped (vals (reduce (fn [acc perm]
                                (let [k [(:resource-type perm)
                                         (:resource-id perm)
                                         (:action perm)]]
                                  (if (contains? acc k)
                                    acc
                                    (assoc acc k perm))))
                              {}
                              all-perms))]
    (vec deduped)))

;; ---------------------------------------------------------------------------
;; Permission groups
;; ---------------------------------------------------------------------------

(defn create-group!
  "Create a permission group. Returns the created group map."
  [ds {:keys [org-id name description created-by]}]
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :org-id org-id
                     :name name}
              description (assoc :description description)
              created-by (assoc :created-by created-by))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :permission-groups
                   :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (log/info "Created permission group" {:id id :name name :org-id org-id})
    row))

(defn get-group
  "Get a permission group by id with optional org-id filter."
  [ds group-id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id group-id] [:= :org-id org-id]]
                [:= :id group-id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :permission-groups
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-groups
  "List all permission groups with optional org-id filter."
  [ds & {:keys [org-id]}]
  (let [query (cond-> {:select :*
                       :from :permission-groups
                       :order-by [[:created-at :desc]]}
                org-id (assoc :where [:= :org-id org-id]))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn delete-group!
  "Delete a permission group and its associated entries and members.
   Cascading via DB constraints, but also deletes explicitly for safety."
  [ds group-id & {:keys [org-id]}]
  ;; Delete entries and members first
  (jdbc/execute-one! ds
    (sql/format {:delete-from :permission-group-entries
                 :where [:= :group-id group-id]}))
  (jdbc/execute-one! ds
    (sql/format {:delete-from :permission-group-members
                 :where [:= :group-id group-id]}))
  ;; Delete the group itself
  (let [where (if org-id
                [:and [:= :id group-id] [:= :org-id org-id]]
                [:= :id group-id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :permission-groups
                   :where where}))
    (log/info "Deleted permission group" {:id group-id})))

;; ---------------------------------------------------------------------------
;; Group entries (resource permissions within a group)
;; ---------------------------------------------------------------------------

(defn add-group-entry!
  "Add a permission entry to a group. Uses ON CONFLICT DO NOTHING for idempotency.
   Returns the created entry map."
  [ds {:keys [group-id resource-type resource-id action]}]
  (let [id (util/generate-id)
        row {:id id
             :group-id group-id
             :resource-type resource-type
             :resource-id resource-id
             :action action}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :permission-group-entries
                   :values [row]
                   :on-conflict [:group-id :resource-type :resource-id :action]
                   :do-nothing true})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn remove-group-entry!
  "Remove a permission entry from a group by entry id."
  [ds entry-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :permission-group-entries
                 :where [:= :id entry-id]})))

(defn list-group-entries
  "List all permission entries for a group."
  [ds group-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :permission-group-entries
                 :where [:= :group-id group-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; ---------------------------------------------------------------------------
;; Group members
;; ---------------------------------------------------------------------------

(defn add-group-member!
  "Add a user to a permission group. Uses ON CONFLICT DO NOTHING for idempotency.
   Returns the created membership map."
  [ds {:keys [group-id user-id assigned-by]}]
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :group-id group-id
                     :user-id user-id}
              assigned-by (assoc :assigned-by assigned-by))]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :permission-group-members
                   :values [row]
                   :on-conflict [:group-id :user-id]
                   :do-nothing true})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn remove-group-member!
  "Remove a user from a permission group."
  [ds group-id user-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :permission-group-members
                 :where [:and [:= :group-id group-id] [:= :user-id user-id]]})))

(defn list-group-members
  "List all members of a permission group."
  [ds group-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :permission-group-members
                 :where [:= :group-id group-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
