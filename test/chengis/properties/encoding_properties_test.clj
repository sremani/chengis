(ns chengis.properties.encoding-properties-test
  "Property-based tests for encoding, compression, hashing, and serialization.
   Verifies round-trip integrity for compress/decompress, EDN serialize/deserialize,
   and structural invariants for SHA-256 hashing and UUID generation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.iac-state]
            [chengis.util :as util]))

;; Access private functions via var references (standard Clojure testing pattern)
(def compress    #'chengis.engine.iac-state/compress)
(def decompress  #'chengis.engine.iac-state/decompress)
(def sha256      #'chengis.engine.iac-state/sha256)

;; ---------------------------------------------------------------------------
;; Compress / Decompress round-trip
;; ---------------------------------------------------------------------------

(defspec compress-decompress-roundtrip 200
  (prop/for-all [s gen/string]
    (= s (decompress (compress s)))))

(defspec compress-produces-non-empty-output 100
  (prop/for-all [s gen/string]
    (let [compressed (compress s)]
      (and (string? compressed)
           (pos? (count compressed))))))

(defspec compress-empty-string-roundtrip 10
  (prop/for-all [_ (gen/return nil)]
    (= "" (decompress (compress "")))))

;; ---------------------------------------------------------------------------
;; SHA-256 properties
;; ---------------------------------------------------------------------------

(defspec sha256-always-64-char-hex 200
  (prop/for-all [s gen/string]
    (let [hash (sha256 s)]
      (and (string? hash)
           (= 64 (count hash))
           (re-matches #"[0-9a-f]{64}" hash)))))

(defspec sha256-deterministic 100
  (prop/for-all [s gen/string]
    (= (sha256 s) (sha256 s))))

(defspec sha256-different-inputs-different-hashes 100
  (prop/for-all [a gen/string
                 b gen/string]
    ;; If inputs differ, hashes should differ (probabilistic — collisions
    ;; are astronomically unlikely for SHA-256)
    (or (= a b)
        (not= (sha256 a) (sha256 b)))))

;; ---------------------------------------------------------------------------
;; EDN serialize / deserialize round-trip
;; ---------------------------------------------------------------------------

(defspec edn-serialize-deserialize-roundtrip 200
  (prop/for-all [data cgen/gen-edn-safe]
    ;; gen-edn-safe excludes nil and false at top level (serialize-edn
    ;; treats them as falsy), so serialized is always non-nil here.
    (let [serialized (util/serialize-edn data)
          deserialized (util/deserialize-edn serialized)]
      (= data deserialized))))

(deftest edn-nil-handling-test
  (testing "serialize nil returns nil, deserialize nil returns nil"
    (is (nil? (util/serialize-edn nil)))
    (is (nil? (util/deserialize-edn nil)))))

;; ---------------------------------------------------------------------------
;; generate-id properties
;; ---------------------------------------------------------------------------

(defspec generate-id-uuid-format 100
  (prop/for-all [_ (gen/return nil)]
    (let [id (util/generate-id)]
      (and (string? id)
           (= 36 (count id))
           (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}" id)))))

(defspec generate-id-unique 50
  (prop/for-all [_ (gen/return nil)]
    (let [ids (repeatedly 10 util/generate-id)]
      (= (count ids) (count (set ids))))))
