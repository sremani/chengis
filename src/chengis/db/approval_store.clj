(ns chengis.db.approval-store
  "Approval gate persistence — manages approval gates for pipeline stages."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Create / Query gates
;; ---------------------------------------------------------------------------

(defn create-gate!
  "Create an approval gate for a build stage. Returns the gate ID."
  [ds {:keys [build-id stage-name required-role message timeout-minutes]}]
  (let [id (util/generate-id)
        row {:id id
             :build-id build-id
             :stage-name stage-name
             :status "pending"
             :required-role (or required-role "developer")
             :message message
             :timeout-minutes (or timeout-minutes 1440)}]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :approval-gates
                     :values [row]}))
      id
      (catch Exception e
        (log/warn "Failed to create approval gate:" (.getMessage e))
        nil))))

(defn get-gate
  "Get a single approval gate by ID."
  [ds gate-id]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :approval-gates
                 :where [:= :id gate-id]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-gate-for-build-stage
  "Get the approval gate for a specific build and stage name."
  [ds build-id stage-name]
  (jdbc/execute-one! ds
    (sql/format {:select :*
                 :from :approval-gates
                 :where [:and
                         [:= :build-id build-id]
                         [:= :stage-name stage-name]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-pending-gates
  "List all pending approval gates."
  [ds]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :approval-gates
                 :where [:= :status "pending"]
                 :order-by [[:created-at :desc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-gates-for-build
  "List all gates for a specific build."
  [ds build-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :approval-gates
                 :where [:= :build-id build-id]
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn count-pending-gates
  "Count pending approval gates."
  [ds]
  (:count
    (jdbc/execute-one! ds
      (sql/format {:select [[[:count :*] :count]]
                   :from :approval-gates
                   :where [:= :status "pending"]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; ---------------------------------------------------------------------------
;; Approve / Reject / Timeout
;; ---------------------------------------------------------------------------

(defn approve-gate!
  "Approve an approval gate. Returns the update count."
  [ds gate-id user-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :approval-gates
                              :set {:status "approved"
                                    :approved-by user-id
                                    :approved-at [:datetime "now"]}
                              :where [:and
                                      [:= :id gate-id]
                                      [:= :status "pending"]]}))]
    (:next.jdbc/update-count result 0)))

(defn reject-gate!
  "Reject an approval gate. Returns the update count."
  [ds gate-id user-id]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:update :approval-gates
                              :set {:status "rejected"
                                    :rejected-by user-id
                                    :rejected-at [:datetime "now"]}
                              :where [:and
                                      [:= :id gate-id]
                                      [:= :status "pending"]]}))]
    (:next.jdbc/update-count result 0)))

(defn timeout-gate!
  "Mark an approval gate as timed out."
  [ds gate-id]
  (jdbc/execute-one! ds
    (sql/format {:update :approval-gates
                 :set {:status "timed_out"}
                 :where [:and
                         [:= :id gate-id]
                         [:= :status "pending"]]})))

(defn cleanup-old-gates!
  "Delete gates older than retention-days. Returns number deleted."
  [ds retention-days]
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :approval-gates
                              :where [:< :created-at
                                      [:datetime "now" (str "-" retention-days " days")]]}))]
    (:next.jdbc/update-count result 0)))
