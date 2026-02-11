(ns chengis.properties.cost-properties-test
  "Property-based tests for IaC cost estimation.
   Verifies mathematical invariants (non-negative costs, hourly/monthly ratio),
   monotonicity (more resources ≥ more cost), and formatting properties."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.iac-cost :as iac-cost]))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — non-negativity
;; ---------------------------------------------------------------------------

(defspec estimate-plan-cost-non-negative 200
  (prop/for-all [resources cgen/gen-parsed-resources
                 tool-type cgen/gen-tool-type-keyword]
    (let [result (iac-cost/estimate-plan-cost resources tool-type)]
      (and (>= (:total-monthly result) 0.0)
           (>= (:total-hourly result) 0.0)))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — hourly = monthly / 730
;; ---------------------------------------------------------------------------

(defspec estimate-plan-cost-hourly-monthly-ratio 200
  (prop/for-all [resources cgen/gen-parsed-resources
                 tool-type cgen/gen-tool-type-keyword]
    (let [result (iac-cost/estimate-plan-cost resources tool-type)
          expected-hourly (/ (:total-monthly result) 730.0)]
      ;; Allow for floating-point rounding (within 0.0001)
      (< (Math/abs (- (:total-hourly result) expected-hourly)) 0.0001))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — always returns expected keys
;; ---------------------------------------------------------------------------

(defspec estimate-plan-cost-structure 100
  (prop/for-all [resources cgen/gen-parsed-resources
                 tool-type cgen/gen-tool-type-keyword]
    (let [result (iac-cost/estimate-plan-cost resources tool-type)]
      (and (contains? result :total-monthly)
           (contains? result :total-hourly)
           (contains? result :currency)
           (contains? result :resources)
           (= "USD" (:currency result))
           (vector? (:resources result))))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — empty resources = zero cost
;; ---------------------------------------------------------------------------

(defspec estimate-plan-cost-empty-is-zero 50
  (prop/for-all [tool-type cgen/gen-tool-type-keyword]
    (let [result (iac-cost/estimate-plan-cost [] tool-type)]
      (and (= 0.0 (:total-monthly result))
           (= 0.0 (:total-hourly result))
           (empty? (:resources result))))))

;; ---------------------------------------------------------------------------
;; estimate-plan-cost — monotonicity (superset ≥ subset cost)
;; ---------------------------------------------------------------------------

(defspec estimate-plan-cost-monotonic 100
  (prop/for-all [resources cgen/gen-parsed-resources
                 extra cgen/gen-parsed-resource
                 tool-type cgen/gen-tool-type-keyword]
    (let [base-cost (:total-monthly (iac-cost/estimate-plan-cost resources tool-type))
          more-cost (:total-monthly (iac-cost/estimate-plan-cost (conj resources extra) tool-type))]
      (>= more-cost base-cost))))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — delete actions are free
;; ---------------------------------------------------------------------------

(defspec delete-actions-cost-zero 100
  (prop/for-all [rt cgen/gen-terraform-resource-type]
    (= 0.0 (:monthly (iac-cost/estimate-resource-cost rt "delete")))))

(defspec no-op-actions-cost-zero 100
  (prop/for-all [rt cgen/gen-terraform-resource-type]
    (= 0.0 (:monthly (iac-cost/estimate-resource-cost rt "no-op")))))

;; ---------------------------------------------------------------------------
;; estimate-resource-cost — always returns expected structure
;; ---------------------------------------------------------------------------

(defspec estimate-resource-cost-structure 100
  (prop/for-all [rt gen/string-alphanumeric
                 action cgen/gen-resource-action]
    (let [result (iac-cost/estimate-resource-cost rt action)]
      (and (map? result)
           (contains? result :resource-type)
           (contains? result :monthly)
           (contains? result :action)
           (contains? result :description)
           (number? (:monthly result))
           (>= (:monthly result) 0.0)))))

;; ---------------------------------------------------------------------------
;; format-cost-summary — structural properties
;; ---------------------------------------------------------------------------

(defspec format-cost-summary-produces-string 100
  (prop/for-all [estimate cgen/gen-cost-estimate]
    (string? (iac-cost/format-cost-summary estimate))))

(defspec format-cost-summary-contains-dollar-sign 100
  (prop/for-all [estimate cgen/gen-cost-estimate]
    (str/includes? (iac-cost/format-cost-summary estimate) "$")))

(defspec format-cost-summary-contains-month-and-hour 100
  (prop/for-all [estimate cgen/gen-cost-estimate]
    (let [summary (iac-cost/format-cost-summary estimate)]
      (and (str/includes? summary "/month")
           (str/includes? summary "/hour")))))

(defspec format-cost-summary-contains-resource-count 100
  (prop/for-all [estimate cgen/gen-cost-estimate]
    (let [summary (iac-cost/format-cost-summary estimate)]
      ;; Should mention "resource" or "resources"
      (str/includes? summary "resource"))))

(defspec format-cost-summary-handles-empty-estimate 10
  (prop/for-all [_ (gen/return nil)]
    (let [summary (iac-cost/format-cost-summary {})]
      (and (string? summary)
           (str/includes? summary "$0.00/month")))))
