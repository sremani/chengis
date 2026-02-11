(ns chengis.properties.config-properties-test
  "Property-based tests for configuration utilities.
   Verifies deep-merge associativity and identity, coerce-env-value type rules,
   resolve-path absolute path handling, and validate-name! boundary behavior."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.config :as config]
            [chengis.engine.iac]))

;; Access private validate-name! via var reference
(def validate-name! #'chengis.engine.iac/validate-name!)

;; ---------------------------------------------------------------------------
;; deep-merge — identity with empty map
;; ---------------------------------------------------------------------------

(defspec deep-merge-identity-with-empty 100
  (prop/for-all [m cgen/gen-nested-map]
    (= m (config/deep-merge m {}))))

(defspec deep-merge-empty-left-identity 100
  (prop/for-all [m cgen/gen-nested-map]
    (= m (config/deep-merge {} m))))

;; ---------------------------------------------------------------------------
;; deep-merge — self-merge is identity
;; ---------------------------------------------------------------------------

(defspec deep-merge-self-is-identity 100
  (prop/for-all [m cgen/gen-nested-map]
    (= m (config/deep-merge m m))))

;; ---------------------------------------------------------------------------
;; deep-merge — nil handling
;; ---------------------------------------------------------------------------

(defspec deep-merge-nil-ignored 100
  (prop/for-all [m cgen/gen-nested-map]
    (and (= m (config/deep-merge m nil))
         (= m (config/deep-merge nil m)))))

;; ---------------------------------------------------------------------------
;; deep-merge — associativity
;; ---------------------------------------------------------------------------

(defspec deep-merge-associative 100
  (prop/for-all [a cgen/gen-nested-map
                 b cgen/gen-nested-map
                 c cgen/gen-nested-map]
    (= (config/deep-merge (config/deep-merge a b) c)
       (config/deep-merge a (config/deep-merge b c)))))

;; ---------------------------------------------------------------------------
;; deep-merge — last value wins for non-map values
;; ---------------------------------------------------------------------------

(defspec deep-merge-right-wins 100
  (prop/for-all [k gen/keyword
                 v1 gen/string-alphanumeric
                 v2 gen/string-alphanumeric]
    (= v2 (get (config/deep-merge {k v1} {k v2}) k))))

;; ---------------------------------------------------------------------------
;; coerce-env-value — boolean strings
;; ---------------------------------------------------------------------------

(deftest coerce-env-value-booleans
  (testing "\"true\" and \"false\" coerce to booleans"
    (is (true? (config/coerce-env-value "true")))
    (is (false? (config/coerce-env-value "false")))))

;; ---------------------------------------------------------------------------
;; coerce-env-value — numeric strings
;; ---------------------------------------------------------------------------

(defspec coerce-env-value-numeric-strings 100
  (prop/for-all [n gen/nat]
    (let [result (config/coerce-env-value (str n))]
      (and (integer? result)
           (= n result)))))

;; ---------------------------------------------------------------------------
;; coerce-env-value — keyword strings
;; ---------------------------------------------------------------------------

(defspec coerce-env-value-keyword-strings 100
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    (let [result (config/coerce-env-value (str ":" s))]
      (keyword? result))))

;; ---------------------------------------------------------------------------
;; coerce-env-value — plain strings pass through
;; ---------------------------------------------------------------------------

(defspec coerce-env-value-plain-strings-passthrough 100
  (prop/for-all [s (gen/such-that (fn [s]
                                    (and (not= s "true")
                                         (not= s "false")
                                         (not (re-matches #"\d+" s))
                                         (not (.startsWith s ":"))))
                                  gen/string-alphanumeric
                                  100)]
    (= s (config/coerce-env-value s))))

;; ---------------------------------------------------------------------------
;; resolve-path — absolute paths ignore base
;; ---------------------------------------------------------------------------

(defspec resolve-path-absolute-ignores-base 50
  (prop/for-all [base gen/string-alphanumeric]
    (let [abs-path "/tmp/test/absolute"]
      (= abs-path (config/resolve-path base abs-path)))))

;; ---------------------------------------------------------------------------
;; resolve-path — always produces absolute path
;; ---------------------------------------------------------------------------

(defspec resolve-path-always-absolute 50
  (prop/for-all [rel (gen/not-empty gen/string-alphanumeric)]
    (.isAbsolute (java.io.File. (config/resolve-path "/base" rel)))))

;; ---------------------------------------------------------------------------
;; sqlite? / postgresql? — complementary
;; ---------------------------------------------------------------------------

(defspec sqlite-postgresql-complementary 50
  (prop/for-all [db-type (gen/elements ["sqlite" "postgresql"])]
    (let [cfg {:database {:type db-type}}]
      ;; Exactly one of sqlite? or postgresql? should be true
      (not= (config/sqlite? cfg) (config/postgresql? cfg)))))

;; ---------------------------------------------------------------------------
;; validate-name! — valid names pass
;; ---------------------------------------------------------------------------

(defspec validate-name-accepts-valid-names 200
  (prop/for-all [name cgen/gen-valid-name]
    ;; validate-name! returns nil (no exception) for valid names
    (nil? (validate-name! name "test-label"))))

;; ---------------------------------------------------------------------------
;; validate-name! — invalid names throw
;; ---------------------------------------------------------------------------

(defspec validate-name-rejects-invalid-names 200
  (prop/for-all [name cgen/gen-invalid-name]
    (try
      (validate-name! name "test-label")
      ;; If we get here without exception, it should be because the name
      ;; is nil (validate-name! allows nil to pass through)
      (nil? name)
      (catch Exception _
        true))))

;; ---------------------------------------------------------------------------
;; validate-name! — nil passes (no validation)
;; ---------------------------------------------------------------------------

(deftest validate-name-nil-passes
  (testing "nil is allowed by validate-name! (no validation needed)"
    (is (nil? (validate-name! nil "test-label")))))
