(ns chengis.properties.cache-compliance-properties-test
  "Property-based tests for stage cache fingerprinting and compliance hashing.
   Verifies SHA-256 output format, determinism of fingerprinting and hashing,
   and audit event summarization count invariant."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.stage-cache :as stage-cache]
            [chengis.engine.compliance :as compliance]))

;; Access private functions via var references
(def sha256 #'chengis.engine.stage-cache/sha256)
(def recompute-entry-hash #'chengis.engine.compliance/recompute-entry-hash)
(def summarize-events #'chengis.engine.compliance/summarize-events)

;; ---------------------------------------------------------------------------
;; sha256 — output format and determinism
;; ---------------------------------------------------------------------------

(defspec sha256-always-64-hex 200
  (prop/for-all [s gen/string]
    (let [result (sha256 s)]
      (and (string? result)
           (= 64 (count result))
           (boolean (re-matches #"[0-9a-f]{64}" result))))))

(defspec sha256-deterministic 200
  (prop/for-all [s gen/string]
    (= (sha256 s) (sha256 s))))

;; ---------------------------------------------------------------------------
;; stage-fingerprint — determinism
;; ---------------------------------------------------------------------------

(defspec stage-fingerprint-deterministic 200
  (prop/for-all [stage-def cgen/gen-stage-def-for-fingerprint
                 git-commit (gen/not-empty gen/string-alphanumeric)
                 env cgen/gen-env-map]
    (= (stage-cache/stage-fingerprint git-commit stage-def env)
       (stage-cache/stage-fingerprint git-commit stage-def env))))

;; ---------------------------------------------------------------------------
;; recompute-entry-hash — determinism
;; ---------------------------------------------------------------------------

(defspec recompute-entry-hash-deterministic 200
  (prop/for-all [entry cgen/gen-audit-entry
                 prev-hash cgen/gen-sha256-hash]
    (let [h1 (recompute-entry-hash entry prev-hash)
          h2 (recompute-entry-hash entry prev-hash)]
      (= h1 h2))))

;; ---------------------------------------------------------------------------
;; summarize-events — total count invariant
;; ---------------------------------------------------------------------------

(defspec summarize-events-total-equals-input-count 200
  (prop/for-all [entries (gen/vector cgen/gen-audit-entry 0 20)]
    (let [result (summarize-events entries)]
      (= (count entries) (:total-events result)))))
