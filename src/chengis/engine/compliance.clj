(ns chengis.engine.compliance
  "Compliance report generation and audit hash chain verification."
  (:require [chengis.db.audit-store :as audit-store]
            [chengis.db.compliance-store :as compliance-store]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Hash chain verification
;; ---------------------------------------------------------------------------

(def ^:private hash-fields
  "The exact set of fields included in the audit entry hash.
   Must match the base-row keys constructed by audit-store/insert-audit!
   Excludes: :timestamp (DB-generated), :prev-hash, :entry-hash (hash chain columns).
   :org-id is conditionally included (only when non-nil) to match insert-audit!'s cond-> behavior."
  [:id :user-id :username :action :resource-type :resource-id :detail :ip-address :user-agent])

(defn- recompute-entry-hash
  "Recompute the hash for a raw audit row + its stated prev_hash.
   Uses the same fixed field set as audit-store/compute-entry-hash to ensure
   exact match regardless of extra DB columns."
  [row-map prev-hash]
  (try
    (let [;; Build a map with the exact fields used at insert time
          base (select-keys row-map hash-fields)
          ;; Conditionally add :org-id only when non-nil (matches cond-> in insert-audit!)
          base (if-let [org-id (:org-id row-map)]
                 (assoc base :org-id org-id)
                 base)
          canonical (->> base
                         (sort-by (comp str key))
                         (map (fn [[k v]] (str (name k) "=" v)))
                         (str/join "|"))
          data (str canonical "|prev=" (or prev-hash "genesis"))
          digest (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes data "UTF-8"))]
      (format "%064x" (BigInteger. 1 hash-bytes)))
    (catch Exception _e nil)))

(defn verify-hash-chain
  "Walk audit logs in timestamp order and verify the SHA-256 chain.
   Performs two checks per entry:
   1. prev_hash linkage — stored prev_hash must match the previous entry's entry_hash
   2. Content integrity — recomputed hash must match stored entry_hash
   Returns {:valid bool :entries-checked int :first-invalid-id id-or-nil :reason str-or-nil}."
  [ds {:keys [org-id from-date to-date]}]
  (let [entries (audit-store/query-audits-asc ds
                  {:org-id org-id :from-date from-date :to-date to-date
                   :limit 10000 :offset 0})]
    (if (empty? entries)
      {:valid true :entries-checked 0 :first-invalid-id nil}
      (loop [remaining entries
             prev-hash nil
             checked 0]
        (if (empty? remaining)
          {:valid true :entries-checked checked :first-invalid-id nil}
          (let [entry (first remaining)
                stored-hash (:entry-hash entry)
                stored-prev (:prev-hash entry)]
            ;; Skip entries without hashes (pre-chain migration entries)
            (if (nil? stored-hash)
              (recur (rest remaining) prev-hash (inc checked))
              (cond
                ;; Check 1: prev_hash chain link
                (and prev-hash (not= stored-prev prev-hash))
                {:valid false :entries-checked (inc checked)
                 :first-invalid-id (:id entry)
                 :reason "prev_hash mismatch"}

                ;; Check 2: recompute entry_hash to detect content tampering
                (let [recomputed (recompute-entry-hash entry stored-prev)]
                  (and recomputed (not= recomputed stored-hash)))
                {:valid false :entries-checked (inc checked)
                 :first-invalid-id (:id entry)
                 :reason "entry_hash mismatch (content tampered)"}

                ;; Both checks passed — continue
                :else
                (recur (rest remaining) stored-hash (inc checked))))))))))

;; ---------------------------------------------------------------------------
;; Report generation
;; ---------------------------------------------------------------------------

(defn- summarize-events
  "Group and count audit events by action."
  [entries]
  (let [by-action (group-by :action entries)]
    {:total-events (count entries)
     :by-action (into {} (map (fn [[k v]] [k (count v)]) by-action))
     :unique-users (count (set (map :username entries)))}))

(defn- access-control-section
  "SOC2 access control section: login/logout/user management events."
  [entries]
  (let [access-actions #{"login" "logout" "login-failed" "manage-user"
                          "create-user" "toggle-user" "reset-password"
                          "account-lockout" "unlock-account"}
        filtered (filter #(contains? access-actions (:action %)) entries)]
    {:section "Access Control"
     :events (summarize-events filtered)
     :findings (cond-> []
                 (some #(= "account-lockout" (:action %)) filtered)
                 (conj "Account lockout events detected — review for brute-force attempts."))}))

(defn- change-management-section
  "SOC2 change management section: build/deploy events."
  [entries]
  (let [change-actions #{"trigger-build" "cancel-build" "retry-build"
                          "approve-gate" "reject-gate" "create-job" "delete-job"}
        filtered (filter #(contains? change-actions (:action %)) entries)]
    {:section "Change Management"
     :events (summarize-events filtered)
     :findings (cond-> []
                 (some #(= "cancel-build" (:action %)) filtered)
                 (conj "Build cancellations detected — verify they were intentional."))}))

(defn- audit-trail-section
  "ISO27001 audit trail section: all events with hash chain status."
  [entries chain-result]
  {:section "Audit Trail Integrity"
   :events (summarize-events entries)
   :chain-valid (:valid chain-result)
   :entries-checked (:entries-checked chain-result)
   :findings (cond-> []
               (not (:valid chain-result))
               (conj (str "Hash chain integrity violation detected at entry: "
                          (:first-invalid-id chain-result))))})

(defn generate-report!
  "Generate a compliance report for the given template and parameters.
   Updates the run record with results."
  [ds report-template run-id {:keys [org-id period-start period-end]}]
  (try
    (compliance-store/update-report-run! ds run-id {:status "running"})
    (let [entries (audit-store/query-audits ds
                    {:org-id org-id
                     :from-date period-start
                     :to-date period-end
                     :limit 10000
                     :offset 0})
          report-type (:report-type report-template)
          chain-result (verify-hash-chain ds {:org-id org-id
                                              :from-date period-start
                                              :to-date period-end})
          sections (case report-type
                     "soc2-access-control"
                     [(access-control-section entries)]

                     "soc2-change-management"
                     [(change-management-section entries)]

                     "iso27001-audit-trail"
                     [(audit-trail-section entries chain-result)]

                     "full-audit"
                     [(access-control-section entries)
                      (change-management-section entries)
                      (audit-trail-section entries chain-result)]

                     ;; custom or unknown: full summary
                     [(summarize-events entries)])
          summary {:report-type report-type
                   :title (:title report-template)
                   :period {:start period-start :end period-end}
                   :generated-at (str (java.time.Instant/now))
                   :sections sections
                   :chain-verification chain-result}
          summary-json (json/write-str summary)
          ;; Hash the report itself for tamper evidence
          digest (MessageDigest/getInstance "SHA-256")
          report-hash (format "%064x"
                        (BigInteger. 1 (.digest digest (.getBytes summary-json "UTF-8"))))]
      (compliance-store/update-report-run! ds run-id
        {:status "completed"
         :summary summary-json
         :report-hash report-hash
         :completed-at (str (java.time.Instant/now))})
      (log/info "Compliance report generated" {:run-id run-id :type report-type
                                                :events (count entries)})
      {:run-id run-id :status "completed" :summary summary})
    (catch Exception e
      (log/error "Compliance report generation failed" {:run-id run-id :error (.getMessage e)})
      (compliance-store/update-report-run! ds run-id
        {:status "failed"
         :summary (json/write-str {:error (.getMessage e)})
         :completed-at (str (java.time.Instant/now))})
      {:run-id run-id :status "failed" :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Built-in templates
;; ---------------------------------------------------------------------------

(defn builtin-templates
  "Returns a vector of built-in compliance report template definitions."
  []
  [{:report-type "soc2-access-control"
    :title "SOC2 Access Control Report"
    :description "Reviews login/logout activity, failed authentication attempts, account lockouts, and user management changes."}
   {:report-type "soc2-change-management"
    :title "SOC2 Change Management Report"
    :description "Reviews build triggers, cancellations, approvals, and job configuration changes."}
   {:report-type "iso27001-audit-trail"
    :title "ISO 27001 Audit Trail Report"
    :description "Comprehensive audit trail with hash chain integrity verification for tamper evidence."}
   {:report-type "full-audit"
    :title "Full Audit Report"
    :description "Combined access control, change management, and audit trail integrity report."}])
