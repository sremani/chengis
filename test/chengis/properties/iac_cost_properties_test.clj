(ns chengis.properties.iac-cost-properties-test
  "Property-based tests for IaC cost estimation.
   Covers normalize-resource-type (private), estimate-resource-cost,
   estimate-plan-cost, parse-terraform-plan-resources,
   parse-pulumi-preview-resources, parse-cloudformation-changeset,
   and format-cost-summary."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [chengis.generators :as cgen]
            [chengis.engine.iac-cost :as iac-cost]))

(def ^:private normalize-resource-type #'chengis.engine.iac-cost/normalize-resource-type)

;; ---------------------------------------------------------------------------
;; normalize-resource-type — terraform just lowercases
;; ---------------------------------------------------------------------------

(defspec normalize-terraform-lowercases 100
  (prop/for-all [rt cgen/gen-terraform-resource-type]
    (= (str/lower-case rt)
       (normalize-resource-type rt :terraform))))

;; ---------------------------------------------------------------------------
;; normalize-resource-type — nil tool-type defaults to lowercase
;; ---------------------------------------------------------------------------

(defspec normalize-nil-tool-lowercases 100
  (prop/for-all [rt cgen/gen-terraform-resource-type]
    (= (str/lower-case rt)
       (normalize-resource-type rt nil))))

;; ---------------------------------------------------------------------------
;; normalize-resource-type — pulumi takes first+third parts
;; ---------------------------------------------------------------------------

(defspec normalize-pulumi-splits-on-colon 100
  (prop/for-all [rt cgen/gen-pulumi-resource-type]
    (let [parts (str/split rt #":")
          result (normalize-resource-type rt :pulumi)]
      (if (>= (count parts) 3)
        (= result (str (str/lower-case (first parts)) "_"
                       (str/lower-case (nth parts 2))))
        true))))

;; ---------------------------------------------------------------------------
;; normalize-resource-type — pulumi result is always lowercase
;; ---------------------------------------------------------------------------

(defspec normalize-pulumi-always-lowercase 100
  (prop/for-all [rt cgen/gen-pulumi-resource-type]
    (let [result (normalize-resource-type rt :pulumi)]
      (= result (str/lower-case result)))))

;; ---------------------------------------------------------------------------
;; normalize-resource-type — cloudformation splits on ::
;; ---------------------------------------------------------------------------

(defspec normalize-cloudformation-splits-on-double-colon 100
  (prop/for-all [rt cgen/gen-cloudformation-resource-type]
    (let [parts (str/split rt #"::")
          result (normalize-resource-type rt :cloudformation)]
      (if (>= (count parts) 3)
        (str/starts-with? result (str/lower-case (first parts)))
        true))))

;; ---------------------------------------------------------------------------
;; normalize-resource-type — cloudformation result is lowercase
;; ---------------------------------------------------------------------------

(defspec normalize-cloudformation-always-lowercase 100
  (prop/for-all [rt cgen/gen-cloudformation-resource-type]
    (let [result (normalize-resource-type rt :cloudformation)]
      (= result (str/lower-case result)))))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — billable actions return non-negative cost
;; ---------------------------------------------------------------------------

(defspec billable-actions-non-negative 200
  (prop/for-all [rt cgen/gen-terraform-resource-type
                 action (gen/elements ["create" "update" "replace"])]
    (>= (:monthly (iac-cost/estimate-resource-cost rt action)) 0.0)))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — non-billable actions return zero cost
;; ---------------------------------------------------------------------------

(defspec non-billable-actions-zero-cost 200
  (prop/for-all [rt cgen/gen-terraform-resource-type
                 action (gen/elements ["delete" "no-op" "unknown"])]
    (= 0.0 (:monthly (iac-cost/estimate-resource-cost rt action)))))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — unknown types return "Unknown resource"
;; ---------------------------------------------------------------------------

(defspec unknown-resource-type-description 100
  (prop/for-all [action cgen/gen-resource-action]
    (let [result (iac-cost/estimate-resource-cost "nonexistent_xyz_widget" action)]
      (= "Unknown resource" (:description result)))))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — resource-type in result is always lowercased
;; ---------------------------------------------------------------------------

(defspec resource-cost-type-always-lowercase 100
  (prop/for-all [rt gen/string-alphanumeric
                 action cgen/gen-resource-action]
    (let [result (iac-cost/estimate-resource-cost rt action)]
      (= (:resource-type result) (str/lower-case rt)))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — resource count preserved
;; ---------------------------------------------------------------------------

(defspec plan-cost-resource-count-matches-input 100
  (prop/for-all [resources cgen/gen-parsed-resources
                 tool-type cgen/gen-tool-type-keyword]
    (let [result (iac-cost/estimate-plan-cost resources tool-type)]
      (= (count resources) (count (:resources result))))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — currency always USD
;; ---------------------------------------------------------------------------

(defspec plan-cost-currency-always-usd 100
  (prop/for-all [resources cgen/gen-parsed-resources
                 tool-type cgen/gen-tool-type-keyword]
    (= "USD" (:currency (iac-cost/estimate-plan-cost resources tool-type)))))

;; ---------------------------------------------------------------------------
;; parse-terraform-plan-resources — round-trip structure
;; ---------------------------------------------------------------------------

(defspec terraform-parse-resource-structure 200
  (prop/for-all [plan-json cgen/gen-terraform-plan-json]
    (let [parsed (iac-cost/parse-terraform-plan-resources plan-json)]
      (and (vector? parsed)
           (every? #(contains? % :resource-type) parsed)
           (every? #(contains? % :action) parsed)
           (every? #(contains? % :name) parsed)))))

;; ---------------------------------------------------------------------------
;; parse-terraform-plan-resources — invalid JSON returns empty vec
;; ---------------------------------------------------------------------------

(defspec terraform-parse-invalid-json-returns-empty 50
  (prop/for-all [s gen/string-alphanumeric]
    (= [] (iac-cost/parse-terraform-plan-resources s))))

;; ---------------------------------------------------------------------------
;; parse-pulumi-preview-resources — round-trip structure
;; ---------------------------------------------------------------------------

(defspec pulumi-parse-resource-structure 200
  (prop/for-all [preview-json cgen/gen-pulumi-preview-json]
    (let [parsed (iac-cost/parse-pulumi-preview-resources preview-json)]
      (and (vector? parsed)
           (every? #(contains? % :resource-type) parsed)
           (every? #(contains? % :action) parsed)
           (every? #(contains? % :name) parsed)))))

;; ---------------------------------------------------------------------------
;; parse-pulumi-preview-resources — "same" maps to "no-op"
;; ---------------------------------------------------------------------------

(defspec pulumi-same-maps-to-noop 50
  (prop/for-all [_ (gen/return nil)]
    (let [json-str (json/write-str
                     {:steps [{:type "aws:s3:Bucket" :name "b1" :op "same"
                               :urn "urn:pulumi:stack::aws:s3:Bucket::b1"}]})
          parsed (iac-cost/parse-pulumi-preview-resources json-str)]
      (= "no-op" (:action (first parsed))))))

;; ---------------------------------------------------------------------------
;; parse-cloudformation-changeset — round-trip structure
;; ---------------------------------------------------------------------------

(defspec cloudformation-parse-resource-structure 200
  (prop/for-all [cs-json cgen/gen-cf-changeset-json]
    (let [parsed (iac-cost/parse-cloudformation-changeset cs-json)]
      (and (vector? parsed)
           (every? #(contains? % :resource-type) parsed)
           (every? #(contains? % :action) parsed)
           (every? #(contains? % :name) parsed)))))

;; ---------------------------------------------------------------------------
;; parse-cloudformation-changeset — invalid JSON returns empty vec
;; ---------------------------------------------------------------------------

(defspec cloudformation-parse-invalid-returns-empty 50
  (prop/for-all [s gen/string-alphanumeric]
    (= [] (iac-cost/parse-cloudformation-changeset s))))

;; ---------------------------------------------------------------------------
;; format-cost-summary — contains billable count
;; ---------------------------------------------------------------------------

(defspec format-cost-summary-contains-billable 100
  (prop/for-all [estimate cgen/gen-cost-estimate]
    (str/includes? (iac-cost/format-cost-summary estimate) "billable")))
