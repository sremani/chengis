(ns chengis.properties.provenance-properties-test
  "Property-based tests for SLSA provenance generation, DSSE envelope wrapping,
   and artifact signing command building.
   Verifies schema conformance, Base64 encoding, and command structure."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.provenance :as provenance]
            [chengis.engine.signing :as signing])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; format-slsa-predicate — SLSA v1.0 schema
;; ---------------------------------------------------------------------------

(defspec slsa-predicate-has-required-keys 200
  (prop/for-all [build-result cgen/gen-build-result-map]
    (let [pred (provenance/format-slsa-predicate build-result {})]
      (and (map? pred)
           (contains? pred :buildDefinition)
           (contains? pred :runDetails)
           (contains? (:buildDefinition pred) :buildType)
           (contains? (:runDetails pred) :builder)
           (contains? (:runDetails pred) :metadata)))))

(defspec slsa-predicate-builder-id-is-chengis 200
  (prop/for-all [build-result cgen/gen-build-result-map]
    (let [pred (provenance/format-slsa-predicate build-result {})]
      (= "chengis" (get-in pred [:runDetails :builder :id])))))

(defspec slsa-predicate-preserves-build-id 200
  (prop/for-all [build-result cgen/gen-build-result-map]
    (let [pred (provenance/format-slsa-predicate build-result {})]
      (= (:build-id build-result)
         (get-in pred [:buildDefinition :internalParameters :build-id])))))

(defspec slsa-predicate-build-type-is-pipeline 100
  (prop/for-all [build-result cgen/gen-build-result-map]
    (let [pred (provenance/format-slsa-predicate build-result {})]
      (= "chengis/pipeline/v1"
         (get-in pred [:buildDefinition :buildType])))))

;; ---------------------------------------------------------------------------
;; format-subjects — artifact mapping
;; ---------------------------------------------------------------------------

(defspec format-subjects-count-matches-input 200
  (prop/for-all [artifacts (gen/vector cgen/gen-artifact-with-hash 0 10)]
    (let [subjects (provenance/format-subjects artifacts)]
      (= (count artifacts) (count subjects)))))

(defspec format-subjects-each-has-name-and-digest 200
  (prop/for-all [artifacts (gen/vector cgen/gen-artifact-with-hash 1 5)]
    (let [subjects (provenance/format-subjects artifacts)]
      (every? (fn [s]
                (and (contains? s :name)
                     (contains? s :digest)
                     (contains? (:digest s) :sha256)))
              subjects))))

;; ---------------------------------------------------------------------------
;; wrap-dsse-envelope — DSSE structure and Base64
;; ---------------------------------------------------------------------------

(defspec dsse-envelope-has-required-keys 200
  (prop/for-all [payload-type (gen/not-empty gen/string-alphanumeric)]
    (let [envelope (provenance/wrap-dsse-envelope payload-type [] {})]
      (and (contains? envelope :payloadType)
           (contains? envelope :payload)
           (contains? envelope :signatures)
           (= payload-type (:payloadType envelope))
           (vector? (:signatures envelope))))))

(defspec dsse-envelope-payload-is-valid-base64 200
  (prop/for-all [build-result cgen/gen-build-result-map]
    (let [pred (provenance/format-slsa-predicate build-result {})
          envelope (provenance/wrap-dsse-envelope "test/v1" [] pred)
          decoder (Base64/getDecoder)]
      ;; Decoding should not throw
      (try
        (let [decoded (.decode decoder ^String (:payload envelope))]
          (pos? (alength decoded)))
        (catch Exception _ false)))))

;; ---------------------------------------------------------------------------
;; build-cosign-command — command structure
;; ---------------------------------------------------------------------------

(defspec cosign-command-contains-sign-blob 200
  (prop/for-all [key-ref cgen/gen-file-path-safe
                 artifact cgen/gen-file-path-safe
                 sig-out cgen/gen-file-path-safe]
    (let [cmd (signing/build-cosign-command key-ref artifact sig-out)]
      (and (string? cmd)
           (str/includes? cmd "cosign sign-blob")
           (str/includes? cmd key-ref)
           (str/includes? cmd artifact)
           (str/includes? cmd sig-out)))))

;; ---------------------------------------------------------------------------
;; build-gpg-command — command structure
;; ---------------------------------------------------------------------------

(defspec gpg-command-contains-detach-sign 200
  (prop/for-all [key-ref cgen/gen-file-path-safe
                 artifact cgen/gen-file-path-safe
                 sig-out cgen/gen-file-path-safe]
    (let [cmd (signing/build-gpg-command key-ref artifact sig-out)]
      (and (string? cmd)
           (str/includes? cmd "gpg")
           (str/includes? cmd "--detach-sign")
           (str/includes? cmd key-ref)
           (str/includes? cmd artifact)
           (str/includes? cmd sig-out)))))
