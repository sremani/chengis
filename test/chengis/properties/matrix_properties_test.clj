(ns chengis.properties.matrix-properties-test
  "Property-based tests for matrix build expansion.
   Verifies cartesian product count invariant, exclusion filtering,
   environment variable formatting, and label generation."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.matrix :as matrix]))

;; Access private functions via var references
(def cartesian-product #'chengis.engine.matrix/cartesian-product)
(def matches-exclude? #'chengis.engine.matrix/matches-exclude?)

;; ---------------------------------------------------------------------------
;; cartesian-product — combinatorial count
;; ---------------------------------------------------------------------------

(defspec cartesian-product-count-is-product-of-sizes 200
  (prop/for-all [dims cgen/gen-matrix-dimensions]
    (let [combos (cartesian-product dims)
          expected-count (reduce * 1 (map count (vals dims)))]
      (= expected-count (count combos)))))

(defspec cartesian-product-all-keys-present 200
  (prop/for-all [dims cgen/gen-matrix-dimensions]
    (let [combos (cartesian-product dims)]
      (every? (fn [combo]
                (= (set (keys dims)) (set (keys combo))))
              combos))))

(defspec cartesian-product-all-values-are-strings 200
  (prop/for-all [dims cgen/gen-matrix-dimensions]
    (let [combos (cartesian-product dims)]
      (every? (fn [combo]
                (every? string? (vals combo)))
              combos))))

(defspec cartesian-product-empty-input 10
  (prop/for-all [_ (gen/return nil)]
    (= [{}] (cartesian-product {}))))

(defspec cartesian-product-single-dimension 100
  (prop/for-all [values cgen/gen-dimension-values]
    (let [combos (cartesian-product {:os values})]
      (= (count values) (count combos)))))

;; ---------------------------------------------------------------------------
;; matches-exclude? — rule matching
;; ---------------------------------------------------------------------------

(defspec matches-exclude-empty-rule-matches-all 100
  (prop/for-all [combo cgen/gen-combination]
    ;; Empty exclude rule matches everything
    (true? (matches-exclude? combo {}))))

(defspec matches-exclude-full-match 100
  (prop/for-all [combo cgen/gen-combination]
    ;; A rule that exactly matches the combination should match
    (true? (matches-exclude? combo combo))))

(defspec matches-exclude-superset-rule-does-not-match 100
  (prop/for-all [combo cgen/gen-combination]
    ;; A rule with an extra key not in combo should NOT match
    (let [rule (assoc combo :nonexistent-dim "impossible-value")]
      (false? (matches-exclude? combo rule)))))

;; ---------------------------------------------------------------------------
;; expand-matrix — filtering and limits
;; ---------------------------------------------------------------------------

(defspec expand-matrix-respects-exclude 100
  (prop/for-all [dims cgen/gen-matrix-dimensions]
    (if (and (pos? (count dims))
             (every? seq (vals dims)))
      ;; Exclude the first combination
      (let [all-combos (cartesian-product dims)
            exclude-rule (first all-combos)
            result (matrix/expand-matrix (assoc dims :exclude [exclude-rule])
                     :max 1000)]
        ;; The excluded combination should not appear
        (not-any? #(= exclude-rule %) result))
      true)))

(defspec expand-matrix-without-exclude-equals-cartesian 100
  (prop/for-all [dims cgen/gen-matrix-dimensions]
    (let [all-combos (vec (cartesian-product dims))
          result (matrix/expand-matrix dims :max 1000)]
      (= (count all-combos) (count result)))))

;; ---------------------------------------------------------------------------
;; matrix-label — formatting
;; ---------------------------------------------------------------------------

(defspec matrix-label-is-string 200
  (prop/for-all [combo cgen/gen-combination]
    (string? (matrix/matrix-label combo))))

(defspec matrix-label-contains-all-keys 200
  (prop/for-all [combo cgen/gen-combination]
    (let [label (matrix/matrix-label combo)]
      (every? (fn [k]
                (str/includes? label (name k)))
              (keys combo)))))

(defspec matrix-label-is-sorted 200
  (prop/for-all [combo cgen/gen-combination]
    (let [label (matrix/matrix-label combo)
          parts (str/split label #", ")
          ;; Extract key names from "key=value" parts
          key-names (map #(first (str/split % #"=")) parts)]
      (= key-names (sort key-names)))))

;; ---------------------------------------------------------------------------
;; matrix-env — environment variable formatting
;; ---------------------------------------------------------------------------

(defspec matrix-env-keys-start-with-matrix 200
  (prop/for-all [combo cgen/gen-combination]
    (let [env (matrix/matrix-env combo)]
      (every? #(str/starts-with? % "MATRIX_") (keys env)))))

(defspec matrix-env-keys-are-uppercase 200
  (prop/for-all [combo cgen/gen-combination]
    (let [env (matrix/matrix-env combo)]
      (every? #(= % (str/upper-case %)) (keys env)))))

(defspec matrix-env-values-are-strings 200
  (prop/for-all [combo cgen/gen-combination]
    (let [env (matrix/matrix-env combo)]
      (every? string? (vals env)))))

(defspec matrix-env-count-matches-dims 200
  (prop/for-all [combo cgen/gen-combination]
    (let [env (matrix/matrix-env combo)]
      (= (count combo) (count env)))))
