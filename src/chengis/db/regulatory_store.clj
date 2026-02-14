(ns chengis.db.regulatory-store
  "CRUD store for regulatory_checks table.
   Uses UPSERT pattern (ON CONFLICT DO UPDATE) for idempotent check updates."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

(defn upsert-check!
  "Insert or update a regulatory check. On conflict (org_id, framework, control_id),
   updates status, evidence_summary, last_assessed_at, and updated_at."
  [ds {:keys [org-id framework control-id control-name status evidence-summary]}]
  (let [id (util/generate-id)
        org-id (or org-id "default-org")]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :regulatory-checks
                   :values [{:id id
                             :org-id org-id
                             :framework framework
                             :control-id control-id
                             :control-name control-name
                             :status (or status "not-assessed")
                             :evidence-summary evidence-summary
                             :last-assessed-at [:raw "CURRENT_TIMESTAMP"]}]
                   :on-conflict [:org-id :framework :control-id]
                   :do-update-set {:status (or status "not-assessed")
                                   :control-name control-name
                                   :evidence-summary evidence-summary
                                   :last-assessed-at [:raw "CURRENT_TIMESTAMP"]
                                   :updated-at [:raw "CURRENT_TIMESTAMP"]}}))
    (log/debug "Upserted regulatory check" {:framework framework :control-id control-id
                                             :status status})))

(defn get-framework-checks
  "Get all regulatory checks for a framework, optionally scoped to org."
  [ds framework & {:keys [org-id]}]
  (let [where (if org-id
                [:and [:= :framework framework] [:= :org-id org-id]]
                [:= :framework framework])]
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :regulatory-checks
                   :where where
                   :order-by [[:control-id :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-all-framework-checks
  "Fetch all checks for all frameworks in a single query, optionally scoped to org.
   Returns a map of framework-name -> [check ...]."
  [ds & {:keys [org-id]}]
  (let [where-clause (when org-id [:= :org-id org-id])
        query (cond-> {:select :*
                       :from :regulatory-checks
                       :order-by [[:framework :asc] [:control-id :asc]]}
                where-clause (assoc :where where-clause))
        rows (jdbc/execute! ds
               (sql/format query)
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (group-by :framework rows)))

(defn get-readiness-summary
  "Aggregate readiness summary: for each framework, count passing/failing/not-assessed.
   Returns [{:framework \"soc2\" :total n :passing n :failing n :not-assessed n :percentage pct}]."
  [ds & {:keys [org-id]}]
  (let [where-clause (when org-id [:= :org-id org-id])
        query (cond-> {:select [:framework
                                [[:count :*] :total]
                                [[:sum [:case [:= :status [:inline "passing"]] [:inline 1]
                                        :else [:inline 0]]] :passing]
                                [[:sum [:case [:= :status [:inline "failing"]] [:inline 1]
                                        :else [:inline 0]]] :failing]
                                [[:sum [:case [:= :status [:inline "not-assessed"]] [:inline 1]
                                        :else [:inline 0]]] :not-assessed]]
                       :from :regulatory-checks
                       :group-by [:framework]
                       :order-by [[:framework :asc]]}
                where-clause (assoc :where where-clause))
        rows (jdbc/execute! ds
               (sql/format query)
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (mapv (fn [row]
            (let [total (or (:total row) 0)
                  passing (or (:passing row) 0)
                  pct (if (pos? total)
                        (* 100.0 (/ (double passing) (double total)))
                        0.0)]
              (assoc row :percentage pct)))
          rows)))

(defn list-frameworks
  "List distinct framework names, optionally scoped to org."
  [ds & {:keys [org-id]}]
  (let [where-clause (when org-id [:= :org-id org-id])
        query (cond-> {:select-distinct [:framework]
                       :from :regulatory-checks
                       :order-by [[:framework :asc]]}
                where-clause (assoc :where where-clause))
        rows (jdbc/execute! ds
               (sql/format query)
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (mapv :framework rows)))
