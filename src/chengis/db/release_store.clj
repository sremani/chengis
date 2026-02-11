(ns chengis.db.release-store
  "CRUD store for releases. Releases are versioned snapshots of builds
   with lifecycle: draft -> published -> deprecated."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.string :as str]))

(defn create-release!
  "Create a new release. Returns the created row map."
  [ds {:keys [id org-id job-id build-id version title notes status created-by]}]
  (let [id (or id (util/generate-id))
        row {:id id
             :org-id (or org-id "default-org")
             :job-id job-id
             :build-id build-id
             :version version
             :title title
             :notes notes
             :status (or status "draft")
             :created-by created-by}]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :releases :values [row]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    row))

(defn get-release
  "Get release by ID, optionally scoped to org."
  [ds id & {:keys [org-id]}]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :releases
                 :where (if org-id
                          [:and [:= :id id] [:= :org-id org-id]]
                          [:= :id id])})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-releases
  "List releases with optional filters."
  [ds & {:keys [org-id job-id status limit offset]
         :or {limit 50 offset 0}}]
  (let [conditions (cond-> [:and]
                     org-id  (conj [:= :org-id org-id])
                     job-id  (conj [:= :job-id job-id])
                     status  (conj [:= :status status]))
        where (when (> (count conditions) 1) conditions)
        query (cond-> {:select :*
                       :from :releases
                       :order-by [[:created-at :desc]]
                       :limit limit
                       :offset offset}
                where (assoc :where where))]
    (jdbc/execute! ds (sql/format query)
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn update-release!
  "Update release fields. Returns update count."
  [ds id updates & {:keys [org-id]}]
  (let [clean (dissoc updates :id :org-id :created-at)
        result (jdbc/execute-one! ds
                 (sql/format {:update :releases
                              :set clean
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (or (:next.jdbc/update-count result) 0)))

(defn publish-release!
  "Transition release from draft to published."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :releases
                              :set {:status "published"
                                    :published-at [:raw "CURRENT_TIMESTAMP"]
                                    :updated-at [:raw "CURRENT_TIMESTAMP"]}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id] [:= :status "draft"]]
                                       [:and [:= :id id] [:= :status "draft"]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn deprecate-release!
  "Transition release to deprecated."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :releases
                              :set {:status "deprecated"
                                    :deprecated-at [:raw "CURRENT_TIMESTAMP"]
                                    :updated-at [:raw "CURRENT_TIMESTAMP"]}
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id] [:= :status "published"]]
                                       [:and [:= :id id] [:= :status "published"]])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn get-latest-release
  "Get the most recently published release for a job."
  [ds org-id job-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :releases
                 :where [:and [:= :org-id org-id] [:= :job-id job-id] [:= :status "published"]]
                 :order-by [[:published-at :desc]]
                 :limit 1})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn suggest-next-version
  "Suggest the next version based on existing releases for a job.
   Looks for semver patterns and increments patch. Falls back to 1.0.N."
  [ds org-id job-id]
  (let [releases (list-releases ds :org-id org-id :job-id job-id :limit 10)
        versions (map :version releases)
        semver-pattern #"^(\d+)\.(\d+)\.(\d+)$"
        latest-semver (first (filter #(re-matches semver-pattern (or % "")) versions))]
    (if latest-semver
      (let [[_ major minor patch] (re-matches semver-pattern latest-semver)]
        (str major "." minor "." (inc (Long/parseLong patch))))
      (str "1.0." (count releases)))))

(defn delete-release!
  "Delete release by ID. Returns true if deleted."
  [ds id & {:keys [org-id]}]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :releases
                              :where (if org-id
                                       [:and [:= :id id] [:= :org-id org-id]]
                                       [:= :id id])}))]
    (pos? (or (:next.jdbc/update-count result) 0))))
