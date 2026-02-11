(ns chengis.properties.dag-properties-test
  "Property-based tests for DAG (Directed Acyclic Graph) utilities.
   Verifies topological sort ordering invariants, ready-stage computation,
   cycle detection, and DAG construction validation."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.set :as set]
            [chengis.generators :as cgen]
            [chengis.engine.dag :as dag]))

;; ---------------------------------------------------------------------------
;; has-dag? — detection of dependency declarations
;; ---------------------------------------------------------------------------

(defspec has-dag-true-when-depends-on-present 100
  (prop/for-all [stages cgen/gen-stages-with-deps]
    ;; If any stage has :depends-on, has-dag? should return true
    ;; (gen-stages-with-deps may or may not include :depends-on depending on DAG structure)
    (let [any-deps? (some :depends-on stages)]
      (= (boolean any-deps?) (dag/has-dag? stages)))))

(defspec has-dag-false-when-no-depends-on 100
  (prop/for-all [stages cgen/gen-stages-without-deps]
    (false? (dag/has-dag? stages))))

;; ---------------------------------------------------------------------------
;; topological-sort — ordering invariants
;; ---------------------------------------------------------------------------

(defspec topological-sort-result-count-equals-input 200
  (prop/for-all [dag cgen/gen-valid-dag]
    (= (count dag) (count (dag/topological-sort dag)))))

(defspec topological-sort-contains-all-nodes 200
  (prop/for-all [dag cgen/gen-valid-dag]
    (= (set (keys dag)) (set (dag/topological-sort dag)))))

(defspec topological-sort-deps-before-dependents 200
  (prop/for-all [dag cgen/gen-valid-dag]
    (let [sorted (dag/topological-sort dag)
          pos (zipmap sorted (range))]
      ;; For every node, all its dependencies appear earlier in the sort
      (every? (fn [[node deps]]
                (every? (fn [dep]
                          (< (get pos dep) (get pos node)))
                        deps))
              dag))))

(defspec topological-sort-empty-dag 10
  (prop/for-all [_ (gen/return nil)]
    (empty? (dag/topological-sort {}))))

(defspec topological-sort-single-node 50
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)]
    (= [name] (dag/topological-sort {name #{}}))))

;; ---------------------------------------------------------------------------
;; topological-sort — cycle detection
;; ---------------------------------------------------------------------------

(defspec topological-sort-detects-self-cycle 50
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)]
    (try
      (dag/topological-sort {name #{name}})
      false ;; Should have thrown
      (catch Exception e
        (= :dag-cycle (:type (ex-data e)))))))

(defspec topological-sort-detects-two-node-cycle 50
  (prop/for-all [_ (gen/return nil)]
    (try
      (dag/topological-sort {"a" #{"b"} "b" #{"a"}})
      false
      (catch Exception e
        (= :dag-cycle (:type (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; ready-stages — dependency satisfaction
;; ---------------------------------------------------------------------------

(defspec ready-stages-initial-are-roots 200
  (prop/for-all [dag cgen/gen-valid-dag]
    ;; With no completed stages, only root nodes (no deps) should be ready
    (let [ready (dag/ready-stages dag #{})]
      (every? (fn [stage]
                (empty? (get dag stage)))
              ready))))

(defspec ready-stages-never-includes-completed 200
  (prop/for-all [dag cgen/gen-valid-dag]
    (let [all-nodes (set (keys dag))
          ;; Complete some random subset — just the roots for simplicity
          roots (set (filter #(empty? (get dag %)) all-nodes))
          ready (dag/ready-stages dag roots)]
      ;; Ready set should not contain any already-completed stage
      (empty? (set/intersection ready roots)))))

(defspec ready-stages-all-deps-satisfied 200
  (prop/for-all [dag cgen/gen-valid-dag]
    ;; Complete the root nodes, then check that all ready stages have deps satisfied
    (let [roots (set (filter #(empty? (get dag %)) (keys dag)))
          ready (dag/ready-stages dag roots)]
      (every? (fn [stage]
                (every? #(contains? roots %) (get dag stage)))
              ready))))

(defspec ready-stages-fully-completed-returns-empty 100
  (prop/for-all [dag cgen/gen-valid-dag]
    ;; If all stages are completed, ready should be empty
    (empty? (dag/ready-stages dag (set (keys dag))))))

;; ---------------------------------------------------------------------------
;; stage-by-name — lookup
;; ---------------------------------------------------------------------------

(defspec stage-by-name-finds-existing 100
  (prop/for-all [stages cgen/gen-stages-with-deps]
    (let [first-stage (first stages)]
      (= first-stage (dag/stage-by-name stages (:stage-name first-stage))))))

(defspec stage-by-name-nil-for-missing 100
  (prop/for-all [stages cgen/gen-stages-with-deps]
    (nil? (dag/stage-by-name stages "nonexistent-stage-xyz"))))

;; ---------------------------------------------------------------------------
;; build-dag — validation
;; ---------------------------------------------------------------------------

(defspec build-dag-from-valid-stages 100
  (prop/for-all [stages cgen/gen-stages-with-deps]
    (let [dag (dag/build-dag stages)]
      ;; Should produce a valid DAG map with all stage names
      (and (map? dag)
           (= (set (map :stage-name stages)) (set (keys dag)))))))
