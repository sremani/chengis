(ns chengis.properties.util-properties-test
  "Property-based tests for chengis.util and chengis.config.
   Verifies EDN round-trip, ensure-keyword idempotence, format-size invariants,
   coerce-env-value type rules, and deep-merge algebraic properties."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.util :as util]
            [chengis.config :as config]))

;; ---------------------------------------------------------------------------
;; serialize-edn / deserialize-edn — round-trip
;; ---------------------------------------------------------------------------

(defspec edn-roundtrip-for-safe-values 200
  (prop/for-all [data cgen/gen-edn-safe]
    (let [serialized (util/serialize-edn data)
          deserialized (util/deserialize-edn serialized)]
      (= data deserialized))))

;; ---------------------------------------------------------------------------
;; serialize-edn — nil input yields nil output
;; ---------------------------------------------------------------------------

(defspec serialize-nil-returns-nil 50
  (prop/for-all [_ (gen/return nil)]
    (nil? (util/serialize-edn nil))))

;; ---------------------------------------------------------------------------
;; deserialize-edn — nil input yields nil output
;; ---------------------------------------------------------------------------

(defspec deserialize-nil-returns-nil 50
  (prop/for-all [_ (gen/return nil)]
    (nil? (util/deserialize-edn nil))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — keywords are unchanged
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-identity-for-keywords 100
  (prop/for-all [k gen/keyword]
    (= k (util/ensure-keyword k))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — strings become keywords
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-string-to-keyword 100
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    (let [result (util/ensure-keyword s)]
      (and (keyword? result)
           (= (keyword s) result)))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — non-string/non-keyword values pass through
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-passthrough-for-other-types 100
  (prop/for-all [n gen/nat]
    (= n (util/ensure-keyword n))))

;; ---------------------------------------------------------------------------
;; format-size — nil returns dash
;; ---------------------------------------------------------------------------

(defspec format-size-nil-returns-dash 50
  (prop/for-all [_ (gen/return nil)]
    (= "\u2014" (util/format-size nil))))

;; ---------------------------------------------------------------------------
;; format-size — small values show " B" suffix
;; ---------------------------------------------------------------------------

(defspec format-size-bytes-range 100
  (prop/for-all [b (gen/choose 0 1023)]
    (let [result (util/format-size b)]
      (and (string? result)
           (str/ends-with? result " B")
           (str/includes? result (str b))))))

;; ---------------------------------------------------------------------------
;; format-size — KB range
;; ---------------------------------------------------------------------------

(defspec format-size-kb-range 100
  (prop/for-all [b (gen/choose 1024 (dec (* 1024 1024)))]
    (let [result (util/format-size b)]
      (and (string? result)
           (str/ends-with? result " KB")))))

;; ---------------------------------------------------------------------------
;; format-size — MB range
;; ---------------------------------------------------------------------------

(defspec format-size-mb-range 100
  (prop/for-all [b (gen/choose (* 1024 1024) (dec (* 1024 1024 1024)))]
    (let [result (util/format-size b)]
      (and (string? result)
           (str/ends-with? result " MB")))))

;; ---------------------------------------------------------------------------
;; coerce-env-value — round-trip for env values
;; ---------------------------------------------------------------------------

(defspec coerce-env-value-type-correctness 200
  (prop/for-all [v cgen/gen-env-var-value]
    (let [result (config/coerce-env-value v)]
      (cond
        (= v "true")              (true? result)
        (= v "false")             (false? result)
        (re-matches #"\d+" v)     (and (integer? result) (= (Long/parseLong v) result))
        (str/starts-with? v ":")  (keyword? result)
        :else                     (= v result)))))

;; ---------------------------------------------------------------------------
;; deep-merge — nil is identity
;; ---------------------------------------------------------------------------

(defspec deep-merge-nil-is-identity 100
  (prop/for-all [m (gen/map gen/keyword gen/nat {:max-elements 5})]
    (and (= m (config/deep-merge m nil))
         (= m (config/deep-merge nil m)))))
