(ns chengis.db.iac-cost-store
  "CRUD store for IaC cost estimates.
   Each estimate is linked to an IaC plan and stores per-resource
   cost breakdowns as JSON."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.data.json :as json]))

(defn- normalize-estimate
  "Parse resources JSON field for a cost estimate row."
  [e]
  (when e
    (assoc e :resources (when-let [rj (:resources-json e)]
                          (try (json/read-str rj :key-fn keyword)
                               (catch Exception _ []))))))

(defn save-estimate!
  "Save a cost estimate for an IaC plan. Returns the created row map."
  [ds {:keys [id org-id plan-id total-monthly total-hourly currency
              resources estimation-method]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :plan-id plan-id
             :total-monthly (or total-monthly 0.0)
             :total-hourly (or total-hourly 0.0)
             :currency (or currency "USD")
             :resources-json (when resources (json/write-str resources))
             :estimation-method (or estimation-method "builtin")}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :iac-cost-estimates :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (normalize-estimate (merge row {:created-at nil}))))

(defn get-estimate
  "Get cost estimate by plan ID."
  [ds plan-id]
  (normalize-estimate
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :iac-cost-estimates
                   :where [:= :plan-id plan-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-estimates
  "List cost estimates with optional filters, ordered by created_at desc."
  [ds & {:keys [org-id limit]
         :or {limit 50}}]
  (let [query (cond-> {:select :*
                        :from :iac-cost-estimates
                        :order-by [[:created-at :desc]]
                        :limit limit}
                org-id (assoc :where [:= :org-id org-id]))]
    (mapv normalize-estimate
      (jdbc/execute! ds (sql/format query)
        {:builder-fn rs/as-unqualified-kebab-maps}))))
