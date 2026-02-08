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
  "Create a new pipeline template. Returns the template ID."
  [ds {:keys [name description format content parameters created-by]}]
  (let [id (util/generate-id)]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :pipeline-templates
                     :values [{:id id
                               :name name
                               :description description
                               :format (or format "edn")
                               :content content
                               :parameters parameters
                               :created-by created-by}]}))
      id
      (catch Exception e
        (log/warn "Failed to create template:" (.getMessage e))
        nil))))

(defn get-template
  "Get a template by ID."
  [ds template-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :pipeline-templates
                 :where [:= :id template-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-template-by-name
  "Get a template by name (case-insensitive)."
  [ds template-name]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :pipeline-templates
                 :where [:= :name template-name]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-templates
  "List all templates, ordered by name."
  [ds]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :pipeline-templates
                 :order-by [[:name :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-template!
  "Update an existing template by ID. Bumps version."
  [ds template-id {:keys [description format content parameters]}]
  (let [sets (cond-> {:version [:+ :version 1]
                      :updated-at [:datetime "now"]}
               description (assoc :description description)
               format      (assoc :format format)
               content     (assoc :content content)
               parameters  (assoc :parameters parameters))]
    (jdbc/execute-one! ds
      (sql/format {:update :pipeline-templates
                   :set sets
                   :where [:= :id template-id]}))))

(defn delete-template!
  "Delete a template by ID."
  [ds template-id]
  (jdbc/execute-one! ds
    (sql/format {:delete-from :pipeline-templates
                 :where [:= :id template-id]})))

(defn count-templates
  "Count all templates."
  [ds]
  (:count
    (jdbc/execute-one! ds
      (sql/format {:select [[[:count :*] :count]]
                   :from :pipeline-templates})
      {:builder-fn rs/as-unqualified-kebab-maps})))
