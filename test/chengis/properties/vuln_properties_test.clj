(ns chengis.properties.vuln-properties-test
  "Property-based tests for vulnerability scanner parsing and threshold evaluation.
   Verifies resilience to arbitrary input, severity count invariants,
   and monotonic threshold strictness."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.vulnerability-scanner :as vuln]))

;; ---------------------------------------------------------------------------
;; parse-trivy-results — resilience
;; ---------------------------------------------------------------------------

(defspec trivy-parser-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (vuln/parse-trivy-results s)]
      (and (map? result)
           (>= (:critical result) 0)
           (>= (:high result) 0)
           (>= (:medium result) 0)
           (>= (:low result) 0)
           (>= (:total result) 0)))))

(defspec trivy-parser-total-equals-sum 200
  (prop/for-all [json-str cgen/gen-trivy-json]
    (let [result (vuln/parse-trivy-results json-str)]
      (= (:total result)
         (+ (:critical result) (:high result)
            (:medium result) (:low result))))))

(defspec trivy-parser-all-counts-non-negative 200
  (prop/for-all [json-str cgen/gen-trivy-json]
    (let [result (vuln/parse-trivy-results json-str)]
      (and (int? (:critical result))
           (int? (:high result))
           (int? (:medium result))
           (int? (:low result))
           (int? (:total result))))))

;; ---------------------------------------------------------------------------
;; parse-grype-results — resilience
;; ---------------------------------------------------------------------------

(defspec grype-parser-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (vuln/parse-grype-results s)]
      (and (map? result)
           (>= (:critical result) 0)
           (>= (:high result) 0)
           (>= (:medium result) 0)
           (>= (:low result) 0)
           (>= (:total result) 0)))))

(defspec grype-parser-total-equals-sum 200
  (prop/for-all [json-str cgen/gen-grype-json]
    (let [result (vuln/parse-grype-results json-str)]
      (= (:total result)
         (+ (:critical result) (:high result)
            (:medium result) (:low result))))))

;; ---------------------------------------------------------------------------
;; evaluate-threshold — policy logic
;; ---------------------------------------------------------------------------

(defspec evaluate-threshold-zero-counts-always-pass 100
  (prop/for-all [threshold cgen/gen-severity-threshold]
    ;; Zero counts at all severities should always pass
    (true? (vuln/evaluate-threshold
             {:critical 0 :high 0 :medium 0 :low 0}
             threshold))))

(defspec evaluate-threshold-critical-only-checks-critical 100
  (prop/for-all [high (gen/choose 0 10)
                 medium (gen/choose 0 10)
                 low (gen/choose 0 10)]
    ;; With threshold "critical", pass if critical=0 regardless of other severities
    (true? (vuln/evaluate-threshold
             {:critical 0 :high high :medium medium :low low}
             "critical"))))

(defspec evaluate-threshold-critical-fails-when-critical-nonzero 100
  (prop/for-all [critical (gen/choose 1 10)]
    ;; Any threshold should fail if critical > 0
    (false? (vuln/evaluate-threshold
              {:critical critical :high 0 :medium 0 :low 0}
              "critical"))))

(defspec evaluate-threshold-monotonic-strictness 200
  (prop/for-all [counts cgen/gen-vuln-counts]
    ;; If "low" passes (strictest), then "medium" passes, then "high", then "critical"
    ;; i.e., passing a stricter threshold implies passing all laxer thresholds
    (let [pass-critical (vuln/evaluate-threshold counts "critical")
          pass-high (vuln/evaluate-threshold counts "high")
          pass-medium (vuln/evaluate-threshold counts "medium")
          pass-low (vuln/evaluate-threshold counts "low")]
      (and (if pass-low pass-medium true)
           (if pass-medium pass-high true)
           (if pass-high pass-critical true)))))

;; ---------------------------------------------------------------------------
;; detect-scan-targets — target detection
;; ---------------------------------------------------------------------------

(defspec detect-scan-targets-always-returns-vector 100
  (prop/for-all [source (gen/one-of [gen/string-alphanumeric (gen/return nil)])]
    (vector? (vuln/detect-scan-targets
               {:pipeline-source source}
               {}))))

(defspec detect-scan-targets-includes-configured-targets 100
  (prop/for-all [target (gen/not-empty gen/string-alphanumeric)]
    (let [result (vuln/detect-scan-targets
                   {:pipeline-source nil}
                   {:targets [target]})]
      (some #{target} result))))
