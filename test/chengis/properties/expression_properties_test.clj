(ns chengis.properties.expression-properties-test
  "Property-based tests for DSL expression parsing and resolution.
   Verifies parse-expression dispatch, resolve-string passthrough,
   structure preservation, idempotency, and has-expressions? detection."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.dsl.expressions :as expr]))

;; ---------------------------------------------------------------------------
;; parse-expression — namespace dispatch
;; ---------------------------------------------------------------------------

(defspec parse-expression-parameters 200
  (prop/for-all [name cgen/gen-expression-name]
    (let [result (expr/parse-expression (str "parameters." name))]
      (and (= :parameters (:type result))
           (= name (:name result))))))

(defspec parse-expression-secrets 200
  (prop/for-all [name cgen/gen-expression-name]
    (let [result (expr/parse-expression (str "secrets." name))]
      (and (= :secrets (:type result))
           (= name (:name result))))))

(defspec parse-expression-env 200
  (prop/for-all [name cgen/gen-expression-name]
    (let [result (expr/parse-expression (str "env." name))]
      (and (= :env (:type result))
           (= name (:name result))))))

(defspec parse-expression-unknown-namespace 200
  (prop/for-all [name cgen/gen-expression-name]
    (nil? (expr/parse-expression (str "unknown." name)))))

(defspec parse-expression-no-dot 200
  (prop/for-all [word (gen/not-empty gen/string-alphanumeric)]
    (nil? (expr/parse-expression word))))

;; ---------------------------------------------------------------------------
;; resolve-string — passthrough invariants
;; ---------------------------------------------------------------------------

(defspec resolve-string-no-expressions-unchanged 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; Plain strings without ${{ }} should pass through unchanged
    (= s (expr/resolve-string s {}))))

(defspec resolve-string-non-string-passthrough 200
  (prop/for-all [v (gen/one-of [(gen/return nil)
                                 gen/nat
                                 gen/keyword])]
    ;; Non-string values should be returned unchanged
    (= v (expr/resolve-string v {}))))

;; ---------------------------------------------------------------------------
;; resolve-expressions — structure preservation and idempotency
;; ---------------------------------------------------------------------------

(defspec resolve-expressions-structure-preserved 200
  (prop/for-all [ctx cgen/gen-expression-context]
    ;; Nested map/vector shape preserved — same keys, same vector lengths
    (let [data {:a "plain"
                :b ["one" "two"]
                :c {:nested "value"}}
          result (expr/resolve-expressions data ctx)]
      (and (map? result)
           (= (set (keys data)) (set (keys result)))
           (vector? (:b result))
           (= (count (:b data)) (count (:b result)))
           (map? (:c result))
           (= (set (keys (:c data))) (set (keys (:c result))))))))

(defspec resolve-expressions-idempotent 100
  (prop/for-all [ctx cgen/gen-expression-context]
    ;; When context fully resolves all expressions,
    ;; resolve(resolve(data,ctx),ctx) = resolve(data,ctx)
    (let [data {:key "plain-text" :num 42 :flag true}
          once (expr/resolve-expressions data ctx)
          twice (expr/resolve-expressions once ctx)]
      (= once twice))))

;; ---------------------------------------------------------------------------
;; has-expressions? — pattern detection
;; ---------------------------------------------------------------------------

(defspec has-expressions-detects-pattern 200
  (prop/for-all [expr-str cgen/gen-expression-string]
    ;; String with ${{ x.y }} → true
    (true? (expr/has-expressions? expr-str))))

(defspec has-expressions-plain-string-false 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; Alphanumeric string without ${{ }} → false
    (false? (expr/has-expressions? s))))

(defspec has-expressions-nil-returns-nil 10
  (prop/for-all [_ (gen/return nil)]
    (nil? (expr/has-expressions? nil))))
