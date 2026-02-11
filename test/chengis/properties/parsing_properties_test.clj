(ns chengis.properties.parsing-properties-test
  "Property-based tests for IaC plan JSON parsers, resource type normalization,
   and state diffing. Verifies resilience to arbitrary input and structural
   invariants of parsed output."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.iac :as iac]
            [chengis.engine.iac-state :as iac-state]
            [chengis.engine.iac-cost :as iac-cost]))

;; Access private functions via var references
(def normalize-resource-type #'chengis.engine.iac-cost/normalize-resource-type)

;; ---------------------------------------------------------------------------
;; Terraform plan parser — resilience
;; ---------------------------------------------------------------------------

(defspec terraform-parser-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-terraform-plan-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)
           (contains? result :resources)
           (vector? (:resources result))
           (>= (:resources-add result) 0)
           (>= (:resources-change result) 0)
           (>= (:resources-destroy result) 0)))))

(defspec terraform-parser-valid-json-resource-counts 200
  (prop/for-all [json-str cgen/gen-terraform-plan-json]
    (let [result (iac/parse-terraform-plan-summary json-str)]
      ;; Counts should be non-negative integers
      (and (int? (:resources-add result))
           (int? (:resources-change result))
           (int? (:resources-destroy result))
           (>= (:resources-add result) 0)
           (>= (:resources-change result) 0)
           (>= (:resources-destroy result) 0)))))

;; ---------------------------------------------------------------------------
;; Pulumi preview parser — resilience
;; ---------------------------------------------------------------------------

(defspec pulumi-parser-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-pulumi-preview-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)
           (contains? result :resources)
           (vector? (:resources result))))))

(defspec pulumi-parser-valid-json-resource-counts 200
  (prop/for-all [json-str cgen/gen-pulumi-preview-json]
    (let [result (iac/parse-pulumi-preview-summary json-str)]
      (and (int? (:resources-add result))
           (int? (:resources-change result))
           (int? (:resources-destroy result))
           (>= (:resources-add result) 0)
           (>= (:resources-change result) 0)
           (>= (:resources-destroy result) 0)))))

;; ---------------------------------------------------------------------------
;; CloudFormation changeset parser — resilience
;; ---------------------------------------------------------------------------

(defspec cloudformation-parser-never-throws-on-arbitrary-input 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-cloudformation-changeset-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)
           (contains? result :resources)
           (vector? (:resources result))))))

(defspec cloudformation-parser-valid-json-resource-counts 200
  (prop/for-all [json-str cgen/gen-cloudformation-changeset-json]
    (let [result (iac/parse-cloudformation-changeset-summary json-str)]
      (and (int? (:resources-add result))
           (int? (:resources-change result))
           (int? (:resources-destroy result))
           (>= (:resources-add result) 0)
           (>= (:resources-change result) 0)
           (>= (:resources-destroy result) 0)))))

;; ---------------------------------------------------------------------------
;; Unified parser dispatch
;; ---------------------------------------------------------------------------

(defspec parse-plan-summary-unknown-tool-returns-zeros 50
  (prop/for-all [s gen/string]
    (let [result (iac/parse-plan-summary "unknown-tool" s)]
      (and (= 0 (:resources-add result))
           (= 0 (:resources-change result))
           (= 0 (:resources-destroy result))
           (empty? (:resources result))))))

;; ---------------------------------------------------------------------------
;; Resource type normalization
;; ---------------------------------------------------------------------------

(defspec normalize-resource-type-always-lowercase 200
  (prop/for-all [rt gen/string-alphanumeric
                 tool cgen/gen-tool-type]
    (let [normalized (normalize-resource-type rt tool)]
      (= normalized (clojure.string/lower-case normalized)))))

(defspec normalize-resource-type-produces-string 100
  (prop/for-all [rt gen/string
                 tool cgen/gen-tool-type]
    (string? (normalize-resource-type rt tool))))

(defspec normalize-terraform-type-identity 100
  (prop/for-all [rt gen/string-alphanumeric]
    ;; For terraform, normalization should just lowercase
    (= (clojure.string/lower-case rt)
       (normalize-resource-type rt "terraform"))))

(defspec normalize-pulumi-type-underscore-format 100
  (prop/for-all [rt cgen/gen-pulumi-resource-type]
    (let [normalized (normalize-resource-type rt "pulumi")]
      ;; Should contain underscore separators, not colons
      (and (not (clojure.string/includes? normalized ":"))
           (clojure.string/includes? normalized "_")))))

(defspec normalize-cloudformation-type-underscore-format 100
  (prop/for-all [rt cgen/gen-cloudformation-resource-type]
    (let [normalized (normalize-resource-type rt "cloudformation")]
      ;; Should not contain :: separators
      (not (clojure.string/includes? normalized "::")))))

;; ---------------------------------------------------------------------------
;; Cost resource parsers — resilience
;; ---------------------------------------------------------------------------

(defspec terraform-cost-parser-never-throws 100
  (prop/for-all [s gen/string]
    (let [result (iac-cost/parse-terraform-plan-resources s)]
      (and (vector? result)
           (every? map? result)))))

(defspec pulumi-cost-parser-never-throws 100
  (prop/for-all [s gen/string]
    (let [result (iac-cost/parse-pulumi-preview-resources s)]
      (and (vector? result)
           (every? map? result)))))

(defspec cloudformation-cost-parser-never-throws 100
  (prop/for-all [s gen/string]
    (let [result (iac-cost/parse-cloudformation-changeset s)]
      (and (vector? result)
           (every? map? result)))))

;; ---------------------------------------------------------------------------
;; State diffing properties
;; ---------------------------------------------------------------------------

(defspec diff-states-identity 100
  (prop/for-all [json-str cgen/gen-terraform-state-json]
    ;; Diffing a state against itself should produce no changes
    (let [result (iac-state/diff-states json-str json-str)]
      (and (empty? (:added result))
           (empty? (:removed result))
           (empty? (:changed result))))))

(defspec diff-states-symmetry 100
  (prop/for-all [json-a cgen/gen-terraform-state-json
                 json-b cgen/gen-terraform-state-json]
    ;; What's "added" in diff(a,b) should be "removed" in diff(b,a)
    (let [ab (iac-state/diff-states json-a json-b)
          ba (iac-state/diff-states json-b json-a)]
      (and (= (set (:added ab)) (set (:removed ba)))
           (= (set (:removed ab)) (set (:added ba)))
           (= (set (:changed ab)) (set (:changed ba)))))))

(defspec diff-states-never-throws-on-arbitrary-input 100
  (prop/for-all [a gen/string
                 b gen/string]
    (let [result (iac-state/diff-states a b)]
      (and (map? result)
           (contains? result :added)
           (contains? result :removed)
           (contains? result :changed)
           (vector? (:added result))
           (vector? (:removed result))
           (vector? (:changed result))))))
