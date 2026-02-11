(ns cljest.operators
  "Mutation operators for cljest.
   Each operator is a data map with:
     :id          — unique keyword identifier
     :category    — category keyword
     :description — human-readable description
     :predicate   — (fn [zloc] boolean) — does this zipper location match?
     :transform   — (fn [zloc] zloc') — produce the mutated zipper location

   Operators are organized into 8 categories with ~56 total operators.
   Three presets control which operators run: :fast, :standard, :comprehensive."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- sexpr-is?
  "Check if the zipper location's s-expression equals the given value.
   Returns false for non-sexpr-able nodes (whitespace, comments)."
  [zloc sym]
  (and (z/sexpr-able? zloc)
       (= (z/sexpr zloc) sym)))

(defn- in-list-head?
  "Check if the zipper location is the first element of a list form.
   This helps avoid mutating symbols that appear in non-call positions."
  [zloc]
  (when-let [up (z/up zloc)]
    (and (= :list (z/tag up))
         (= zloc (z/down up)))))

(defn- swap-symbol
  "Create an operator that swaps one symbol for another."
  [id category desc from to]
  {:id id
   :category category
   :description desc
   :predicate (fn [zloc] (and (in-list-head? zloc) (sexpr-is? zloc from)))
   :transform (fn [zloc] (z/replace zloc (n/token-node to)))})

(defn- swap-symbol-any-position
  "Create an operator that swaps a symbol regardless of position (e.g., true/false)."
  [id category desc from to]
  {:id id
   :category category
   :description desc
   :predicate (fn [zloc] (sexpr-is? zloc from))
   :transform (fn [zloc] (z/replace zloc (n/token-node to)))})

;; ---------------------------------------------------------------------------
;; Category 1: Arithmetic (8 operators)
;; ---------------------------------------------------------------------------

(def arithmetic-operators
  [(swap-symbol :arith-plus-minus :arithmetic "Replace + with -" '+ '-)
   (swap-symbol :arith-minus-plus :arithmetic "Replace - with +" '- '+)
   (swap-symbol :arith-mult-div :arithmetic "Replace * with /" '* '/)
   (swap-symbol :arith-div-mult :arithmetic "Replace / with *" '/ '*)
   (swap-symbol :arith-inc-dec :arithmetic "Replace inc with dec" 'inc 'dec)
   (swap-symbol :arith-dec-inc :arithmetic "Replace dec with inc" 'dec 'inc)
   (swap-symbol :arith-mod-identity :arithmetic "Replace mod with first arg (identity)"
                'mod 'identity)
   (swap-symbol :arith-max-min :arithmetic "Replace max with min" 'max 'min)])

;; ---------------------------------------------------------------------------
;; Category 2: Comparison (12 operators)
;; ---------------------------------------------------------------------------

(def comparison-operators
  [(swap-symbol :comp-lt-gt :comparison "Replace < with >" '< '>)
   (swap-symbol :comp-gt-lt :comparison "Replace > with <" '> '<)
   (swap-symbol :comp-lte-gte :comparison "Replace <= with >=" '<= '>=)
   (swap-symbol :comp-gte-lte :comparison "Replace >= with <=" '>= '<=)
   (swap-symbol :comp-lt-lte :comparison "Replace < with <= (boundary)" '< '<=)
   (swap-symbol :comp-lte-lt :comparison "Replace <= with < (boundary)" '<= '<)
   (swap-symbol :comp-gt-gte :comparison "Replace > with >= (boundary)" '> '>=)
   (swap-symbol :comp-gte-gt :comparison "Replace >= with > (boundary)" '>= '>)
   (swap-symbol :comp-eq-neq :comparison "Replace = with not=" '= 'not=)
   (swap-symbol :comp-neq-eq :comparison "Replace not= with =" 'not= '=)
   (swap-symbol :comp-zero-pos :comparison "Replace zero? with pos?" 'zero? 'pos?)
   (swap-symbol :comp-zero-neg :comparison "Replace zero? with neg?" 'zero? 'neg?)])

;; ---------------------------------------------------------------------------
;; Category 3: Logical (6 operators)
;; ---------------------------------------------------------------------------

(def logical-operators
  [(swap-symbol :logical-and-or :logical "Replace and with or" 'and 'or)
   (swap-symbol :logical-or-and :logical "Replace or with and" 'or 'and)
   (swap-symbol-any-position :logical-true-false :logical "Replace true with false" true false)
   (swap-symbol-any-position :logical-false-true :logical "Replace false with true" false true)
   ;; Negate if condition: (if cond a b) → (if (not cond) a b)
   {:id :logical-negate-if
    :category :logical
    :description "Negate if condition"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'if)
                      (some? (z/right zloc))))
    :transform (fn [zloc]
                 (let [cond-zloc (z/right zloc)
                       cond-str (z/string cond-zloc)
                       negated (str "(not " cond-str ")")]
                   (z/replace cond-zloc (p/parse-string negated))))}
   ;; when-not ↔ when
   (swap-symbol :logical-when-not-when :logical "Replace when-not with when" 'when-not 'when)])

