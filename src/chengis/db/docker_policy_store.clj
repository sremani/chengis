(ns chengis.db.docker-policy-store
  "CRUD for Docker image policies.
   Supports allowed-registry, denied-image, and allowed-image policy types.
   Policies are evaluated in priority order (lower number = higher priority)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defn create-docker-policy!
  "Create a Docker image policy.
   Keys: :org-id, :policy-type, :pattern, :action, :description, :priority, :created-by.
   policy-type: 'allowed-registry', 'denied-image', 'allowed-image'
   action: 'allow' or 'deny'
   pattern: glob-like pattern (e.g. 'docker.io/*', 'myrepo/evil-image:*')"
  [ds {:keys [org-id policy-type pattern action description priority created-by]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :docker-policies
                   :values [{:id id
                             :org-id org-id
                             :policy-type policy-type
                             :pattern pattern
                             :action (or action "allow")
                             :description description
                             :priority (or priority 100)
                             :enabled 1
                             :created-by created-by}]}))
    {:id id :org-id org-id :policy-type policy-type :pattern pattern
     :action (or action "allow") :description description
     :priority (or priority 100) :enabled true :created-by created-by}))

(defn list-docker-policies
  "List Docker policies, ordered by priority ascending.
   Options: :org-id, :policy-type, :enabled-only."
  [ds & {:keys [org-id policy-type enabled-only]}]
  (let [conditions (cond-> [[:= 1 1]]
                     org-id       (conj [:= :org-id org-id])
                     policy-type  (conj [:= :policy-type policy-type])
                     enabled-only (conj [:= :enabled 1]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (mapv (fn [row]
            (update row :enabled #(= 1 %)))
      (jdbc/execute! ds
        (sql/format {:select :*
                     :from :docker-policies
                     :where where
                     :order-by [[:priority :asc] [:created-at :asc]]})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn delete-docker-policy!
  "Delete a Docker policy by ID, scoped to org."
  [ds id & {:keys [org-id]}]
  (let [conditions (cond-> [[:= :id id]]
                     org-id (conj [:= :org-id org-id]))
        where (if (= 1 (count conditions))
                (first conditions)
                (into [:and] conditions))]
    (jdbc/execute-one! ds
      (sql/format {:delete-from :docker-policies
                   :where where}))))

(defn toggle-docker-policy!
  "Toggle a Docker policy's enabled state."
  [ds id enabled?]
  (jdbc/execute-one! ds
    (sql/format {:update :docker-policies
                 :set {:enabled (if enabled? 1 0)}
                 :where [:= :id id]})))

(defn- pattern-matches?
  "Check if an image name matches a policy pattern.
   Supports simple glob-like matching:
   - '*' matches any sequence of characters
   - Exact match otherwise
   Examples:
     'docker.io/*' matches 'docker.io/ubuntu:latest'
     'myrepo/bad:*' matches 'myrepo/bad:v1.0'
     'nginx' matches 'nginx' but not 'nginx:latest'
   All non-glob characters are regex-escaped via Pattern/quote to prevent
   regex injection from special characters like +, ?, (), etc."
  [pattern image-name]
  (let [;; Split on *, quote each literal segment, rejoin with .*
        segments (str/split pattern #"\*" -1)
        regex-str (str/join ".*" (map #(java.util.regex.Pattern/quote %) segments))
        regex (re-pattern (str "^" regex-str "$"))]
    (boolean (re-matches regex image-name))))

(defn check-image-allowed
  "Evaluate all Docker policies for an image, in priority order.
   Returns {:allowed bool :reason string :policy-id id}.
   When no policies exist, images are allowed by default."
  [ds image-name & {:keys [org-id]}]
  (let [policies (list-docker-policies ds :org-id org-id :enabled-only true)]
    (if (empty? policies)
      {:allowed true :reason "No Docker policies configured — all images allowed"}
      ;; Walk policies in priority order; first match wins
      (loop [remaining policies]
        (if (empty? remaining)
          {:allowed true :reason "No matching policy — image allowed by default"}
          (let [policy (first remaining)]
            (if (pattern-matches? (:pattern policy) image-name)
              (case (:action policy)
                "allow" {:allowed true
                         :reason (str "Allowed by policy: " (or (:description policy)
                                                                 (:pattern policy)))
                         :policy-id (:id policy)}
                "deny"  {:allowed false
                         :reason (str "Denied by policy: " (or (:description policy)
                                                                (:pattern policy)))
                         :policy-id (:id policy)}
                ;; Unknown action — deny by default
                {:allowed false
                 :reason (str "Unknown action '" (:action policy) "' in policy — denied")
                 :policy-id (:id policy)})
              (recur (rest remaining)))))))))
