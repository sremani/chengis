(ns chengis.db.approval-store
  "Approval gate persistence — manages approval gates for pipeline stages.
   Supports multi-approver workflows: gates can specify an approver group
   and minimum approval count. Individual decisions are tracked in the
   approval_responses table."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [honey.sql :as sql]
            [clojure.data.json :as json]
            [chengis.util :as util]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Create / Query gates
;; ---------------------------------------------------------------------------

(defn create-gate!
  "Create an approval gate for a build stage. Returns the gate ID.
   Supports multi-approver via :approver-group (vector of user-ids) and
   :min-approvals (minimum number of approvals needed, default 1)."
  [ds {:keys [build-id stage-name required-role message timeout-minutes
              approver-group min-approvals]}]
  (let [id (util/generate-id)
        row (cond-> {:id id
                     :build-id build-id
                     :stage-name stage-name
                     :status "pending"
                     :required-role (or required-role "developer")
                     :message message
                     :timeout-minutes (or timeout-minutes 1440)
                     :min-approvals (or min-approvals 1)}
              approver-group (assoc :approver-group
                                    (json/write-str (vec approver-group))))]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :approval-gates
                     :values [row]}))
      id
      (catch Exception e
        (log/warn "Failed to create approval gate:" (.getMessage e))
        nil))))

