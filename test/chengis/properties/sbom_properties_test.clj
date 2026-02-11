(ns chengis.properties.sbom-properties-test
  "Property-based tests for SBOM parsing, content hashing, target detection,
   and artifact content-type resolution.
   Verifies resilience to arbitrary input, hash format, and MIME type mapping."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.sbom :as sbom]
            [chengis.engine.artifacts]))

;; Access private content-type-for via var reference
(def content-type-for #'chengis.engine.artifacts/content-type-for)

;; ---------------------------------------------------------------------------
;; parse-sbom-output — resilience
;; ---------------------------------------------------------------------------

(defspec parse-sbom-output-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string
                 fmt (gen/elements ["cyclonedx" "spdx" "unknown"])]
    ;; Should return nil on parse failure, never throw
    (let [result (sbom/parse-sbom-output s fmt)]
      (or (nil? result) (map? result)))))

(defspec parse-sbom-output-valid-cyclonedx 100
  (prop/for-all [sbom-json cgen/gen-cyclonedx-sbom-json]
    (let [result (sbom/parse-sbom-output sbom-json "cyclonedx")]
      (and (map? result)
           (contains? result :components)
           (contains? result :component-count)
           (int? (:component-count result))
           (>= (:component-count result) 0)))))

;; ---------------------------------------------------------------------------
;; compute-content-hash — SHA-256 format
;; ---------------------------------------------------------------------------

(defspec compute-content-hash-always-64-hex-chars 200
  (prop/for-all [content gen/string]
    (let [hash (sbom/compute-content-hash content)]
      (and (string? hash)
           (= 64 (count hash))
           (re-matches #"[0-9a-f]{64}" hash)))))

(defspec compute-content-hash-deterministic 200
  (prop/for-all [content gen/string]
    (= (sbom/compute-content-hash content)
       (sbom/compute-content-hash content))))

(defspec compute-content-hash-different-inputs-different-hashes 200
  (prop/for-all [a (gen/not-empty gen/string-alphanumeric)
                 b (gen/not-empty gen/string-alphanumeric)]
    (if (= a b)
      true  ;; Same input, same hash — trivially true
      (not= (sbom/compute-content-hash a)
            (sbom/compute-content-hash b)))))

;; ---------------------------------------------------------------------------
;; detect-sbom-targets — target detection
;; ---------------------------------------------------------------------------

(defspec detect-sbom-targets-always-returns-vector-or-seq 100
  (prop/for-all [workspace (gen/not-empty gen/string-alphanumeric)]
    (let [result (sbom/detect-sbom-targets {:workspace workspace})]
      (sequential? result))))

(defspec detect-sbom-targets-workspace-fallback 100
  (prop/for-all [workspace (gen/not-empty gen/string-alphanumeric)]
    ;; When no docker images found, workspace should be in targets
    (let [result (sbom/detect-sbom-targets {:workspace workspace
                                            :pipeline-source nil})]
      (some #{workspace} result))))

;; ---------------------------------------------------------------------------
;; content-type-for — MIME type mapping
;; ---------------------------------------------------------------------------

(defspec content-type-for-known-extensions 200
  (prop/for-all [filename cgen/gen-filename-with-extension]
    (let [ct (content-type-for filename)]
      (and (string? ct)
           (pos? (count ct))
           ;; Known extensions should NOT return the default octet-stream
           (not= "application/octet-stream" ct)))))

(defspec content-type-for-unknown-extension 100
  (prop/for-all [filename cgen/gen-filename-unknown-ext]
    (= "application/octet-stream" (content-type-for filename))))

(defspec content-type-for-specific-mappings 10
  (prop/for-all [_ (gen/return nil)]
    (and (= "application/java-archive" (content-type-for "app.jar"))
         (= "application/zip" (content-type-for "archive.zip"))
         (= "application/gzip" (content-type-for "bundle.tar.gz"))
         (= "application/json" (content-type-for "data.json"))
         (= "text/html" (content-type-for "page.html"))
         (= "application/pdf" (content-type-for "doc.pdf"))
         (= "image/png" (content-type-for "logo.png"))
         (= "image/jpeg" (content-type-for "photo.jpg"))
         (= "image/svg+xml" (content-type-for "icon.svg")))))
