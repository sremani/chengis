(ns chengis.db.template-store
  "Pipeline template persistence — manages reusable pipeline templates."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; CRUD operations
;; ---------------------------------------------------------------------------

(defn create-template!
  "Create a new pipeline template. Returns the template ID.
   When :org-id is provided, scopes the template to that organization."
  [ds {:keys [name description format content parameters created-by org-id]}]
  (let [id (util/generate-id)]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :pipeline-templates
                     :values [(cond-> {:id id
                                       :name name
                                       :description description
                                       :format (or format "edn")
                                       :content content
                                       :parameters parameters
                                       :created-by created-by}
                                org-id (assoc :org-id org-id))]}))
      id
      (catch Exception e
        (log/warn "Failed to create template:" (.getMessage e))
        nil))))

(defn get-template
  "Get a template by ID. When org-id is provided, verifies the template belongs to that org."
  [ds template-id & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :pipeline-templates
                 :where (if org-id
                          [:and [:= :id template-id] [:= :org-id org-id]]
                          [:= :id template-id])})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-template-by-name
  "Get a template by name. When org-id is provided, scopes to that org."
  [ds template-name & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :pipeline-templates
                 :where (if org-id
                          [:and [:= :name template-name] [:= :org-id org-id]]
                          [:= :name template-name])})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-templates
  "List all templates, ordered by name.
   When org-id is provided, only returns templates in that org."
  [ds & {:keys [org-id]}]
  (jdbc/execute! ds
    (sql/format (cond-> {:select :*
                          :from :pipeline-templates
                          :order-by [[:name :asc]]}
                  org-id (assoc :where [:= :org-id org-id])))
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-template!
  "Update an existing template by ID. Bumps version.
   When org-id is provided, verifies the template belongs to that org."
  [ds template-id {:keys [description format content parameters]} & {:keys [org-id]}]
  (let [sets (cond-> {:version [:+ :version 1]
                      :updated-at [:raw "CURRENT_TIMESTAMP"]}
               description (assoc :description description)
               format      (assoc :format format)
               content     (assoc :content content)
               parameters  (assoc :parameters parameters))]
    (jdbc/execute-one! ds
      (sql/format {:update :pipeline-templates
                   :set sets
                   :where (if org-id
                            [:and [:= :id template-id] [:= :org-id org-id]]
                            [:= :id template-id])}))))

(defn delete-template!
  "Delete a template by ID. When org-id is provided, verifies the template belongs to that org."
  [ds template-id & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :pipeline-templates
                 :where (if org-id
                          [:and [:= :id template-id] [:= :org-id org-id]]
                          [:= :id template-id])})))

(defn count-templates
  "Count all templates. When org-id is provided, counts only within that org."
  [ds & {:keys [org-id]}]
  (:count
    (jdbc/execute-one! ds
      (sql/format (cond-> {:select [[[:count :*] :count]]
                            :from :pipeline-templates}
                    org-id (assoc :where [:= :org-id org-id])))
      {:builder-fn rs/as-unqualified-kebab-maps})))
