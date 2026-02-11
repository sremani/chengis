(ns chengis.properties.branch-override-properties-test
  "Property-based tests for branch-based pipeline overrides.
   Covers glob->regex (private), branch-matches?, find-matching-override,
   apply-override, parse-overrides, and validate-overrides."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.branch-overrides :as bo]))

(def ^:private glob->regex #'chengis.engine.branch-overrides/glob->regex)

;; ---------------------------------------------------------------------------
;; glob->regex — dots are escaped
;; ---------------------------------------------------------------------------

(defspec glob-regex-escapes-literal-dots 100
  (prop/for-all [prefix (gen/not-empty gen/string-alphanumeric)]
    (let [result (glob->regex (str prefix ".txt"))]
      (str/includes? result "\\."))))

;; ---------------------------------------------------------------------------
;; branch-matches? — nil inputs return nil
;; ---------------------------------------------------------------------------

(defspec branch-matches-nil-pattern-returns-nil 50
  (prop/for-all [branch cgen/gen-branch-name]
    (nil? (bo/branch-matches? nil branch))))

(defspec branch-matches-nil-branch-returns-nil 50
  (prop/for-all [pattern (gen/not-empty gen/string-alphanumeric)]
    (nil? (bo/branch-matches? pattern nil))))

;; ---------------------------------------------------------------------------
;; branch-matches? — exact match
;; ---------------------------------------------------------------------------

(defspec branch-matches-exact-self-match 100
  (prop/for-all [branch cgen/gen-branch-name]
    (true? (bo/branch-matches? branch branch))))

;; ---------------------------------------------------------------------------
;; branch-matches? — glob with * matches prefix
;; ---------------------------------------------------------------------------

(defspec branch-matches-glob-star 100
  (prop/for-all [segment cgen/gen-path-segment]
    (true? (bo/branch-matches? (str "feature/*") (str "feature/" segment)))))

;; ---------------------------------------------------------------------------
;; branch-matches? — regex with ~ prefix
;; ---------------------------------------------------------------------------

(defspec branch-matches-regex-prefix 50
  (prop/for-all [_ (gen/return nil)]
    (and (true? (bo/branch-matches? "~release/v\\d+" "release/v123"))
         (false? (bo/branch-matches? "~release/v\\d+" "release/abc")))))

;; ---------------------------------------------------------------------------
;; find-matching-override — nil/empty returns nil
;; ---------------------------------------------------------------------------

(defspec find-matching-override-empty-returns-nil 50
  (prop/for-all [branch cgen/gen-branch-name]
    (and (nil? (bo/find-matching-override nil branch))
         (nil? (bo/find-matching-override [] branch)))))

;; ---------------------------------------------------------------------------
;; find-matching-override — returns first match
;; ---------------------------------------------------------------------------

(defspec find-matching-override-returns-first 100
  (prop/for-all [branch cgen/gen-branch-name]
    (let [overrides [{:pattern "*" :parameters {:env "staging"}}
                     {:pattern "*" :parameters {:env "production"}}]
          result (bo/find-matching-override overrides branch)]
      (if (nil? result)
        true
        (= {:env "staging"} (:parameters result))))))

;; ---------------------------------------------------------------------------
;; apply-override — nil override passes through
;; ---------------------------------------------------------------------------

(defspec apply-override-nil-passthrough 100
  (prop/for-all [pipeline cgen/gen-pipeline-def]
    (= pipeline (bo/apply-override pipeline nil))))

;; ---------------------------------------------------------------------------
;; apply-override — skip-stages removes named stages
;; ---------------------------------------------------------------------------

(defspec apply-override-skip-stages-removes 100
  (prop/for-all [pipeline cgen/gen-pipeline-def]
    (let [stage-names (mapv :stage-name (:stages pipeline))]
      (if (empty? stage-names)
        true
        (let [skip-name (first stage-names)
              override {:pattern "*" :skip-stages [skip-name]}
              result (bo/apply-override pipeline override)]
          (not-any? #(= skip-name (:stage-name %)) (:stages result)))))))

;; ---------------------------------------------------------------------------
;; apply-override — parameters are merged
;; ---------------------------------------------------------------------------

(defspec apply-override-merges-parameters 100
  (prop/for-all [pipeline cgen/gen-pipeline-def
                 k gen/keyword
                 v (gen/not-empty gen/string-alphanumeric)]
    (let [override {:pattern "*" :parameters {k v}}
          result (bo/apply-override pipeline override)]
      (= v (get (:parameters result) k)))))

;; ---------------------------------------------------------------------------
;; parse-overrides — blank string returns empty vec
;; ---------------------------------------------------------------------------

(defspec parse-overrides-blank-returns-empty 50
  (prop/for-all [_ (gen/return nil)]
    (and (= [] (bo/parse-overrides ""))
         (= [] (bo/parse-overrides nil))
         (= [] (bo/parse-overrides "   ")))))

;; ---------------------------------------------------------------------------
;; parse-overrides — invalid EDN returns empty vec
;; ---------------------------------------------------------------------------

(defspec parse-overrides-invalid-edn-returns-empty 50
  (prop/for-all [_ (gen/return nil)]
    (= [] (bo/parse-overrides "{{{invalid"))))

;; ---------------------------------------------------------------------------
;; validate-overrides — valid overrides pass validation
;; ---------------------------------------------------------------------------

(defspec validate-overrides-valid-passes 100
  (prop/for-all [pattern cgen/gen-branch-name]
    (let [overrides [{:pattern pattern :skip-stages ["Deploy"]
                      :stages [{:stage-name "Extra"}]
                      :parameters {:env "test"}}]
          result (bo/validate-overrides overrides)]
      (true? (:valid? result)))))

;; ---------------------------------------------------------------------------
;; validate-overrides — missing :pattern fails
;; ---------------------------------------------------------------------------

(defspec validate-overrides-missing-pattern-fails 50
  (prop/for-all [_ (gen/return nil)]
    (let [result (bo/validate-overrides [{:skip-stages ["Deploy"]}])]
      (and (false? (:valid? result))
           (pos? (count (:errors result)))))))