;; ---------------------------------------------------------------------------
;; Category 4: Collections (8 operators)
;; ---------------------------------------------------------------------------

(def collection-operators
  [(swap-symbol :coll-first-last :collections "Replace first with last" 'first 'last)
   (swap-symbol :coll-last-first :collections "Replace last with first" 'last 'first)
   (swap-symbol :coll-conj-disj :collections "Replace conj with disj" 'conj 'disj)
   (swap-symbol :coll-assoc-dissoc :collections "Replace assoc with dissoc" 'assoc 'dissoc)
   (swap-symbol :coll-take-drop :collections "Replace take with drop" 'take 'drop)
   (swap-symbol :coll-drop-take :collections "Replace drop with take" 'drop 'take)
   (swap-symbol :coll-filter-remove :collections "Replace filter with remove" 'filter 'remove)
   (swap-symbol :coll-map-mapv :collections "Replace map with mapv" 'map 'mapv)])

;; ---------------------------------------------------------------------------
;; Category 5: Nil/Control (6 operators)
;; ---------------------------------------------------------------------------

(def nil-control-operators
  [(swap-symbol :nil-nilq-someq :nil-control "Replace nil? with some?" 'nil? 'some?)
   (swap-symbol :nil-someq-nilq :nil-control "Replace some? with nil?" 'some? 'nil?)
   (swap-symbol :nil-if-not-if :nil-control "Replace if-not with if" 'if-not 'if)
   (swap-symbol :nil-if-if-not :nil-control "Replace if with if-not" 'if 'if-not)
   (swap-symbol :nil-when-when-not :nil-control "Replace when with when-not" 'when 'when-not)
   ;; Remove last arg of or (default value removal)
   {:id :nil-or-remove-default
    :category :nil-control
    :description "Remove last arg of or (default value)"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'or)
                      ;; Must have at least 2 args after 'or
                      (some? (z/right zloc))
                      (some? (z/right (z/right zloc)))))
    :transform (fn [zloc]
                 ;; Navigate to the last child and remove it
                 (let [up (z/up zloc)]
                   (-> up z/down z/rightmost z/remove z/up)))}])

;; ---------------------------------------------------------------------------
;; Category 6: Constants (6 operators)
;; ---------------------------------------------------------------------------

(def constant-operators
  [{:id :const-zero-one
    :category :constants
    :description "Replace 0 with 1"
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (= (z/sexpr zloc) 0)
                      (not (in-list-head? zloc))))
    :transform (fn [zloc] (z/replace zloc (n/token-node 1)))}
   {:id :const-one-zero
    :category :constants
    :description "Replace 1 with 0"
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (= (z/sexpr zloc) 1)
                      (not (in-list-head? zloc))))
    :transform (fn [zloc] (z/replace zloc (n/token-node 0)))}
   {:id :const-empty-str-mutant
    :category :constants
    :description "Replace empty string with \"mutant\""
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (= (z/sexpr zloc) "")))
    :transform (fn [zloc] (z/replace zloc (n/string-node "mutant")))}
   {:id :const-nonempty-str-empty
    :category :constants
    :description "Replace non-empty string literal with empty string"
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (string? (z/sexpr zloc))
                      (not (empty? (z/sexpr zloc)))))
    :transform (fn [zloc] (z/replace zloc (n/string-node "")))}
   {:id :const-empty-vec-nil
    :category :constants
    :description "Replace [] with [nil]"
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (= (z/sexpr zloc) [])
                      (= :vector (z/tag zloc))))
    :transform (fn [zloc] (z/replace zloc (n/coerce [nil])))}
   {:id :const-empty-map-mutant
    :category :constants
    :description "Replace {} with {:mutant true}"
    :predicate (fn [zloc]
                 (and (z/sexpr-able? zloc)
                      (= (z/sexpr zloc) {})
                      (= :map (z/tag zloc))))
    :transform (fn [zloc] (z/replace zloc (n/coerce {:mutant true})))}])

;; ---------------------------------------------------------------------------
;; Category 7: Threading (4 operators)
;; ---------------------------------------------------------------------------

(def threading-operators
  [(swap-symbol :thread-arrow-darrow :threading "Replace -> with ->>" '-> '->>)
   (swap-symbol :thread-darrow-arrow :threading "Replace ->> with ->" '->> '->)
   (swap-symbol :thread-some-some-d :threading "Replace some-> with some->>" 'some-> 'some->>)
   (swap-symbol :thread-some-d-some :threading "Replace some->> with some->" 'some->> 'some->)])

;; ---------------------------------------------------------------------------
;; Category 8: Clojure-Specific (6 operators)
;; ---------------------------------------------------------------------------

