(ns chengis.db.notification-store
  "Storage for build notification records in SQLite."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]))

(defn save-notification!
  "Save a notification record to the database."
  [ds {:keys [build-id type status details]}]
  (let [id (util/generate-id)]
    (jdbc/execute-one! ds
      (sql/format {:insert-into :build-notifications
                   :values [{:id id
                             :build-id build-id
                             :type (name type)
                             :status (name (or status :pending))
                             :details details}]}))
    {:id id :build-id build-id :type type :status status}))

(defn update-notification-status!
  "Update the status and sent-at time of a notification."
  [ds notification-id status & {:keys [details]}]
  (jdbc/execute-one! ds
    (sql/format {:update :build-notifications
                 :set (cond-> {:status (name status)}
                        (= status :sent) (assoc :sent-at [:raw "datetime('now')"])
                        details          (assoc :details details))
                 :where [:= :id notification-id]})))

(defn list-notifications
  "List all notifications for a build."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :build-notifications
                 :where [:= :build-id build-id]
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))
