(ns chengis.db.compliance-store
  "CRUD for compliance report templates and report runs."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Report Templates
;; ---------------------------------------------------------------------------

(defn create-report-template!
  "Create a compliance report template."
  [ds {:keys [org-id report-type title description filters created-by]}]
  (let [id (util/generate-id)
        row {:id id
             :org-id org-id
             :report-type report-type
             :title title
             :description description
             :filters (when filters (json/write-str filters))
             :created-by created-by}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :compliance-reports
                   :values [row]}))
    (log/info "Created compliance report template" {:id id :type report-type})
    (assoc row :filters filters)))

(defn get-report-template
  "Get a compliance report template by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])
        row (jdbc/execute-one! ds
              (sql/format {:select :*
                           :from :compliance-reports
                           :where where})
              {:builder-fn rs/as-unqualified-kebab-maps})]
    (when row
      (update row :filters #(when % (json/read-str % :key-fn keyword))))))

(defn list-report-templates
  "List compliance report templates, optionally filtered by org-id and/or type."
  [ds & {:keys [org-id report-type]}]
  (let [conditions (cond-> [:and]
                     org-id      (conj [:= :org-id org-id])
                     report-type (conj [:= :report-type report-type]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :compliance-reports
                       :order-by [[:created-at :desc]]}
                where (assoc :where where))]
    (mapv (fn [row]
            (update row :filters #(when % (json/read-str % :key-fn keyword))))
      (jdbc/execute! ds
        (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn update-report-template!
  "Update a compliance report template."
  [ds id updates & {:keys [org-id]}]
  (let [set-map (cond-> {:updated-at [:raw "CURRENT_TIMESTAMP"]}
                  (:title updates)       (assoc :title (:title updates))
                  (:description updates) (assoc :description (:description updates))
                  (:filters updates)     (assoc :filters (json/write-str (:filters updates)))
                  (:report-type updates) (assoc :report-type (:report-type updates)))
        where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:update :compliance-reports
                   :set set-map
                   :where where}))))

(defn delete-report-template!
  "Delete a compliance report template by ID."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :compliance-reports
                   :where where}))))

;; ---------------------------------------------------------------------------
;; Report Runs
;; ---------------------------------------------------------------------------

(defn create-report-run!
  "Create a new compliance report run."
  [ds {:keys [report-id org-id generated-by period-start period-end]}]
  (let [id (util/generate-id)
        row {:id id
             :report-id report-id
             :org-id org-id
             :status "pending"
             :generated-by generated-by
             :period-start period-start
             :period-end period-end}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :compliance-report-runs
                   :values [row]}))
    row))

(defn update-report-run!
  "Update a report run (status, summary, hash, completed-at)."
  [ds id updates]
  (let [set-map (cond-> {}
                  (:status updates)       (assoc :status (:status updates))
                  (:summary updates)      (assoc :summary (:summary updates))
                  (:report-hash updates)  (assoc :report-hash (:report-hash updates))
                  (:completed-at updates) (assoc :completed-at (:completed-at updates)))]
    (jdbc/execute-one! ds
      (sql/format {:update :compliance-report-runs
                   :set set-map
                   :where [:= :id id]}))))

(defn get-report-run
  "Get a single report run by ID."
  [ds id & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :id id] [:= :org-id org-id]]
                [:= :id id])]
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :compliance-report-runs
                   :where where})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-report-runs
  "List report runs, optionally filtered by report-id or org-id."
  [ds & {:keys [report-id org-id limit offset]
         :or {limit 20 offset 0}}]
  (let [conditions (cond-> [:and]
                     report-id (conj [:= :report-id report-id])
                     org-id    (conj [:= :org-id org-id]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :compliance-report-runs
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds
      (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))
