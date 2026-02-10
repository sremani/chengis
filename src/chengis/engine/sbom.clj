(ns chengis.engine.sbom
  "SBOM (Software Bill of Materials) generation engine.
   Runs SBOM tools (syft by default) against build targets and persists
   the results to the database.
   Gated by the :sbom-generation feature flag."
  (:require [chengis.db.sbom-store :as sbom-store]
            [chengis.engine.process :as process]
            [chengis.feature-flags :as feature-flags]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

;; ---------------------------------------------------------------------------
;; Target detection
;; ---------------------------------------------------------------------------

(defn detect-sbom-targets
  "Returns a list of scan targets for SBOM generation.
   Checks for docker images in pipeline-source, falls back to workspace path.
   Always returns at least [workspace] if workspace exists."
  [build-result]
  (let [pipeline-source (:pipeline-source build-result)
        workspace (:workspace build-result)
        ;; Look for docker image references in pipeline source
        docker-images (when (string? pipeline-source)
                        (re-seq #"(?:docker\.io/|ghcr\.io/|registry\.[^/]+/)?[\w.-]+/[\w.-]+:[\w.-]+" pipeline-source))
        targets (if (seq docker-images)
                  (vec docker-images)
                  [])]
    (if (and (empty? targets) workspace)
      [workspace]
      (if (and (seq targets) workspace)
        (conj targets workspace)
        (if workspace
          [workspace]
          targets)))))

;; ---------------------------------------------------------------------------
;; SBOM output parsing
;; ---------------------------------------------------------------------------

(defn parse-sbom-output
  "Parse JSON SBOM output, extract component count and version.
   Returns {:components components :component-count count :version version} or nil on parse failure."
  [stdout format-name]
  (try
    (let [parsed (json/read-str stdout :key-fn keyword)
          components (case format-name
                       "cyclonedx" (:components parsed)
                       "spdx" (get-in parsed [:packages] [])
                       (:components parsed))
          version (case format-name
                    "cyclonedx" (:specVersion parsed)
                    "spdx" (:spdxVersion parsed)
                    (:specVersion parsed))]
      {:components components
       :component-count (count (or components []))
       :version version})
    (catch Exception e
      (log/warn "Failed to parse SBOM output" {:format format-name :error (.getMessage e)})
      nil)))

;; ---------------------------------------------------------------------------
;; Content hashing
;; ---------------------------------------------------------------------------

(defn compute-content-hash
  "Compute SHA-256 hash of a content string. Returns hex string."
  [content]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes (str content) "UTF-8"))]
    (format "%064x" (BigInteger. 1 hash-bytes))))

;; ---------------------------------------------------------------------------
;; Main entry point
;; ---------------------------------------------------------------------------

(defn generate-sbom!
  "Generate SBOMs for a completed build.
   Gated by the :sbom-generation feature flag.
   For each target, runs the configured tool (syft by default) via process/execute-command.
   Parses output, computes content hash, stores in DB.
   If tool not found (exit code 127 or exception), logs warning and returns nil gracefully.
   Config from (get-in system [:config :sbom])."
  [system build-result]
  (when (feature-flags/enabled? (:config system) :sbom-generation)
    (try
      (let [ds (:db system)
            config (get-in system [:config :sbom])
            tool-name (or (:tool config) "syft")
            sbom-format (or (:format config) "cyclonedx")
            timeout (or (:timeout config) 120000)
            build-id (:build-id build-result)
            job-id (:job-id build-result)
            org-id (or (:org-id build-result) "default-org")
            targets (detect-sbom-targets build-result)
            output-format (case sbom-format
                            "cyclonedx" "cyclonedx-json"
                            "spdx" "spdx-json"
                            "cyclonedx-json")]
        (when (seq targets)
          (let [results (doall
                          (for [target targets]
                            (try
                              (let [command (str tool-name " " target " -o " output-format)
                                    result (process/execute-command
                                             {:command command
                                              :dir (:workspace build-result)
                                              :timeout timeout})]
                                (cond
                                  ;; Tool not found
                                  (= 127 (:exit-code result))
                                  (do (log/warn "SBOM tool not found" {:tool tool-name})
                                      nil)

                                  ;; Command failed
                                  (not (zero? (:exit-code result)))
                                  (do (log/warn "SBOM generation failed" {:target target
                                                                          :exit-code (:exit-code result)
                                                                          :stderr (:stderr result)})
                                      nil)

                                  ;; Success — parse and store
                                  :else
                                  (let [stdout (:stdout result)
                                        parsed (parse-sbom-output stdout sbom-format)
                                        content-hash (compute-content-hash stdout)]
                                    (when parsed
                                      (sbom-store/create-sbom! ds
                                        {:build-id build-id
                                         :job-id job-id
                                         :org-id org-id
                                         :sbom-format sbom-format
                                         :sbom-version (:version parsed)
                                         :component-count (:component-count parsed)
                                         :content-hash content-hash
                                         :sbom-content stdout
                                         :tool-name tool-name
                                         :tool-version nil})))))
                              (catch Exception e
                                (log/warn "SBOM generation exception" {:target target :error (.getMessage e)})
                                nil))))]
            (let [successful (filterv some? results)]
              (log/info "SBOM generation complete" {:build-id build-id
                                                    :targets (count targets)
                                                    :successful (count successful)})
              (when (seq successful)
                successful)))))
      (catch Exception e
        (log/warn "SBOM generation failed" {:error (.getMessage e)})
        nil))))