(defn get-gate
  "Get a single approval gate by ID.
   When org-id is provided, verifies the gate's build belongs to that org via JOIN."
  [ds gate-id & {:keys [org-id]}]
  (if org-id
    (jdbc/execute-one! ds
      (sql/format {:select [:ag.*]
                   :from [[:approval-gates :ag]]
                   :join [[:builds :b] [:= :ag.build-id :b.id]]
                   :where [:and [:= :ag.id gate-id] [:= :b.org-id org-id]]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (jdbc/execute-one! ds
      (sql/format {:select :*
                   :from :approval-gates
                   :where [:= :id gate-id]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

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
  "List all pending approval gates.
   When org-id is provided, only returns gates for builds in that org."
  [ds & {:keys [org-id]}]
  (if org-id
    (jdbc/execute! ds
      (sql/format {:select [:ag.*]
                   :from [[:approval-gates :ag]]
                   :join [[:builds :b] [:= :ag.build-id :b.id]]
                   :where [:and [:= :ag.status "pending"] [:= :b.org-id org-id]]
                   :order-by [[:ag.created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :approval-gates
                   :where [:= :status "pending"]
                   :order-by [[:created-at :desc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn list-gates-for-build
  "List all gates for a specific build.
   When org-id is provided, verifies the build belongs to that org."
  [ds build-id & {:keys [org-id]}]
  (if org-id
    (jdbc/execute! ds
      (sql/format {:select [:ag.*]
                   :from [[:approval-gates :ag]]
                   :join [[:builds :b] [:= :ag.build-id :b.id]]
                   :where [:and [:= :ag.build-id build-id] [:= :b.org-id org-id]]
                   :order-by [[:ag.created-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})
    (jdbc/execute! ds
      (sql/format {:select :*
                   :from :approval-gates
                   :where [:= :build-id build-id]
                   :order-by [[:created-at :asc]]})
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn count-pending-gates
  "Count pending approval gates.
   When org-id is provided, counts only gates for builds in that org."
  [ds & {:keys [org-id]}]
  (if org-id
    (:count
      (jdbc/execute-one! ds
        (sql/format {:select [[[:count :*] :count]]
                     :from [[:approval-gates :ag]]
                     :join [[:builds :b] [:= :ag.build-id :b.id]]
                     :where [:and [:= :ag.status "pending"] [:= :b.org-id org-id]]})
        {:builder-fn rs/as-unqualified-kebab-maps}))
    (:count
      (jdbc/execute-one! ds
        (sql/format {:select [[[:count :*] :count]]
                     :from :approval-gates
                     :where [:= :status "pending"]})
        {:builder-fn rs/as-unqualified-kebab-maps}))))

;; ---------------------------------------------------------------------------
;; Multi-approver response tracking
;; ---------------------------------------------------------------------------

(defn record-response!
  "Record an individual approval/rejection response for a gate.
   decision is \"approved\" or \"rejected\". Optional comment.
   Returns the response ID, or nil if user already responded (UNIQUE constraint)."
  [ds gate-id user-id decision & {:keys [comment]}]
  (let [id (util/generate-id)]
    (try
      (jdbc/execute-one! ds
        (sql/format {:insert-into :approval-responses
                     :values [(cond-> {:id id
                                       :gate-id gate-id
                                       :user-id user-id
                                       :decision decision}
                                comment (assoc :comment comment))]}))
      id
      (catch Exception e
        (let [msg (.getMessage e)]
          (if (and msg (or (.contains msg "UNIQUE constraint")
                          (.contains msg "unique constraint")
                          (.contains msg "duplicate key")))
            ;; UNIQUE(gate_id, user_id) violation → user already responded
            (do (log/debug "Duplicate response from" user-id "on gate" gate-id)
                nil)
            ;; Unexpected error — re-throw
            (throw e)))))))

(defn get-gate-responses
  "List all individual responses for a gate."
  [ds gate-id]
  (jdbc/execute! ds
    (sql/format {:select :*
                 :from :approval-responses
                 :where [:= :gate-id gate-id]
                 :order-by [[:created-at :asc]]})
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn count-approvals
  "Count the number of 'approved' responses for a gate."
  [ds gate-id]
  (or (:count
        (jdbc/execute-one! ds
          (sql/format {:select [[[:count :*] :count]]
                       :from :approval-responses
                       :where [:and
                               [:= :gate-id gate-id]
                               [:= :decision "approved"]]})
          {:builder-fn rs/as-unqualified-kebab-maps}))
      0))

(defn count-rejections
  "Count the number of 'rejected' responses for a gate."
  [ds gate-id]
  (or (:count
        (jdbc/execute-one! ds
          (sql/format {:select [[[:count :*] :count]]
                       :from :approval-responses
                       :where [:and
                               [:= :gate-id gate-id]
                               [:= :decision "rejected"]]})
          {:builder-fn rs/as-unqualified-kebab-maps}))
      0))

(defn can-user-respond?
  "Check if a user can respond to a gate.
   Returns true if the gate is pending and the user hasn't already responded.
   When the gate has an approver group, also checks the user is in the group."
  [ds gate-id user-id]
  (let [gate (jdbc/execute-one! ds
               (sql/format {:select [:status :approver-group]
                            :from :approval-gates
                            :where [:= :id gate-id]})
               {:builder-fn rs/as-unqualified-kebab-maps})]
    (when (= "pending" (:status gate))
      (let [;; Check approver group if set
            group (when (:approver-group gate)
                    (try (json/read-str (:approver-group gate))
                         (catch Exception _ nil)))
            in-group? (or (nil? group) (some #{user-id} group))
            ;; Check not already responded
            existing (jdbc/execute-one! ds
                       (sql/format {:select [:id]
                                    :from :approval-responses
                                    :where [:and
                                            [:= :gate-id gate-id]
                                            [:= :user-id user-id]]})
                       {:builder-fn rs/as-unqualified-kebab-maps})]
        (and in-group? (nil? existing))))))

(defn- parse-approver-group
  "Parse JSON approver group. Returns nil if not set or invalid."
  [group-json]
  (when group-json
    (try (json/read-str group-json)
         (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Approve / Reject / Timeout
;; ---------------------------------------------------------------------------

(defn approve-gate!
  "Approve an approval gate.
   For single-approver gates (min-approvals=1 or legacy), directly marks as approved.
   For multi-approver gates, records the approval response and checks if
   min-approvals threshold is met. Returns the update count (1 if gate resolved, 0 if not)."
  [ds gate-id user-id]
  (let [gate (get-gate ds gate-id)]
    (if-not (and gate (= "pending" (:status gate)))
      0
      (let [min-approvals (or (:min-approvals gate) 1)]
        (if (<= min-approvals 1)
          ;; Legacy single-approver: directly approve
          (let [result (jdbc/execute-one! ds
                         (sql/format {:update :approval-gates
                                      :set {:status "approved"
                                            :approved-by user-id
                                            :approved-at [:datetime "now"]}
                                      :where [:and
                                              [:= :id gate-id]
                                              [:= :status "pending"]]}))]
            ;; Also record as a response for consistency
            (record-response! ds gate-id user-id "approved")
            (:next.jdbc/update-count result 0))
          ;; Multi-approver: record response and check threshold
          (if (record-response! ds gate-id user-id "approved")
            (let [approval-count (count-approvals ds gate-id)]
              (if (>= approval-count min-approvals)
                ;; Threshold met — mark gate as approved
                (let [result (jdbc/execute-one! ds
                               (sql/format {:update :approval-gates
                                            :set {:status "approved"
                                                  :approved-by user-id
                                                  :approved-at [:datetime "now"]}
                                            :where [:and
                                                    [:= :id gate-id]
                                                    [:= :status "pending"]]}))]
                  (:next.jdbc/update-count result 0))
                ;; Not enough approvals yet
                0))
            ;; User already responded
            0))))))

(defn reject-gate!
  "Reject an approval gate.
   For multi-approver gates, records the rejection response. The gate is
   marked rejected if rejections make it impossible to reach min-approvals
   (i.e., remaining approvers < min-approvals - current-approvals).
   For single-approver gates, directly rejects.
   Returns the update count (1 if gate resolved, 0 if not)."
  [ds gate-id user-id]
  (let [gate (get-gate ds gate-id)]
    (if-not (and gate (= "pending" (:status gate)))
      0
      (let [min-approvals (or (:min-approvals gate) 1)
            group (parse-approver-group (:approver-group gate))]
        (if (or (<= min-approvals 1) (nil? group))
          ;; Legacy single-approver or no group: directly reject
          (let [result (jdbc/execute-one! ds
                         (sql/format {:update :approval-gates
                                      :set {:status "rejected"
                                            :rejected-by user-id
                                            :rejected-at [:datetime "now"]}
                                      :where [:and
                                              [:= :id gate-id]
                                              [:= :status "pending"]]}))]
            (record-response! ds gate-id user-id "rejected")
            (:next.jdbc/update-count result 0))
          ;; Multi-approver: record rejection, check if approval is still possible
          (if (record-response! ds gate-id user-id "rejected")
            (let [total-responses (count (get-gate-responses ds gate-id))
                  group-size (count group)
                  remaining (- group-size total-responses)
                  current-approvals (count-approvals ds gate-id)
                  can-still-approve? (>= (+ remaining current-approvals) min-approvals)]
              (if can-still-approve?
                ;; Still possible to reach threshold — don't reject yet
                0
                ;; Impossible to reach threshold — reject gate
                (let [result (jdbc/execute-one! ds
                               (sql/format {:update :approval-gates
                                            :set {:status "rejected"
                                                  :rejected-by user-id
                                                  :rejected-at [:datetime "now"]}
                                            :where [:and
                                                    [:= :id gate-id]
                                                    [:= :status "pending"]]}))]
                  (:next.jdbc/update-count result 0))))
            ;; User already responded
            0))))))

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
  "Delete gates and their responses older than retention-days.
   Returns number of gates deleted."
  [ds retention-days]
  ;; Delete responses for old gates first (referential integrity)
  (try
    (jdbc/execute-one! ds
      (sql/format {:delete-from :approval-responses
                   :where [:in :gate-id
                           {:select [:id]
                            :from :approval-gates
                            :where [:< :created-at
                                    [:datetime "now" (str "-" retention-days " days")]]}]}))
    (catch Exception _))
  (let [result (jdbc/execute-one! ds
                 (sql/format {:delete-from :approval-gates
                              :where [:< :created-at
                                      [:datetime "now" (str "-" retention-days " days")]]}))]
    (:next.jdbc/update-count result 0)))
