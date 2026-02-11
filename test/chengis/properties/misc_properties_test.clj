(ns chengis.properties.misc-properties-test
  "Property-based tests for chengis.engine.log-masker and chengis.distributed.region.
   Verifies secret masking completeness, region matching symmetry,
   locality bonus scoring, and region-aware score bounds."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.log-masker :as log-masker]
            [chengis.distributed.region :as region]))

;; ---------------------------------------------------------------------------
;; mask-secrets — all secret values are replaced
;; ---------------------------------------------------------------------------

(defspec mask-secrets-replaces-all-occurrences 100
  (prop/for-all [secrets cgen/gen-secret-values]
    (let [text (clojure.string/join " " secrets)
          masked (log-masker/mask-secrets text secrets)]
      ;; No secret value should remain in the masked output
      (every? (fn [s] (not (clojure.string/includes? masked s))) secrets))))

;; ---------------------------------------------------------------------------
;; mask-secrets — nil text or empty secrets returns text unchanged
;; ---------------------------------------------------------------------------

(defspec mask-secrets-nil-text-unchanged 50
  (prop/for-all [secrets cgen/gen-secret-values]
    (nil? (log-masker/mask-secrets nil secrets))))

(defspec mask-secrets-empty-secrets-unchanged 100
  (prop/for-all [text (gen/not-empty gen/string-alphanumeric)]
    (= text (log-masker/mask-secrets text #{}))))

;; ---------------------------------------------------------------------------
;; same-region? — symmetric and reflexive for non-blank strings
;; ---------------------------------------------------------------------------

(defspec same-region-symmetric 100
  (prop/for-all [a (gen/not-empty gen/string-alphanumeric)
                 b (gen/not-empty gen/string-alphanumeric)]
    (= (region/same-region? a b)
       (region/same-region? b a))))

;; ---------------------------------------------------------------------------
;; region-aware-score — bounded at 1.5
;; ---------------------------------------------------------------------------

(defspec region-aware-score-capped-at-1-5 100
  (prop/for-all [base (gen/double* {:min 0.0 :max 2.0 :NaN? false :infinite? false})
                 weight (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})
                 r (gen/not-empty gen/string-alphanumeric)]
    (let [score (region/region-aware-score base r r weight)]
      (<= score 1.5))))
