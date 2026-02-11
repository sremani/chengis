(ns chengis.properties.template-merge-properties-test
  "Property-based tests for template pipeline merging.
   Verifies stage merge semantics (empty, override, append, name union)
   and pipeline-level merge rules (env, artifacts, stages)."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [chengis.generators :as cgen]
            [chengis.dsl.templates :as templates]))

;; Access private functions via var references
(def merge-stages #'chengis.dsl.templates/merge-stages)
(def merge-pipeline #'chengis.dsl.templates/merge-pipeline)

;; ---------------------------------------------------------------------------
;; merge-stages — empty inputs
;; ---------------------------------------------------------------------------

(defspec merge-stages-empty-pipeline-returns-template 200
  (prop/for-all [template cgen/gen-stage-defs]
    (= template (merge-stages template []))))

(defspec merge-stages-empty-template-returns-pipeline 200
  (prop/for-all [pipeline cgen/gen-stage-defs]
    (= pipeline (merge-stages [] pipeline))))

;; ---------------------------------------------------------------------------
;; merge-stages — override by name
;; ---------------------------------------------------------------------------

(defspec merge-stages-override-by-name 100
  (prop/for-all [template cgen/gen-stage-defs
                 cmd (gen/not-empty gen/string-alphanumeric)]
    (let [;; Create a pipeline stage that shares the name of the first template stage
          target-name (:stage-name (first template))
          override-stage {:stage-name target-name
                          :steps [{:type :shell :command cmd}]}
          result (merge-stages template [override-stage])
          ;; Find the stage with target-name in the result
          found (first (filter #(= target-name (:stage-name %)) result))]
      (= override-stage found))))

;; ---------------------------------------------------------------------------
;; merge-stages — append new stages
;; ---------------------------------------------------------------------------

(defspec merge-stages-append-new-stages 100
  (prop/for-all [template cgen/gen-stage-defs
                 new-name (gen/not-empty gen/string-alphanumeric)
                 cmd (gen/not-empty gen/string-alphanumeric)]
    (let [template-names (set (map :stage-name template))
          ;; Ensure the new stage name does not collide with template names
          unique-name (str "unique-new-" new-name)
          new-stage {:stage-name unique-name
                     :steps [{:type :shell :command cmd}]}
          result (merge-stages template [new-stage])]
      (if (contains? template-names unique-name)
        true ;; Skip if collision happens (unlikely but possible)
        (= (inc (count template)) (count result))))))

;; ---------------------------------------------------------------------------
;; merge-stages — all names present
;; ---------------------------------------------------------------------------

(defspec merge-stages-all-names-present 200
  (prop/for-all [template cgen/gen-stage-defs
                 pipeline cgen/gen-stage-defs]
    (let [result (merge-stages template pipeline)
          result-names (set (map :stage-name result))
          template-names (set (map :stage-name template))
          pipeline-names (set (map :stage-name pipeline))]
      (= result-names (set/union template-names pipeline-names)))))

;; ---------------------------------------------------------------------------
;; merge-pipeline — env extension wins
;; ---------------------------------------------------------------------------

(defspec merge-pipeline-env-extension-wins 200
  (prop/for-all [shared-key (gen/not-empty gen/string-alphanumeric)
                 base-val (gen/not-empty gen/string-alphanumeric)
                 ext-val (gen/not-empty gen/string-alphanumeric)
                 base-stages cgen/gen-stage-defs
                 ext-stages cgen/gen-stage-defs]
    (let [base {:stages base-stages :env {shared-key base-val}}
          extension {:stages ext-stages :env {shared-key ext-val}}
          result (merge-pipeline base extension)]
      (= ext-val (get-in result [:env shared-key])))))

;; ---------------------------------------------------------------------------
;; merge-pipeline — artifacts deduplicated
;; ---------------------------------------------------------------------------

(defspec merge-pipeline-artifacts-deduplicated 200
  (prop/for-all [base-arts (gen/vector (gen/not-empty gen/string-alphanumeric) 0 3)
                 ext-arts (gen/vector (gen/not-empty gen/string-alphanumeric) 0 3)
                 base-stages cgen/gen-stage-defs
                 ext-stages cgen/gen-stage-defs]
    (let [base {:stages base-stages :artifacts base-arts}
          extension {:stages ext-stages :artifacts ext-arts}
          result (merge-pipeline base extension)
          expected (vec (distinct (concat base-arts ext-arts)))]
      (= expected (:artifacts result)))))

;; ---------------------------------------------------------------------------
;; merge-pipeline — stages use merge-stages
;; ---------------------------------------------------------------------------

(defspec merge-pipeline-stages-use-merge-stages 200
  (prop/for-all [base-stages cgen/gen-stage-defs
                 ext-stages cgen/gen-stage-defs]
    (let [base {:stages base-stages}
          extension {:stages ext-stages}
          result (merge-pipeline base extension)
          expected-stages (merge-stages base-stages ext-stages)]
      (= (count expected-stages) (count (:stages result))))))
