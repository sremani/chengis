(ns cljest.operators-test
  (:require [clojure.test :refer [deftest testing is]]
            [cljest.operators :as ops]
            [rewrite-clj.zip :as z]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- find-first-match
  "Walk the zipper tree and return the first location where pred returns true."
  [zloc pred]
  (loop [loc zloc]
    (cond
      (z/end? loc) nil
      (pred loc)   loc
      :else        (recur (z/next loc)))))

(defn- test-operator
  "Test an operator by parsing source-str, finding the first matching node,
   and verifying the transform produces expected-str."
  [op-id source-str expected-str]
  (let [op   (ops/operator-by-id op-id)
        zloc (z/of-string source-str {:track-position? true})
        target (find-first-match zloc (:predicate op))]
    (is (some? target) (str "No match found for " op-id " in: " source-str))
    (when target
      (is (= expected-str (z/root-string ((:transform op) target)))
          (str op-id " transform failed")))))

;; ---------------------------------------------------------------------------
;; Structural tests
;; ---------------------------------------------------------------------------

(deftest all-operators-count
  (testing "all-operators has the expected number of operators"
    (is (= 56 (count ops/all-operators)))))

(deftest operator-required-keys
  (testing "every operator has the required keys"
    (doseq [op ops/all-operators]
      (is (contains? op :id)          (str "Missing :id in " (:id op)))
      (is (contains? op :category)    (str "Missing :category in " (:id op)))
      (is (contains? op :description) (str "Missing :description in " (:id op)))
      (is (contains? op :predicate)   (str "Missing :predicate in " (:id op)))
      (is (contains? op :transform)   (str "Missing :transform in " (:id op)))
      (is (keyword? (:id op))         (str ":id should be a keyword for " (:id op)))
      (is (keyword? (:category op))   (str ":category should be a keyword for " (:id op)))
      (is (string? (:description op)) (str ":description should be a string for " (:id op)))
      (is (fn? (:predicate op))       (str ":predicate should be a fn for " (:id op)))
      (is (fn? (:transform op))       (str ":transform should be a fn for " (:id op))))))

(deftest operator-ids-are-unique
  (testing "all operator IDs are unique"
    (let [ids (map :id ops/all-operators)]
      (is (= (count ids) (count (set ids)))
          "Duplicate operator IDs detected"))))

;; ---------------------------------------------------------------------------
;; Preset tests
;; ---------------------------------------------------------------------------

(deftest resolve-preset-fast
  (testing ":fast preset returns fast-ids"
    (is (= ops/fast-ids (ops/resolve-preset :fast)))))

(deftest resolve-preset-standard
  (testing ":standard preset returns all operator IDs"
    (is (= (set (map :id ops/all-operators))
           (ops/resolve-preset :standard)))))

(deftest resolve-preset-comprehensive
  (testing ":comprehensive preset returns same as standard in v0.1"
    (is (= (ops/resolve-preset :standard)
           (ops/resolve-preset :comprehensive)))))

(deftest resolve-preset-custom-set
  (testing "passing a custom set of IDs returns that set"
    (let [custom #{:arith-plus-minus :comp-eq-neq}]
      (is (= custom (ops/resolve-preset custom))))))

(deftest fast-ids-count
  (testing "fast-ids has 15 operators"
    (is (= 15 (count ops/fast-ids)))))

;; ---------------------------------------------------------------------------
;; Lookup function tests
;; ---------------------------------------------------------------------------

(deftest operators-by-ids-filters-correctly
  (testing "operators-by-ids returns only operators with matching IDs"
    (let [target-ids #{:arith-plus-minus :comp-eq-neq :logical-true-false}
          result (ops/operators-by-ids target-ids)]
      (is (= 3 (count result)))
      (is (= target-ids (set (map :id result)))))))

(deftest operators-by-ids-empty-set
  (testing "operators-by-ids with empty set returns empty"
    (is (empty? (ops/operators-by-ids #{})))))

(deftest operator-by-id-finds-existing
  (testing "operator-by-id returns the correct operator"
    (let [op (ops/operator-by-id :arith-plus-minus)]
      (is (some? op))
      (is (= :arith-plus-minus (:id op)))
      (is (= :arithmetic (:category op))))))

(deftest operator-by-id-returns-nil-for-unknown
  (testing "operator-by-id returns nil for non-existent ID"
    (is (nil? (ops/operator-by-id :nonexistent-operator)))))

(deftest operator-count-presets
  (testing "operator-count returns expected counts for each preset"
    (is (= 15 (ops/operator-count :fast)))
    (is (= 56 (ops/operator-count :standard)))
    (is (= 56 (ops/operator-count :comprehensive)))))

;; ---------------------------------------------------------------------------
;; Individual operator transform tests
;; ---------------------------------------------------------------------------

(deftest arith-plus-minus-transform
  (testing ":arith-plus-minus replaces + with -"
    (test-operator :arith-plus-minus "(+ a b)" "(- a b)")))

(deftest arith-minus-plus-transform
  (testing ":arith-minus-plus replaces - with +"
    (test-operator :arith-minus-plus "(- a b)" "(+ a b)")))

(deftest comp-eq-neq-transform
  (testing ":comp-eq-neq replaces = with not="
    (test-operator :comp-eq-neq "(= x y)" "(not= x y)")))

(deftest logical-true-false-transform
  (testing ":logical-true-false replaces true with false"
    (test-operator :logical-true-false "true" "false")))

(deftest logical-and-or-transform
  (testing ":logical-and-or replaces and with or"
    (test-operator :logical-and-or "(and x y)" "(or x y)")))

(deftest coll-first-last-transform
  (testing ":coll-first-last replaces first with last"
    (test-operator :coll-first-last "(first xs)" "(last xs)")))

(deftest const-zero-one-transform
  (testing ":const-zero-one replaces 0 with 1"
    (test-operator :const-zero-one "(f 0)" "(f 1)")))

(deftest const-empty-str-mutant-transform
  (testing ":const-empty-str-mutant replaces empty string with \"mutant\""
    (test-operator :const-empty-str-mutant "\"\"" "\"mutant\"")))

(deftest thread-arrow-darrow-transform
  (testing ":thread-arrow-darrow replaces -> with ->>"
    (test-operator :thread-arrow-darrow "(-> x f)" "(->> x f)")))

(deftest clj-defn--defn-transform
  (testing ":clj-defn--defn replaces defn- with defn"
    (test-operator :clj-defn--defn "(defn- foo [] 1)" "(defn foo [] 1)")))

;; ---------------------------------------------------------------------------
;; Additional operator transforms
;; ---------------------------------------------------------------------------

(deftest logical-false-true-transform
  (testing ":logical-false-true replaces false with true"
    (test-operator :logical-false-true "false" "true")))

(deftest comp-gt-lt-transform
  (testing ":comp-gt-lt replaces > with <"
    (test-operator :comp-gt-lt "(> x y)" "(< x y)")))

(deftest arith-inc-dec-transform
  (testing ":arith-inc-dec replaces inc with dec"
    (test-operator :arith-inc-dec "(inc x)" "(dec x)")))

(deftest coll-conj-disj-transform
  (testing ":coll-conj-disj replaces conj with disj"
    (test-operator :coll-conj-disj "(conj coll x)" "(disj coll x)")))

(deftest nil-nilq-someq-transform
  (testing ":nil-nilq-someq replaces nil? with some?"
    (test-operator :nil-nilq-someq "(nil? x)" "(some? x)")))

(deftest const-one-zero-transform
  (testing ":const-one-zero replaces 1 with 0"
    (test-operator :const-one-zero "(f 1)" "(f 0)")))

(deftest logical-negate-if-transform
  (testing ":logical-negate-if negates the if condition"
    (let [op     (ops/operator-by-id :logical-negate-if)
          zloc   (z/of-string "(if cond a b)" {:track-position? true})
          target (find-first-match zloc (:predicate op))]
      (is (some? target))
      (when target
        (let [result (z/root-string ((:transform op) target))]
          (is (clojure.string/includes? result "(not cond)"))
          (is (clojure.string/includes? result "a"))
          (is (clojure.string/includes? result "b")))))))

(deftest predicate-does-not-match-wrong-node
  (testing "operator predicates do not match incorrect nodes"
    (let [op   (ops/operator-by-id :arith-plus-minus)
          zloc (z/of-string "(- a b)" {:track-position? true})]
      ;; Walk all nodes — none should match :arith-plus-minus
      (is (nil? (find-first-match zloc (:predicate op)))))))
