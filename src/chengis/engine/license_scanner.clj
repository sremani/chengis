(ns chengis.engine.license-scanner
  "License scanning engine for Software Bill of Materials (SBOMs).
   Extracts license information from CycloneDX SBOMs, evaluates them
   against org-scoped license policies, and stores scan results.
   Feature-flagged under :license-scanning."
  (:require [clojure.data.json :as json]
            [chengis.feature-flags :as feature-flags]
            [chengis.db.sbom-store :as sbom-store]
            [chengis.db.license-store :as license-store]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; SBOM license extraction
;; ---------------------------------------------------------------------------

(defn extract-licenses-from-sbom
  "Parse a CycloneDX JSON SBOM and extract component license information.
   For each component, looks at the licenses array. Each license entry has
   license.id (SPDX ID like \"MIT\", \"Apache-2.0\") or license.name.
   Returns [{:component-name name :component-version version :license-id id-or-name}].
   Handles missing licenses gracefully (uses \"unknown\")."
  [sbom-json-str]
  (try
    (let [sbom (json/read-str sbom-json-str :key-fn keyword)
          components (or (:components sbom) [])]
      (mapv (fn [component]
              (let [comp-name (or (:name component) "unknown")
                    comp-version (or (:version component) "unknown")
                    licenses (or (:licenses component) [])
                    license-id (if (seq licenses)
                                 (let [first-license (:license (first licenses))]
                                   (or (:id first-license)
                                       (:name first-license)
                                       "unknown"))
                                 "unknown")]
                {:component-name comp-name
                 :component-version comp-version
                 :license-id license-id}))
            components))
    (catch Exception e
      (log/warn "Failed to parse SBOM JSON for license extraction" {:error (.getMessage e)})
      [])))

;; ---------------------------------------------------------------------------
;; License policy evaluation
;; ---------------------------------------------------------------------------

(defn evaluate-license-policy
  "Evaluate component license list against org's license policy list.
   For each component:
     - if license-id in denied policies -> \"denied\"
     - if license-id in allowed policies -> \"allowed\"
     - else -> \"unknown\"
   Returns {:components [{:name ... :license ... :status \"allowed\"/\"denied\"/\"unknown\"}]
            :allowed n :denied n :unknown n :passed? (zero? denied)}."
  [components policy-list]
  (let [allowed-set (set (map :license-id (filter #(= "allow" (:action %)) policy-list)))
        denied-set  (set (map :license-id (filter #(= "deny" (:action %)) policy-list)))
        evaluated (mapv (fn [comp]
                          (let [license (:license-id comp)
                                status (cond
                                         (contains? denied-set license)  "denied"
                                         (contains? allowed-set license) "allowed"
                                         :else                           "unknown")]
                            {:name (:component-name comp)
                             :version (:component-version comp)
                             :license license
                             :status status}))
                        components)
        allowed-count (count (filter #(= "allowed" (:status %)) evaluated))
        denied-count  (count (filter #(= "denied" (:status %)) evaluated))
        unknown-count (count (filter #(= "unknown" (:status %)) evaluated))]
    {:components evaluated
     :allowed allowed-count
     :denied denied-count
     :unknown unknown-count
     :passed? (zero? denied-count)}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn scan-licenses!
  "Main license scanning entry point. Gated by :license-scanning feature flag.
   1. Looks for existing SBOMs in DB for this build-id
   2. If no SBOM found, logs warning and returns nil
   3. Extracts licenses from first SBOM's sbom-content
   4. Loads org's license policies from license_policies table
   5. Evaluates licenses against policies
   6. Stores result in license_reports
   7. Returns the report."
  [system build-result]
  (let [config (:config system)]
    (when (feature-flags/enabled? config :license-scanning)
      (let [ds (:db system)
            build-id (:build-id build-result)
            job-id (:job-id build-result)
            org-id (or (:org-id build-result) "default-org")]
        (try
          (let [sboms (sbom-store/get-build-sboms ds build-id)]
            (if (empty? sboms)
              (do (log/warn "No SBOM found for build, skipping license scan"
                            {:build-id build-id})
                  nil)
              (let [sbom-content (:sbom-content (first sboms))
                    components (extract-licenses-from-sbom sbom-content)
                    policies (license-store/list-license-policies ds :org-id org-id)
                    result (evaluate-license-policy components policies)
                    report (license-store/create-report! ds
                             {:build-id build-id
                              :job-id job-id
                              :org-id org-id
                              :total-deps (count components)
                              :allowed-count (:allowed result)
                              :denied-count (:denied result)
                              :unknown-count (:unknown result)
                              :policy-passed (:passed? result)
                              :licenses-json (json/write-str (:components result))})]
                (log/info "License scan completed"
                          {:build-id build-id
                           :total (count components)
                           :denied (:denied result)
                           :passed? (:passed? result)})
                report)))
          (catch Exception e
            (log/error "License scanning failed" {:build-id build-id
                                                   :error (.getMessage e)})
            nil))))))
