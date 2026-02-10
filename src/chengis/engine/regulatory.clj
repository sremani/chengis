(ns chengis.engine.regulatory
  "Regulatory readiness assessor.
   Evaluates SOC 2 Type II and ISO 27001 controls against system state.
   Feature-flag gated via :regulatory-dashboards."
  (:require [chengis.feature-flags :as feature-flags]
            [chengis.db.regulatory-store :as regulatory-store]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- audit-log-count
  "Count audit log entries for an org. Returns 0 if table is empty or on error."
  [ds org-id]
  (try
    (let [query (if org-id
                  ["SELECT COUNT(*) AS cnt FROM audit_logs WHERE org_id = ?" org-id]
                  ["SELECT COUNT(*) AS cnt FROM audit_logs"])
          row (jdbc/execute-one! ds query
                {:builder-fn rs/as-unqualified-kebab-maps})]
      (or (:cnt row) 0))
    (catch Exception _e 0)))

(defn- make-check
  "Build a check result map."
  [control-id control-name passing? evidence]
  {:control-id control-id
   :control-name control-name
   :status (if passing? "passing" "failing")
   :evidence evidence})

;; ---------------------------------------------------------------------------
;; SOC 2 Type II Assessment
;; ---------------------------------------------------------------------------

(defn assess-soc2-readiness
  "Evaluate SOC 2 Type II controls against system state.
   Returns vector of check result maps."
  [system org-id]
  (let [config (:config system)
        ds (:db system)]
    [(make-check "CC6.1" "Access Control"
       (boolean (get-in config [:auth :enabled]))
       (if (get-in config [:auth :enabled])
         "Authentication is enabled"
         "Authentication is not enabled"))

     (let [audit-cnt (audit-log-count ds org-id)]
       (make-check "CC6.6" "Audit Logging"
         (pos? audit-cnt)
         (if (pos? audit-cnt)
           (str "Audit log has " audit-cnt " entries for org")
           "No audit log entries found for org")))

     (make-check "CC7.1" "Change Management"
       (feature-flags/enabled? config :slsa-provenance)
       (if (feature-flags/enabled? config :slsa-provenance)
         "SLSA provenance tracking is enabled"
         "SLSA provenance tracking is not enabled"))

     (make-check "CC7.2" "System Monitoring"
       (boolean (get-in config [:metrics :enabled]))
       (if (get-in config [:metrics :enabled])
         "Metrics collection is enabled"
         "Metrics collection is not enabled"))

     (make-check "CC8.1" "Artifact Integrity"
       (feature-flags/enabled? config :artifact-checksums)
       (if (feature-flags/enabled? config :artifact-checksums)
         "Artifact checksum verification is enabled"
         "Artifact checksum verification is not enabled"))]))

;; ---------------------------------------------------------------------------
;; ISO 27001 Assessment
;; ---------------------------------------------------------------------------

(defn assess-iso27001-readiness
  "Evaluate ISO 27001 controls against system state.
   Returns vector of check result maps."
  [system org-id]
  (let [config (:config system)
        ds (:db system)]
    [(make-check "A.12.1" "Operational Procedures"
       (feature-flags/enabled? config :policy-engine)
       (if (feature-flags/enabled? config :policy-engine)
         "Policy engine is enabled for operational procedure enforcement"
         "Policy engine is not enabled"))

     (let [audit-cnt (audit-log-count ds org-id)]
       (make-check "A.12.4" "Logging & Monitoring"
         (pos? audit-cnt)
         (if (pos? audit-cnt)
           (str "Audit logging active with " audit-cnt " entries")
           "No audit log entries found")))

     (make-check "A.14.2" "Development Security"
       (feature-flags/enabled? config :sbom-generation)
       (if (feature-flags/enabled? config :sbom-generation)
         "SBOM generation is enabled for development security"
         "SBOM generation is not enabled"))]))

;; ---------------------------------------------------------------------------
;; Readiness scoring
;; ---------------------------------------------------------------------------

(defn compute-readiness-score
  "Given a vector of check maps, compute readiness score summary."
  [checks]
  (let [total (count checks)
        passing (count (filter #(= "passing" (:status %)) checks))
        failing (count (filter #(= "failing" (:status %)) checks))
        not-assessed (count (filter #(= "not-assessed" (:status %)) checks))
        percentage (if (pos? total)
                     (* 100.0 (/ (double passing) (double total)))
                     0.0)]
    {:total total
     :passing passing
     :failing failing
     :not-assessed not-assessed
     :percentage percentage}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn assess-and-store!
  "Run SOC 2 and ISO 27001 assessments, store results, and return scores.
   Gated by :regulatory-dashboards feature flag.
   Returns {:soc2 score-map :iso27001 score-map} or nil if disabled."
  [system org-id]
  (if-not (feature-flags/enabled? (:config system) :regulatory-dashboards)
    (do (log/debug "Regulatory dashboards disabled, skipping assessment")
        nil)
    (let [ds (:db system)
          soc2-checks (assess-soc2-readiness system org-id)
          iso-checks (assess-iso27001-readiness system org-id)]
      ;; Store each check
      (doseq [check soc2-checks]
        (regulatory-store/upsert-check! ds
          (assoc check :org-id (or org-id "default-org")
                       :framework "soc2"
                       :evidence-summary (:evidence check))))
      (doseq [check iso-checks]
        (regulatory-store/upsert-check! ds
          (assoc check :org-id (or org-id "default-org")
                       :framework "iso27001"
                       :evidence-summary (:evidence check))))
      (let [soc2-score (compute-readiness-score soc2-checks)
            iso-score (compute-readiness-score iso-checks)]
        (log/info "Regulatory assessment complete"
                  {:org-id org-id
                   :soc2-percentage (:percentage soc2-score)
                   :iso27001-percentage (:percentage iso-score)})
        {:soc2 soc2-score
         :iso27001 iso-score}))))
