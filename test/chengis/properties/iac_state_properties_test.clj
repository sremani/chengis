(ns chengis.properties.iac-state-properties-test
  "Property-based tests for chengis.engine.iac-state.
   Verifies compress/decompress round-trip, SHA-256 invariants,
   and diff-states structural correctness."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [clojure.data.json :as json]
            [chengis.generators :as cgen]
            [chengis.engine.iac-state]))

;; Access private functions via var references
(def compress   #'chengis.engine.iac-state/compress)
(def decompress #'chengis.engine.iac-state/decompress)
(def sha256     #'chengis.engine.iac-state/sha256)

;; ---------------------------------------------------------------------------
;; compress / decompress — round-trip identity
;; ---------------------------------------------------------------------------

(defspec compress-decompress-identity 200
  (prop/for-all [s gen/string]
    (= s (decompress (compress s)))))

;; ---------------------------------------------------------------------------
;; compress — output is always a non-empty base64 string
;; ---------------------------------------------------------------------------

(defspec compress-produces-base64 100
  (prop/for-all [s gen/string]
    (let [compressed (compress s)]
      (and (string? compressed)
           (pos? (count compressed))
           ;; base64 uses A-Za-z0-9+/= characters only
           (re-matches #"[A-Za-z0-9+/=]+" compressed)))))

;; ---------------------------------------------------------------------------
;; sha256 — always 64-char lowercase hex
;; ---------------------------------------------------------------------------

(defspec sha256-is-64-char-hex 200
  (prop/for-all [s gen/string]
    (let [hash (sha256 s)]
      (and (string? hash)
           (= 64 (count hash))
           (re-matches #"[0-9a-f]{64}" hash)))))

;; ---------------------------------------------------------------------------
;; sha256 — deterministic (same input, same output)
;; ---------------------------------------------------------------------------

(defspec sha256-deterministic 100
  (prop/for-all [s gen/string]
    (= (sha256 s) (sha256 s))))

;; ---------------------------------------------------------------------------
;; sha256 — distinct inputs produce distinct hashes
;; ---------------------------------------------------------------------------

(defspec sha256-collision-resistance 100
  (prop/for-all [a gen/string
                 b gen/string]
    (or (= a b)
        (not= (sha256 a) (sha256 b)))))

;; ---------------------------------------------------------------------------
;; diff-states — self-diff has no changes
;; ---------------------------------------------------------------------------

(defspec diff-states-self-is-empty 100
  (prop/for-all [state-json cgen/gen-terraform-state-json]
    (let [result (chengis.engine.iac-state/diff-states state-json state-json)]
      (and (empty? (:added result))
           (empty? (:removed result))
           (empty? (:changed result))))))

;; ---------------------------------------------------------------------------
;; diff-states — added/removed are symmetric
;; ---------------------------------------------------------------------------

(defspec diff-states-added-removed-symmetric 100
  (prop/for-all [state-a cgen/gen-terraform-state-json
                 state-b cgen/gen-terraform-state-json]
    (let [ab (chengis.engine.iac-state/diff-states state-a state-b)
          ba (chengis.engine.iac-state/diff-states state-b state-a)]
      (and (= (set (:added ab)) (set (:removed ba)))
           (= (set (:removed ab)) (set (:added ba)))))))

;; ---------------------------------------------------------------------------
;; diff-states — invalid JSON returns all-empty
;; ---------------------------------------------------------------------------

(defspec diff-states-invalid-json-graceful 50
  (prop/for-all [s gen/string-alphanumeric]
    (let [result (chengis.engine.iac-state/diff-states s s)]
      (and (empty? (:added result))
           (empty? (:removed result))
           (empty? (:changed result))))))
