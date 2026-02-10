(ns chengis.engine.provenance
  "SLSA v1.0 provenance generator.
   Generates build provenance attestations following the SLSA framework,
   wraps them in DSSE envelopes, and persists to the database.
   Gated by the :slsa-provenance feature flag."
  (:require [chengis.db.provenance-store :as provenance-store]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; SLSA v1.0 Predicate
;; ---------------------------------------------------------------------------

(defn format-slsa-predicate
  "Pure function that creates a SLSA v1.0 predicate map from build-result and config."
  [build-result config]
  {:buildDefinition {:buildType "chengis/pipeline/v1"
                     :externalParameters {:pipeline (:pipeline-source build-result)
                                          :parameters (get-in build-result [:git-info :parameters] {})}
                     :internalParameters {:build-id (:build-id build-result)
                                          :job-id (:job-id build-result)
                                          :build-number (:build-number build-result)}}
   :runDetails {:builder {:id "chengis" :version "1.0"}
                :metadata {:invocationId (:build-id build-result)
                           :startedOn (:started-at build-result)
                           :finishedOn (:completed-at build-result)}
                :byproducts []}})

;; ---------------------------------------------------------------------------
;; Subjects
;; ---------------------------------------------------------------------------

(defn format-subjects
  "Convert build artifacts to SLSA subject entries.
   Each subject has :name (filename) and :digest {:sha256 hash}."
  [artifacts]
  (mapv (fn [artifact]
          {:name (:filename artifact)
           :digest {:sha256 (or (:sha256-hash artifact)
                                (:sha256hash artifact)
                                "unknown")}})
        artifacts))

;; ---------------------------------------------------------------------------
;; DSSE Envelope
;; ---------------------------------------------------------------------------

(defn wrap-dsse-envelope
  "Create a DSSE (Dead Simple Signing Envelope) structure (unsigned).
   The payload is base64-encoded JSON containing the type, subject, and predicate."
  [payload-type subject predicate]
  (let [payload-str (json/write-str {:_type payload-type
                                     :subject subject
                                     :predicate predicate})
        encoder (Base64/getEncoder)
        payload-b64 (.encodeToString encoder (.getBytes payload-str "UTF-8"))]
    {:payloadType payload-type
     :payload payload-b64
     :signatures []}))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn generate-provenance!
  "Generate SLSA provenance attestation for a completed build.
   Gated by the :slsa-provenance feature flag.
   Builds predicate, formats subjects from artifacts, wraps in DSSE envelope,
   and persists to the database. Returns the attestation record or nil."
  [system build-result]
  (when (feature-flags/enabled? (:config system) :slsa-provenance)
    (try
      (let [ds (:db system)
            config (:config system)
            build-id (:build-id build-result)
            job-id (:job-id build-result)
            org-id (or (:org-id build-result) "default-org")

            ;; Build the SLSA predicate
            predicate (format-slsa-predicate build-result config)

            ;; Get artifacts and format as subjects
            artifacts (if (:artifacts build-result)
                        (:artifacts build-result)
                        (try
                          (artifact-store/list-artifacts ds build-id)
                          (catch Exception _ [])))
            subjects (format-subjects artifacts)

            ;; Wrap in DSSE envelope
            payload-type "https://slsa.dev/provenance/v1"
            envelope (wrap-dsse-envelope payload-type subjects predicate)

            ;; Extract git info for source fields
            git-info (:git-info build-result)

            ;; Persist to database
            attestation (provenance-store/create-attestation! ds
                          {:build-id build-id
                           :job-id job-id
                           :org-id org-id
                           :slsa-level "L1"
                           :predicate-type payload-type
                           :subject-json (json/write-str subjects)
                           :predicate-json (json/write-str predicate)
                           :envelope-json (json/write-str envelope)
                           :builder-id "chengis"
                           :build-type "chengis/pipeline/v1"
                           :source-repo (:repo git-info)
                           :source-branch (:branch git-info)
                           :source-commit (:commit git-info)})]
        (log/info "SLSA provenance generated" {:build-id build-id :subjects (count subjects)})
        attestation)
      (catch Exception e
        (log/error "Failed to generate provenance" {:error (.getMessage e)})
        nil))))
