(ns chengis.db.plugin-policy-store
  "CRUD for plugin trust policies.
   Controls which external plugins are allowed to load, scoped by org."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

(defn get-plugin-policy
  "Get the policy for a named plugin. Returns nil if no policy exists."
  [ds plugin-name & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :plugin-policies
                 :where [:and
                         [:= :plugin-name plugin-name]
                         [:= :org-id org-id]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn set-plugin-policy!
  "Create or update a plugin policy (upsert by org_id + plugin_name).
   Keys: :org-id, :plugin-name, :trust-level, :allowed, :created-by."
  [ds {:keys [org-id plugin-name trust-level allowed created-by]}]
  (let [existing (get-plugin-policy ds plugin-name :org-id org-id)]
    (if existing
      ;; Update
      (do (jdbc/execute-one! ds
            (sql/format {:update :plugin-policies
                         :set {:trust-level (or trust-level "untrusted")
                               :allowed (if allowed 1 0)
                               :updated-at (str (java.time.Instant/now))}
                         :where [:= :id (:id existing)]}))
          (assoc existing
            :trust-level (or trust-level (:trust-level existing))
            :allowed (boolean allowed)))
      ;; Insert
      (let [id (util/generate-id)]
        (jdbc/execute-one! ds
          (sql/format {:insert-into :plugin-policies
                       :values [{:id id
                                 :org-id org-id
                                 :plugin-name plugin-name
                                 :trust-level (or trust-level "untrusted")
                                 :allowed (if allowed 1 0)
                                 :created-by created-by}]}))
        {:id id :org-id org-id :plugin-name plugin-name
         :trust-level (or trust-level "untrusted")
         :allowed (boolean allowed) :created-by created-by}))))

(defn list-plugin-policies
  "List all plugin policies, optionally filtered by org."
  [ds & {:keys [org-id]}]
  (let [where (if org-id
                [:= :org-id org-id]
                [:= 1 1])]
    (mapv (fn [row]
            (update row :allowed #(= 1 %)))
      (jdbc/execute! ds
        (sql/format {:select :*
                     :from :plugin-policies
                     :where where
                     :order-by [[:plugin-name :asc]]})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn delete-plugin-policy!
  "Delete a plugin policy by plugin name and org."
  [ds plugin-name & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :plugin-policies
                 :where [:and
                         [:= :plugin-name plugin-name]
                         [:= :org-id org-id]]})))

(defn plugin-allowed?
  "Check whether a plugin is allowed to load for the given org.
   Returns true if an explicit policy with allowed=true exists.
   Returns false if no policy exists or policy has allowed=false."
  [ds plugin-name & {:keys [org-id]}]
  (let [policy (get-plugin-policy ds plugin-name :org-id org-id)]
    (boolean (and policy (= 1 (:allowed policy))))))
