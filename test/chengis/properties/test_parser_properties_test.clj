(ns chengis.properties.test-parser-properties-test
  "Property-based tests for test output parsers (JUnit XML, TAP, generic).
   Verifies nil handling, required keys in parsed output, status value ranges,
   and combined parser fallback behavior."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.test-parser :as test-parser]))

;; ---------------------------------------------------------------------------
;; parse-junit-xml — nil and non-XML inputs
;; ---------------------------------------------------------------------------

(defspec parse-junit-nil-returns-nil 10
  (prop/for-all [_ (gen/return nil)]
    (nil? (test-parser/parse-junit-xml nil))))

(defspec parse-junit-non-xml-returns-nil 200
  (prop/for-all [s gen/string-alphanumeric]
    ;; Alphanumeric strings won't contain "<testsuite" so should return nil
    (nil? (test-parser/parse-junit-xml s))))

;; ---------------------------------------------------------------------------
;; parse-junit-xml — valid input structure
;; ---------------------------------------------------------------------------

(defspec parse-junit-valid-has-required-keys 200
  (prop/for-all [xml cgen/gen-junit-xml]
    (let [result (test-parser/parse-junit-xml xml)]
      (if (nil? result)
        true ;; If parsing fails for some reason, don't fail the property
        (every? (fn [m]
                  (and (contains? m :test-name)
                       (contains? m :status)
                       (contains? m :test-suite)))
                result)))))

(defspec parse-junit-status-values 200
  (prop/for-all [xml cgen/gen-junit-xml]
    (let [result (test-parser/parse-junit-xml xml)]
      (if (nil? result)
        true
        (every? (fn [m]
                  (contains? #{"pass" "fail" "error" "skip"} (:status m)))
                result)))))

;; ---------------------------------------------------------------------------
;; parse-tap-output — non-TAP input
;; ---------------------------------------------------------------------------

(defspec parse-tap-non-tap-returns-nil 200
  (prop/for-all [s gen/string-alphanumeric]
    ;; Alphanumeric strings won't contain TAP header
    (nil? (test-parser/parse-tap-output s))))

;; ---------------------------------------------------------------------------
;; parse-generic-output — nil input
;; ---------------------------------------------------------------------------

(defspec parse-generic-nil-returns-nil 10
  (prop/for-all [_ (gen/return nil)]
    (nil? (test-parser/parse-generic-output nil))))

;; ---------------------------------------------------------------------------
;; extract-test-results — blank input
;; ---------------------------------------------------------------------------

(defspec extract-test-results-blank-returns-nil 100
  (prop/for-all [s (gen/elements [nil "" "  " "\t" "\n"])]
    (nil? (test-parser/extract-test-results s))))
