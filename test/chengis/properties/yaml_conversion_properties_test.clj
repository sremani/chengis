(ns chengis.properties.yaml-conversion-properties-test
  "Property-based tests for YAML workflow conversion functions.
   Verifies condition conversion, step type detection, stage structure,
   parameter key mapping, and validation invariants."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [chengis.generators :as cgen]
            [chengis.dsl.yaml :as yaml]))

;; Access private functions via var references
(def convert-yaml-condition #'chengis.dsl.yaml/convert-yaml-condition)
(def convert-yaml-step #'chengis.dsl.yaml/convert-yaml-step)
(def convert-yaml-stage #'chengis.dsl.yaml/convert-yaml-stage)
(def convert-yaml-parameters #'chengis.dsl.yaml/convert-yaml-parameters)

;; ---------------------------------------------------------------------------
;; convert-yaml-condition — branch conditions
;; ---------------------------------------------------------------------------

(defspec convert-condition-branch 200
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)]
    (let [result (convert-yaml-condition {:branch name})]
      (and (= :branch (:type result))
           (= name (:value result))))))

(defspec convert-condition-param 200
  (prop/for-all [p (gen/not-empty gen/string-alphanumeric)
                 v (gen/not-empty gen/string-alphanumeric)]
    (let [result (convert-yaml-condition {:param p :value v})]
      (and (= :param (:type result))
           (= p (:param result))
           (= v (:value result))))))

(defspec convert-condition-nil 10
  (prop/for-all [_ (gen/return nil)]
    (nil? (convert-yaml-condition nil))))

;; ---------------------------------------------------------------------------
;; convert-yaml-step — required keys and type detection
;; ---------------------------------------------------------------------------

(defspec convert-step-has-required-keys 200
  (prop/for-all [step cgen/gen-yaml-step]
    (let [result (convert-yaml-step step)]
      (and (contains? result :step-name)
           (contains? result :type)
           (contains? result :command)))))

(defspec convert-step-type-shell-without-image 200
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)
                 cmd (gen/not-empty gen/string-alphanumeric)]
    (let [step {:name name :run cmd}
          result (convert-yaml-step step)]
      (= :shell (:type result)))))

(defspec convert-step-type-docker-with-image 200
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)
                 cmd (gen/not-empty gen/string-alphanumeric)
                 image (gen/elements ["alpine" "node:18" "python:3"])]
    (let [step {:name name :run cmd :image image}
          result (convert-yaml-step step)]
      (= :docker (:type result)))))

;; ---------------------------------------------------------------------------
;; convert-yaml-stage — structure invariants
;; ---------------------------------------------------------------------------

(defspec convert-stage-has-required-keys 200
  (prop/for-all [stage cgen/gen-yaml-stage]
    (let [result (convert-yaml-stage stage)]
      (and (contains? result :stage-name)
           (contains? result :parallel?)
           (contains? result :steps)
           (string? (:stage-name result))
           (boolean? (:parallel? result))
           (vector? (:steps result))))))

;; ---------------------------------------------------------------------------
;; convert-yaml-parameters — key name mapping
;; ---------------------------------------------------------------------------

(defspec convert-parameters-keys-match 200
  (prop/for-all [params cgen/gen-yaml-parameters]
    (let [result (convert-yaml-parameters params)
          result-names (set (map :name result))
          input-names (set (map name (keys params)))]
      (= result-names input-names))))

;; ---------------------------------------------------------------------------
;; validate-yaml-workflow — validity invariants
;; ---------------------------------------------------------------------------

(defspec validate-valid-iff-no-errors 200
  (prop/for-all [stages (gen/vector cgen/gen-yaml-stage 1 3)]
    (let [workflow {:stages stages}
          {:keys [valid? errors]} (yaml/validate-yaml-workflow workflow)
          ;; Also test with invalid input (non-map)
          invalid-result (yaml/validate-yaml-workflow "not-a-map")]
      (and ;; valid? true iff no errors for well-formed input
           (= valid? (empty? errors))
           ;; non-map is always invalid
           (false? (:valid? invalid-result))
           (seq (:errors invalid-result))))))

(defspec validate-missing-stages-invalid 200
  (prop/for-all [extra-keys (gen/map gen/keyword (gen/not-empty gen/string-alphanumeric) {:max-elements 3})]
    (let [data (dissoc extra-keys :stages)
          {:keys [valid?]} (yaml/validate-yaml-workflow data)]
      (false? valid?))))
