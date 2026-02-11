(ns chengis.properties.policy-properties-test
  "Property-based tests for policy evaluation engine.
   Verifies glob matching, branch/author/parameter restriction evaluation,
   and approval override merging."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [chengis.generators :as cgen]
            [chengis.engine.policy :as policy]))

;; Access private functions via var references
(def glob-matches? #'chengis.engine.policy/glob-matches?)
(def any-glob-matches? #'chengis.engine.policy/any-glob-matches?)
(def eval-branch-restriction #'chengis.engine.policy/eval-branch-restriction)
(def eval-author-restriction #'chengis.engine.policy/eval-author-restriction)
(def eval-parameter-restriction #'chengis.engine.policy/eval-parameter-restriction)

;; ---------------------------------------------------------------------------
;; glob-matches? — basic matching properties
;; ---------------------------------------------------------------------------

(defspec glob-matches-reflexive 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; An alphanumeric string (no regex metacharacters) should match itself
    (true? (glob-matches? s s))))

(defspec glob-matches-nil-returns-nil 100
  (prop/for-all [s gen/string-alphanumeric]
    ;; nil pattern or nil value → nil (not false)
    (and (nil? (glob-matches? nil s))
         (nil? (glob-matches? s nil))
         (nil? (glob-matches? nil nil)))))

(defspec glob-matches-star-matches-any 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; "*" should match any non-nil string
    (true? (glob-matches? "*" s))))

(defspec glob-matches-question-mark-single-char 200
  (prop/for-all [c (gen/fmap str gen/char-alphanumeric)]
    ;; "?" should match any single character
    (true? (glob-matches? "?" c))))

;; ---------------------------------------------------------------------------
;; any-glob-matches? — superset of single match
;; ---------------------------------------------------------------------------

(defspec any-glob-matches-superset-of-single 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; If glob-matches? returns truthy for (s, s), then any-glob-matches?
    ;; with [s] and s should also be truthy
    (if (glob-matches? s s)
      (boolean (any-glob-matches? [s] s))
      true)))

(defspec any-glob-matches-empty-patterns-nil 200
  (prop/for-all [s (gen/not-empty gen/string-alphanumeric)]
    ;; Empty pattern vector → nil (some returns nil on empty seq)
    (nil? (any-glob-matches? [] s))))

;; ---------------------------------------------------------------------------
;; eval-branch-restriction — branch policy evaluation
;; ---------------------------------------------------------------------------

(defspec eval-branch-nil-branch-allows 100
  (prop/for-all [branch-pattern (gen/not-empty gen/string-alphanumeric)]
    ;; nil git-branch → :allow regardless of rule
    (let [result (eval-branch-restriction
                   {:branches [branch-pattern] :action "deny"}
                   {:git-branch nil})]
      (= :allow (:result result)))))

(defspec eval-branch-deny-matching-denies 100
  (prop/for-all [_ (gen/return nil)]
    ;; Deny rule with matching branch → :deny
    (let [result (eval-branch-restriction
                   {:branches ["main"] :action "deny"}
                   {:git-branch "main"})]
      (= :deny (:result result)))))

(defspec eval-branch-allow-non-matching-denies 100
  (prop/for-all [_ (gen/return nil)]
    ;; Allow rule with non-matching branch → :deny
    (let [result (eval-branch-restriction
                   {:branches ["main"] :action "allow"}
                   {:git-branch "other"})]
      (= :deny (:result result)))))

;; ---------------------------------------------------------------------------
;; eval-author-restriction — author policy evaluation
;; ---------------------------------------------------------------------------

(defspec eval-author-nil-author-allows 100
  (prop/for-all [author-pattern (gen/not-empty gen/string-alphanumeric)]
    ;; nil git-author → :allow regardless of rule
    (let [result (eval-author-restriction
                   {:authors [author-pattern] :action "deny"}
                   {:git-author nil})]
      (= :allow (:result result)))))

;; ---------------------------------------------------------------------------
;; eval-parameter-restriction — parameter policy evaluation
;; ---------------------------------------------------------------------------

(defspec eval-param-equals-string-coercion 200
  (prop/for-all [v (gen/not-empty gen/string-alphanumeric)]
    ;; With :operator "equals", (str actual) = (str expected) → matches
    (let [result (eval-parameter-restriction
                   {:parameter "key" :operator "equals" :value v :action "deny"}
                   {:parameters {:key v}})]
      (= :deny (:result result)))))

(defspec eval-param-exists-checks-some 100
  (prop/for-all [v (gen/not-empty gen/string-alphanumeric)]
    ;; With :operator "exists", :action "deny" - if param present → :deny
    (let [result (eval-parameter-restriction
                   {:parameter "key" :operator "exists" :action "deny"}
                   {:parameters {:key v}})]
      (= :deny (:result result)))))

(defspec eval-param-not-exists-checks-nil 100
  (prop/for-all [_ (gen/return nil)]
    ;; With :operator "not-exists", :action "deny" - if param missing → :deny
    (let [result (eval-parameter-restriction
                   {:parameter "missing" :operator "not-exists" :action "deny"}
                   {:parameters {}})]
      (= :deny (:result result)))))

;; ---------------------------------------------------------------------------
;; apply-approval-overrides — empty passthrough
;; ---------------------------------------------------------------------------

(defspec apply-approval-overrides-empty-passthrough 200
  (prop/for-all [stage-def cgen/gen-stage-def]
    (= stage-def (policy/apply-approval-overrides stage-def []))))
