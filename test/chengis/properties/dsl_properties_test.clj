(ns chengis.properties.dsl-properties-test
  "Property-based tests for DSL builder functions.
   Verifies structural invariants of pipeline building blocks:
   sh always produces :shell type, step preserves action, parallel marks
   :parallel? true, when-branch adds conditions to all steps."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.dsl.core :as dsl]))

;; ---------------------------------------------------------------------------
;; sh — always produces :type :shell
;; ---------------------------------------------------------------------------

(defspec sh-always-shell-type 100
  (prop/for-all [cmd gen/string]
    (= :shell (:type (dsl/sh cmd)))))

(defspec sh-preserves-command 100
  (prop/for-all [cmd gen/string]
    (= cmd (:command (dsl/sh cmd)))))

(defspec sh-with-env-includes-env 100
  (prop/for-all [cmd gen/string
                 env-key (gen/not-empty gen/string-alphanumeric)
                 env-val (gen/not-empty gen/string-alphanumeric)]
    (let [action (dsl/sh cmd :env {env-key env-val})]
      (and (= :shell (:type action))
           (= cmd (:command action))
           (= {env-key env-val} (:env action))))))

(defspec sh-with-timeout-includes-timeout 100
  (prop/for-all [cmd gen/string
                 timeout gen/nat]
    (let [action (dsl/sh cmd :timeout timeout)]
      (and (= :shell (:type action))
           (= timeout (:timeout action))))))

;; ---------------------------------------------------------------------------
;; step — adds :step-name to action
;; ---------------------------------------------------------------------------

(defspec step-adds-name 100
  (prop/for-all [name gen/string-alphanumeric
                 cmd gen/string]
    (let [action (dsl/sh cmd)
          s (dsl/step name action)]
      (and (= name (:step-name s))
           ;; Original action keys preserved
           (= :shell (:type s))
           (= cmd (:command s))))))

(defspec step-preserves-all-action-keys 100
  (prop/for-all [name gen/string-alphanumeric
                 cmd gen/string
                 dir (gen/not-empty gen/string-alphanumeric)]
    (let [action (dsl/sh cmd :dir dir)
          s (dsl/step name action)]
      (and (= name (:step-name s))
           (= dir (:dir s))
           (= cmd (:command s))))))

;; ---------------------------------------------------------------------------
;; parallel — marks :parallel? true
;; ---------------------------------------------------------------------------

(defspec parallel-marks-parallel 100
  (prop/for-all [n (gen/choose 1 5)]
    (let [steps (repeatedly n #(dsl/step "test" (dsl/sh "echo test")))
          p (apply dsl/parallel steps)]
      (and (true? (:parallel? p))
           (= n (count (:steps p)))
           (vector? (:steps p))))))

(defspec parallel-empty-is-parallel 10
  (prop/for-all [_ (gen/return nil)]
    (let [p (dsl/parallel)]
      (and (true? (:parallel? p))
           (empty? (:steps p))))))

;; ---------------------------------------------------------------------------
;; when-branch — adds condition to all steps
;; ---------------------------------------------------------------------------

(defspec when-branch-adds-condition-to-all-steps 100
  (prop/for-all [branch (gen/not-empty gen/string-alphanumeric)
                 n (gen/choose 1 5)]
    (let [steps (repeatedly n #(dsl/step "test" (dsl/sh "echo test")))
          result (apply dsl/when-branch branch steps)]
      (and (vector? result)
           (= n (count result))
           (every? #(= {:type :branch :value branch} (:condition %)) result)))))

;; ---------------------------------------------------------------------------
;; when-param — adds param condition to all steps
;; ---------------------------------------------------------------------------

(defspec when-param-adds-condition-to-all-steps 100
  (prop/for-all [param (gen/not-empty gen/string-alphanumeric)
                 value (gen/not-empty gen/string-alphanumeric)
                 n (gen/choose 1 5)]
    (let [steps (repeatedly n #(dsl/step "test" (dsl/sh "echo test")))
          result (apply dsl/when-param param value steps)]
      (and (vector? result)
           (= n (count result))
           (every? #(= {:type :param :param param :value value}
                       (:condition %))
                   result)))))

;; ---------------------------------------------------------------------------
;; stage — structural invariants
;; ---------------------------------------------------------------------------

(defspec stage-has-stage-name 100
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)]
    (let [s (dsl/stage name (dsl/step "s1" (dsl/sh "echo")))]
      (= name (:stage-name s)))))

(defspec stage-collects-steps 100
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)
                 n (gen/choose 1 5)]
    (let [steps (repeatedly n #(dsl/step (str "step-" (rand-int 1000)) (dsl/sh "echo")))
          s (apply dsl/stage name steps)]
      (= n (count (:steps s))))))

(defspec stage-parallel-sets-flag 50
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)]
    (let [p (dsl/parallel
              (dsl/step "a" (dsl/sh "echo a"))
              (dsl/step "b" (dsl/sh "echo b")))
          s (dsl/stage name p)]
      (true? (:parallel? s)))))

;; ---------------------------------------------------------------------------
;; build-pipeline — structural invariants
;; ---------------------------------------------------------------------------

(defspec build-pipeline-has-name-and-stages 50
  (prop/for-all [_ (gen/return nil)]
    (let [pipeline (dsl/build-pipeline 'test-pipe
                     {:description "Test"}
                     [(dsl/stage "Build"
                        (dsl/step "compile" (dsl/sh "make")))])]
      (and (= "test-pipe" (:pipeline-name pipeline))
           (= "Test" (:description pipeline))
           (= 1 (count (:stages pipeline)))
           (vector? (:stages pipeline))))))
