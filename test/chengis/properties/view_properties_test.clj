(ns chengis.properties.view-properties-test
  "Property-based tests for view utility functions.
   Verifies format-size produces valid output for all byte counts,
   and ensure-keyword is idempotent and type-preserving."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.util :as util]))

;; ---------------------------------------------------------------------------
;; format-size — always produces a string
;; ---------------------------------------------------------------------------

(defspec format-size-produces-string 100
  (prop/for-all [n gen/nat]
    (string? (util/format-size n))))

;; ---------------------------------------------------------------------------
;; format-size — nil produces dash
;; ---------------------------------------------------------------------------

(deftest format-size-nil-produces-dash
  (testing "nil input returns em dash"
    (is (= "—" (util/format-size nil)))))

;; ---------------------------------------------------------------------------
;; format-size — correct unit for byte ranges
;; ---------------------------------------------------------------------------

(defspec format-size-bytes-range 50
  (prop/for-all [n (gen/choose 0 1023)]
    (str/ends-with? (util/format-size n) " B")))

(defspec format-size-kb-range 50
  (prop/for-all [n (gen/choose 1024 (dec (* 1024 1024)))]
    (str/ends-with? (util/format-size n) " KB")))

(defspec format-size-mb-range 50
  (prop/for-all [n (gen/choose (* 1024 1024) (dec (* 1024 1024 1024)))]
    (str/ends-with? (util/format-size n) " MB")))

(defspec format-size-gb-range 50
  (prop/for-all [n (gen/choose (* 1024 1024 1024) (* 10 1024 1024 1024))]
    (str/ends-with? (util/format-size n) " GB")))

;; ---------------------------------------------------------------------------
;; format-size — non-negative input produces non-empty result
;; ---------------------------------------------------------------------------

(defspec format-size-non-empty 100
  (prop/for-all [n gen/nat]
    (pos? (count (util/format-size n)))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — idempotent
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-idempotent-on-keywords 100
  (prop/for-all [k gen/keyword]
    (= k (util/ensure-keyword (util/ensure-keyword k)))))

(defspec ensure-keyword-idempotent-on-strings 100
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    (= (util/ensure-keyword s)
       (util/ensure-keyword (util/ensure-keyword s)))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — keywords pass through
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-keywords-unchanged 100
  (prop/for-all [k gen/keyword]
    (= k (util/ensure-keyword k))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — strings become keywords
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-strings-become-keywords 100
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    (keyword? (util/ensure-keyword s))))

;; ---------------------------------------------------------------------------
;; ensure-keyword — non-string non-keyword passes through
;; ---------------------------------------------------------------------------

(defspec ensure-keyword-other-types-unchanged 50
  (prop/for-all [n gen/nat]
    (= n (util/ensure-keyword n))))
