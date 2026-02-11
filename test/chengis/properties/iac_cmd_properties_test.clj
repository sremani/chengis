(ns chengis.properties.iac-cmd-properties-test
  "Property-based tests for IaC command building and plan parsing.
   Verifies shell quoting safety, name validation, command flag invariants
   for Terraform/Pulumi/CloudFormation, and parse resilience."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.iac :as iac]))

;; Access private functions via var references
(def shell-quote #'chengis.engine.iac/shell-quote)
(def validate-name! #'chengis.engine.iac/validate-name!)

;; ---------------------------------------------------------------------------
;; shell-quote — quoting invariants
;; ---------------------------------------------------------------------------

(defspec shell-quote-wraps-in-single-quotes 200
  (prop/for-all [s cgen/gen-shell-quotable-string]
    (let [quoted (shell-quote s)]
      (and (str/starts-with? quoted "'")
           (str/ends-with? quoted "'")))))

(defspec shell-quote-no-unescaped-single-quotes 200
  (prop/for-all [s cgen/gen-shell-quotable-string]
    (let [quoted (shell-quote s)
          ;; Strip the outer wrapping quotes
          interior (subs quoted 1 (dec (count quoted)))
          ;; Remove all escape sequences '\'' from interior
          ;; Remaining text should contain no bare single quotes
          cleaned (str/replace interior "'\\''" "")]
      (not (str/includes? cleaned "'")))))

(defspec shell-quote-preserves-length 200
  (prop/for-all [s cgen/gen-shell-quotable-string]
    (let [quoted (shell-quote s)]
      (>= (count quoted) (+ (count (str s)) 2)))))

;; ---------------------------------------------------------------------------
;; validate-name! — acceptance and rejection
;; ---------------------------------------------------------------------------

(defspec validate-name-accepts-valid 200
  (prop/for-all [name cgen/gen-valid-name]
    (try
      (validate-name! name "test")
      true
      (catch Exception _
        false))))

(defspec validate-name-rejects-invalid 200
  (prop/for-all [name cgen/gen-invalid-name]
    (try
      (validate-name! name "test")
      false
      (catch Exception _
        true))))

(defspec validate-name-nil-passthrough 10
  (prop/for-all [_ (gen/return nil)]
    (try
      (validate-name! nil "test")
      true
      (catch Exception _
        false))))

;; ---------------------------------------------------------------------------
;; build-terraform-command — flag invariants
;; ---------------------------------------------------------------------------

(defspec build-terraform-always-has-no-color 200
  (prop/for-all [opts cgen/gen-terraform-opts]
    (let [cmd (iac/build-terraform-command opts)]
      (str/includes? cmd "-no-color"))))

(defspec build-terraform-plan-has-out-flag 100
  (prop/for-all [opts (gen/fmap #(assoc % :action "plan") cgen/gen-terraform-opts)]
    (let [cmd (iac/build-terraform-command opts)]
      (str/includes? cmd "-out=tfplan"))))

(defspec build-terraform-apply-has-auto-approve 100
  (prop/for-all [action (gen/elements ["apply" "destroy"])]
    (let [cmd (iac/build-terraform-command {:action action})]
      (str/includes? cmd "-auto-approve"))))

(defspec build-terraform-var-files-preserved 100
  (prop/for-all [var-files (gen/not-empty cgen/gen-var-files)]
    (let [opts {:action "plan" :var-files var-files}
          cmd (iac/build-terraform-command opts)]
      (every? #(str/includes? cmd %) var-files))))

;; ---------------------------------------------------------------------------
;; build-pulumi-command — flag invariants
;; ---------------------------------------------------------------------------

(defspec build-pulumi-always-has-non-interactive 200
  (prop/for-all [opts cgen/gen-pulumi-opts]
    (let [cmd (iac/build-pulumi-command opts)]
      (str/includes? cmd "--non-interactive"))))

(defspec build-pulumi-preview-has-json 100
  (prop/for-all [action (gen/elements ["preview" "output"])]
    (let [cmd (iac/build-pulumi-command {:action action})]
      (str/includes? cmd "--json"))))

(defspec build-pulumi-up-has-yes 100
  (prop/for-all [action (gen/elements ["up" "destroy" "refresh"])]
    (let [cmd (iac/build-pulumi-command {:action action})]
      (str/includes? cmd "--yes"))))

;; ---------------------------------------------------------------------------
;; build-cloudformation-command — flag invariants
;; ---------------------------------------------------------------------------

(defspec build-cf-always-has-json-output 200
  (prop/for-all [opts cgen/gen-cloudformation-opts]
    (let [cmd (iac/build-cloudformation-command opts)]
      (str/includes? cmd "--output json"))))

(defspec build-cf-always-has-no-paginate 200
  (prop/for-all [opts cgen/gen-cloudformation-opts]
    (let [cmd (iac/build-cloudformation-command opts)]
      (str/includes? cmd "--no-paginate"))))

;; ---------------------------------------------------------------------------
;; parse-*-summary — resilience to arbitrary input
;; ---------------------------------------------------------------------------

(defspec parse-terraform-plan-resilience 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-terraform-plan-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)))))

(defspec parse-pulumi-preview-resilience 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-pulumi-preview-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)))))

(defspec parse-cf-changeset-resilience 200
  (prop/for-all [s gen/string]
    (let [result (iac/parse-cloudformation-changeset-summary s)]
      (and (map? result)
           (contains? result :resources-add)
           (contains? result :resources-change)
           (contains? result :resources-destroy)))))
