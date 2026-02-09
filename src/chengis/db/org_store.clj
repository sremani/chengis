(ns chengis.db.org-store
  "Organization and membership CRUD for multi-tenancy.
   Organizations group resources (jobs, builds, secrets, templates).
   Users belong to organizations via org_memberships with per-org roles."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Row transformers
;; ---------------------------------------------------------------------------

(defn- row->org [row]
  (when row row))

(defn- row->membership [row]
  (when row row))

;; ---------------------------------------------------------------------------
;; Organization CRUD
;; ---------------------------------------------------------------------------

(defn create-org!
  "Create a new organization. Returns the created org map.
   Requires :name and :slug. Optional: :description.
   Slug must be unique and is used for URL-friendly identification."
  [ds {:keys [name slug description]}]
  {:pre [(some? name) (some? slug)]}
  (let [id (util/generate-id)
        row {:id id
             :name name
             :slug slug
             :description description}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :organizations
                   :values [row]}))
    (log/info "Created organization" {:org-id id :slug slug})
    row))

(defn get-org
  "Retrieve an organization by ID."
  [ds org-id]
  (row->org
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :organizations
                   :where [:= :id org-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-org-by-slug
  "Retrieve an organization by slug."
  [ds slug]
  (row->org
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :organizations
                   :where [:= :slug slug]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-orgs
  "List all organizations, ordered by creation date."
  [ds]
  (mapv row->org
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :organizations
                   :order-by [[:created-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn update-org!
  "Update an organization's name and/or description.
   Only non-nil fields in updates are applied."
  [ds org-id updates]
  (let [set-map (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  (:name updates) (assoc :name (:name updates))
                  (:description updates) (assoc :description (:description updates)))]
    (jdbc/execute-one! ds
      (sql/format {:update :organizations
                   :set set-map
                   :where [:= :id org-id]}))
    (log/info "Updated organization" {:org-id org-id :updates (keys updates)})))

(defn delete-org!
  "Delete an organization by ID. Also removes all memberships.
   Wrapped in a transaction so membership removal and org deletion are atomic.
   WARNING: Does NOT cascade to jobs/builds/secrets — those must be
   cleaned up separately or the org should be archived instead."
  [ds org-id]
  (jdbc/with-transaction [tx ds]
    ;; Remove memberships first (foreign key)
    (jdbc/execute-one! tx
      (sql/format {:delete-from :org-memberships
                   :where [:= :org-id org-id]}))
    (let [result (jdbc/execute-one! tx
                   (sql/format {:delete-from :organizations
                                :where [:= :id org-id]}))]
      (log/info "Deleted organization" {:org-id org-id})
      (pos? (:next.jdbc/update-count result 0)))))

;; ---------------------------------------------------------------------------
;; Membership management
;; ---------------------------------------------------------------------------

(defn add-member!
  "Add a user to an organization with a role.
   Roles: admin, developer, viewer (matches existing RBAC system).
   Returns the created membership."
  [ds {:keys [org-id user-id role]}]
  {:pre [(some? org-id) (some? user-id)]}
  (let [id (util/generate-id)
        role (or role "viewer")
        row {:id id
             :org-id org-id
             :user-id user-id
             :role role}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :org-memberships
                   :values [row]}))
    (log/info "Added org member" {:org-id org-id :user-id user-id :role role})
    row))

(defn remove-member!
  "Remove a user from an organization."
  [ds org-id user-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :org-memberships
                              :where [:and
                                      [:= :org-id org-id]
                                      [:= :user-id user-id]]}))]
    (log/info "Removed org member" {:org-id org-id :user-id user-id})
    (pos? (:next.jdbc/update-count result 0))))

(defn update-member-role!
  "Update a member's role within an organization."
  [ds org-id user-id new-role]
  (jdbc/execute-one! ds
    (sql/format {:update :org-memberships
                 :set {:role new-role}
                 :where [:and
                         [:= :org-id org-id]
                         [:= :user-id user-id]]}))
  (log/info "Updated member role" {:org-id org-id :user-id user-id :role new-role}))

(defn get-membership
  "Get a specific user's membership in an organization.
   Returns nil if the user is not a member."
  [ds org-id user-id]
  (row->membership
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :org-memberships
                   :where [:and
                           [:= :org-id org-id]
                           [:= :user-id user-id]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-members
  "List all members of an organization with their roles."
  [ds org-id]
  (mapv row->membership
    (jdbc/execute! ds
      (sql/format {:select [:om.*
                            [:u.username :username]]
                   :from [[:org-memberships :om]]
                   :join [[:users :u] [:= :om.user-id :u.id]]
                   :where [:= :om.org-id org-id]
                   :order-by [[:om.created-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-user-orgs
  "List all organizations a user belongs to, with their role in each."
  [ds user-id]
  (mapv (fn [row]
          {:id (:id row)
           :org-id (:org-id row)
           :org-name (:name row)
           :org-slug (:slug row)
           :role (:role row)
           :created-at (:created-at row)})
    (jdbc/execute! ds
      (sql/format {:select [:om.id :om.org-id :om.role :om.created-at
                            [:o.name :name] [:o.slug :slug]]
                   :from [[:org-memberships :om]]
                   :join [[:organizations :o] [:= :om.org-id :o.id]]
                   :where [:= :om.user-id user-id]
                   :order-by [[:o.name :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn count-members
  "Count members in an organization."
  [ds org-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:select [[[:count :*] :cnt]]
                              :from :org-memberships
                              :where [:= :org-id org-id]})
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (:cnt result 0)))

;; ---------------------------------------------------------------------------
;; Default org helpers
;; ---------------------------------------------------------------------------

(def default-org-id "default-org")

(defn ensure-default-org!
  "Ensure the default organization exists. Idempotent.
   Called during system startup for backward compatibility."
  [ds]
  (when-not (get-org ds default-org-id)
    (jdbc/execute-one! ds
      (sql/format {:insert-into :organizations
                   :values [{:id default-org-id
                             :name "Default"
                             :slug "default"
                             :description "Auto-created default organization"}]}))
    (log/info "Created default organization")))

(defn ensure-user-has-org!
  "Ensure a user has at least one org membership.
   If they have none, add them to the default org with the given role."
  [ds user-id role]
  (let [orgs (list-user-orgs ds user-id)]
    (when (empty? orgs)
      (add-member! ds {:org-id default-org-id
                       :user-id user-id
                       :role (or role "viewer")}))))