(def clojure-specific-operators
  [(swap-symbol :clj-defn--defn :clojure-specific "Replace defn- with defn (make public)"
                'defn- 'defn)
   {:id :clj-merge-swap
    :category :clojure-specific
    :description "Swap first two merge arguments"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'merge)
                      (some? (z/right zloc))
                      (some? (z/right (z/right zloc)))))
    :transform (fn [zloc]
                 (let [arg1 (z/right zloc)
                       arg2 (z/right arg1)
                       n1 (z/node arg1)
                       n2 (z/node arg2)]
                   (-> arg1
                       (z/replace n2)
                       z/right
                       (z/replace n1)
                       z/up)))}
   {:id :clj-str-remove-arg
    :category :clojure-specific
    :description "Remove an argument from str call"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'str)
                      ;; Must have at least 2 args
                      (some? (z/right zloc))
                      (some? (z/right (z/right zloc)))))
    :transform (fn [zloc]
                 ;; Remove the first argument after 'str
                 (let [first-arg (z/right zloc)]
                   (z/up (z/remove first-arg))))}
   {:id :clj-do-remove-form
    :category :clojure-specific
    :description "Remove a form from do block"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'do)
                      ;; Must have at least 2 body forms
                      (some? (z/right zloc))
                      (some? (z/right (z/right zloc)))))
    :transform (fn [zloc]
                 ;; Remove the first form in the do body
                 (let [first-form (z/right zloc)]
                   (z/up (z/remove first-form))))}
   {:id :clj-let-swap-bindings
    :category :clojure-specific
    :description "Swap first two let bindings"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'let)
                      ;; Check binding vector has at least 4 elements (2 pairs)
                      (let [bind-vec (z/right zloc)]
                        (and (some? bind-vec)
                             (z/sexpr-able? bind-vec)
                             (vector? (z/sexpr bind-vec))
                             (>= (count (z/sexpr bind-vec)) 4)))))
    :transform (fn [zloc]
                 ;; Swap first two binding pairs: [a 1 b 2 ...] → [b 2 a 1 ...]
                 (let [bind-vec (z/right zloc)
                       bindings (z/sexpr bind-vec)
                       [n1 v1 n2 v2 & rest-bindings] bindings
                       swapped (vec (concat [n2 v2 n1 v1] rest-bindings))]
                   (z/up (z/replace bind-vec (n/coerce swapped)))))}
   {:id :clj-recur-swap-args
    :category :clojure-specific
    :description "Swap first two recur arguments"
    :predicate (fn [zloc]
                 (and (in-list-head? zloc)
                      (sexpr-is? zloc 'recur)
                      ;; Must have at least 2 args
                      (some? (z/right zloc))
                      (some? (z/right (z/right zloc)))))
    :transform (fn [zloc]
                 ;; Swap first two arguments of recur
                 (let [arg1 (z/right zloc)
                       arg2 (z/right arg1)
                       n1 (z/node arg1)
                       n2 (z/node arg2)
                       ;; Replace arg1 with arg2's content, arg2 with arg1's content
                       zloc' (-> arg1
                                 (z/replace n2)
                                 z/right
                                 (z/replace n1))]
                   (z/up zloc')))}])

;; ---------------------------------------------------------------------------
;; All operators
;; ---------------------------------------------------------------------------

(def all-operators
  "All mutation operators, across all categories."
  (vec (concat arithmetic-operators
               comparison-operators
               logical-operators
               collection-operators
               nil-control-operators
               constant-operators
               threading-operators
               clojure-specific-operators)))

;; ---------------------------------------------------------------------------
;; Presets
;; ---------------------------------------------------------------------------

(def fast-ids
  "15 highest-yield operators for quick CI feedback.
   Covers arithmetic swaps, core comparisons, and boolean flips."
  #{:arith-plus-minus :arith-minus-plus
    :comp-lt-gt :comp-gt-lt
    :comp-eq-neq :comp-neq-eq
    :comp-lt-lte :comp-gt-gte
    :logical-and-or :logical-or-and
    :logical-true-false :logical-false-true
    :nil-nilq-someq :nil-someq-nilq
    :coll-first-last})

(def standard-ids
  "All operators — the default preset."
  (set (map :id all-operators)))

(def comprehensive-ids
  "Same as standard for v0.1. Future: adds constant boundary variations."
  standard-ids)

;; ---------------------------------------------------------------------------
;; Lookup functions
;; ---------------------------------------------------------------------------

(defn resolve-preset
  "Convert a preset keyword to a set of operator IDs."
  [preset]
  (case preset
    :fast fast-ids
    :standard standard-ids
    :comprehensive comprehensive-ids
    ;; If it's already a set of IDs, use directly
    (if (set? preset) preset standard-ids)))

(defn operators-by-ids
  "Filter operators to only those whose :id is in the given set."
  [id-set]
  (filterv #(contains? id-set (:id %)) all-operators))

(defn operator-by-id
  "Look up a single operator by ID."
  [id]
  (first (filter #(= id (:id %)) all-operators)))

(defn operator-count
  "Return the total number of operators in a preset."
  [preset]
  (count (resolve-preset preset)))
