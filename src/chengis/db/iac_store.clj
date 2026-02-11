(ns chengis.db.iac-store
  "CRUD store for Infrastructure-as-Code projects and plans.
   Projects link jobs to IaC tool types (terraform, pulumi, cloudformation).
   Plans track plan/apply executions with resource change counts."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(defn- normalize-bool
  "Convert SQLite integer (0/1) or nil to boolean."
  [v]
  (if (number? v) (pos? v) (boolean v)))

(defn- normalize-project
  "Normalize boolean fields and parse config JSON for a project row."
  [p]
  (when p
    (-> p
        (update :auto-detect normalize-bool)
        (assoc :config (when-let [cj (:config-json p)]
                         (try (json/read-str cj :key-fn keyword)
                              (catch Exception _ nil)))))))

(defn- normalize-plan
  "Parse plan JSON field for a plan row."
  [p]
  (when p
    (assoc p :plan (when-let [pj (:plan-json p)]
                     (try (json/read-str pj :key-fn keyword)
                          (catch Exception _ nil))))))

;; ---------------------------------------------------------------------------
;; Projects
;; ---------------------------------------------------------------------------

(defn create-project!
  "Create a new IaC project. Returns the created row map."
  [ds {:keys [id org-id job-id tool-type working-dir config auto-detect]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :job-id job-id
             :tool-type (name (or tool-type "terraform"))
             :working-dir (or working-dir ".")
             :config-json (when config (json/write-str config))
             :auto-detect (if auto-detect 1 0)}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :iac-projects :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-project (merge row {:created-at nil :updated-at nil}))))

(defn get-project
  "Get IaC project by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (normalize-project
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :iac-projects
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-project-by-job
  "Get IaC project by org and job ID."
  [ds org-id job-id]
  (normalize-project
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :iac-projects
                   :where [:and [:= :org-id org-id] [:= :job-id job-id]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-projects
  "List IaC projects with optional filters, ordered by updated_at desc."
  [ds & {:keys [org-id tool-type]}]
  (let [conditions (cond-> [:and]
                     org-id    (conj [:= :org-id org-id])
                     tool-type (conj [:= :tool-type (name tool-type)]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :iac-projects
                       :order-by [[:updated-at :desc]]}
                where (assoc :where where))]
    (mapv normalize-project
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-project!
  "Update IaC project fields. Returns update count."
  [ds id updates & {:keys [org-id]}]
  (let [clean (-> updates
                  (dissoc :id :org-id :created-at :config)
                  (cond->
                    (contains? updates :auto-detect)
                    (assoc :auto-detect (if (:auto-detect updates) 1 0))
                    (contains? updates :config)
                    (assoc :config-json (when-let [c (:config updates)]
                                          (json/write-str c)))
                    (contains? updates :tool-type)
                    (update :tool-type name)))
        result (jdbc/execute-one! ds
                 (sql/format {:update :iac-projects
                              :set clean
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (or (:next.jdbc/update-count result) 0)))

(defn delete-project!
  "Delete IaC project by ID. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :iac-projects
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

;; ---------------------------------------------------------------------------
;; Plans
;; ---------------------------------------------------------------------------

(defn create-plan!
  "Create a new IaC plan record. Returns the created row map."
  [ds {:keys [id org-id project-id build-id action status plan-json
              resources-add resources-change resources-destroy
              output error-output duration-ms initiated-by]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :project-id project-id
             :build-id build-id
             :action (or action "plan")
             :status (or status "pending")
             :plan-json (when plan-json
                          (if (string? plan-json)
                            plan-json
                            (json/write-str plan-json)))
             :resources-add (or resources-add 0)
             :resources-change (or resources-change 0)
             :resources-destroy (or resources-destroy 0)
             :output output
             :error-output error-output
             :duration-ms duration-ms
             :initiated-by initiated-by}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :iac-plans :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-plan (merge row {:approved-by nil :approved-at nil
                                :created-at nil :updated-at nil}))))

(defn get-plan
  "Get IaC plan by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (normalize-plan
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :iac-plans
                   :where (if org-id
                            [:and [:= :id id] [:= :org-id org-id]]
                            [:= :id id])})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-plans
  "List IaC plans with optional filters, ordered by created_at desc."
  [ds & {:keys [org-id project-id build-id status limit]
         :or {limit 50}}]
  (let [conditions (cond-> [:and]
                     org-id     (conj [:= :org-id org-id])
                     project-id (conj [:= :project-id project-id])
                     build-id   (conj [:= :build-id build-id])
                     status     (conj [:= :status status]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :iac-plans
                       :order-by [[:created-at :desc]]
                       :limit limit}
                where (assoc :where where))]
    (mapv normalize-plan
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-plan!
  "Update IaC plan fields. Returns update count."
  [ds id updates]
  (let [clean (-> updates
                  (dissoc :id :org-id :project-id :created-at :plan)
                  (cond->
                    (contains? updates :plan-json)
                    (update :plan-json (fn [pj]
                                         (if (string? pj)
                                           pj
                                           (when pj (json/write-str pj)))))))
        result (jdbc/execute-one! ds
                 (sql/format {:update :iac-plans
                              :set clean
                              :where [:= :id id]}))]
    (or (:next.jdbc/update-count result) 0)))

(defn approve-plan!
  "Approve an IaC plan. Sets status to 'approved' with approver and timestamp."
  [ds id approved-by & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :iac-plans
                              :set {:status "approved"
                                    :approved-by approved-by
                                    :approved-at [:raw "CURRENT_TIMESTAMP"]}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]
                                        [:= :status "pending"]]
                                       [:and [:= :id id] [:= :status "pending"]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn get-latest-plan
  "Get the most recent plan for a project."
  [ds project-id]
  (normalize-plan
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :iac-plans
                   :where [:= :project-id project-id]
                   :order-by [[:created-at :desc]]
                   :limit 1})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn count-plans-by-status
  "Count plans grouped by status for an org.
   Returns a map like {\"pending\" 3, \"succeeded\" 5}."
  [ds org-id]
  (let [rows (jdbc/execute! ds
               (sql/format {:select [:status [[:count :*] :cnt]]
                            :from :iac-plans
                            :where [:= :org-id org-id]
                            :group-by [:status]})
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (into {} (map (fn [r] [(:status r) (:cnt r)])) rows)))
