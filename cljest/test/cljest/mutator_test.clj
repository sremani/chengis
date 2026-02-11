(ns cljest.mutator-test
  (:require [clojure.test :refer [deftest testing is]]
            [cljest.mutator :as mut]
            [cljest.operators :as ops]
            [rewrite-clj.zip :as z]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- with-temp-source
  "Write source-str to a temp .clj file, call (f path), then delete the file."
  [source-str f]
  (let [tmp (java.io.File/createTempFile "cljest-test-" ".clj")]
    (try
      (spit tmp source-str)
      (f (.getAbsolutePath tmp))
      (finally
        (.delete tmp)))))

;; ---------------------------------------------------------------------------
;; find-mutation-sites-from-string
;; ---------------------------------------------------------------------------

(deftest find-sites-simple-addition
  (testing "finds + operator site in simple add function"
    (let [src   "(defn add [a b] (+ a b))"
          ids   #{:arith-plus-minus}
          sites (mut/find-mutation-sites-from-string src ids)]
      (is (= 1 (count sites)))
      (is (contains? (:operator-ids (first sites)) :arith-plus-minus))
      (is (= '+ (:original-form (first sites)))))))

(deftest find-sites-multiple-operators
  (testing "finds >, if, true, false in conditional code"
    (let [src   "(defn check [x] (if (> x 0) true false))"
          ids   #{:comp-gt-lt :logical-negate-if :logical-true-false :logical-false-true
                  :nil-if-if-not}
          sites (mut/find-mutation-sites-from-string src ids)
          all-op-ids (set (mapcat :operator-ids sites))]
      ;; Should find > (comparison), if (negate-if + if-if-not), true, false
      (is (contains? all-op-ids :comp-gt-lt))
      (is (contains? all-op-ids :logical-negate-if))
      (is (contains? all-op-ids :logical-true-false))
      (is (contains? all-op-ids :logical-false-true)))))

(deftest find-sites-empty-source
  (testing "empty source returns empty sites"
    (let [sites (mut/find-mutation-sites-from-string "" #{:arith-plus-minus})]
      (is (empty? sites)))))

(deftest find-sites-no-matching-operators
  (testing "source without matching forms returns empty sites"
    (let [src   "(defn foo [x] (str x))"
          sites (mut/find-mutation-sites-from-string src #{:arith-plus-minus})]
      (is (empty? sites)))))

;; ---------------------------------------------------------------------------
;; apply-mutation-to-string
;; ---------------------------------------------------------------------------

(deftest apply-mutation-plus-to-minus
  (testing "apply :arith-plus-minus replaces + with -"
    (let [src    "(+ a b)"
          sites  (mut/find-mutation-sites-from-string src #{:arith-plus-minus})
          pos    (:position (first sites))
          result (mut/apply-mutation-to-string src pos :arith-plus-minus)]
      (is (= "(- a b)" result)))))

(deftest apply-mutation-gt-to-lt
  (testing "apply :comp-gt-lt replaces > with <"
    (let [src    "(> x 0)"
          sites  (mut/find-mutation-sites-from-string src #{:comp-gt-lt})
          pos    (:position (first sites))
          result (mut/apply-mutation-to-string src pos :comp-gt-lt)]
      (is (= "(< x 0)" result)))))

(deftest apply-mutation-preserves-surrounding-code
  (testing "mutation only affects the target operator, not surrounding code"
    (let [src    "(defn add [a b] (+ a b))"
          sites  (mut/find-mutation-sites-from-string src #{:arith-plus-minus})
          pos    (:position (first sites))
          result (mut/apply-mutation-to-string src pos :arith-plus-minus)]
      (is (= "(defn add [a b] (- a b))" result)))))

(deftest apply-mutation-nonexistent-position
  (testing "apply-mutation-to-string returns nil for non-existent position"
    (let [result (mut/apply-mutation-to-string "(+ 1 2)" [99 99] :arith-plus-minus)]
      (is (nil? result)))))

(deftest apply-mutation-nonexistent-operator
  (testing "apply-mutation-to-string returns nil for non-existent operator"
    (let [result (mut/apply-mutation-to-string "(+ 1 2)" [1 1] :nonexistent-op)]
      (is (nil? result)))))

;; ---------------------------------------------------------------------------
;; expand-mutations
;; ---------------------------------------------------------------------------

(deftest expand-mutations-single-operator
  (testing "site with one operator produces one mutation"
    (let [sites [{:position [1 1]
                  :operator-ids #{:arith-plus-minus}
                  :original-form '+
                  :source-path "test.clj"}]
          expanded (mut/expand-mutations sites)]
      (is (= 1 (count expanded)))
      (is (= :arith-plus-minus (:operator-id (first expanded))))
      (is (not (contains? (first expanded) :operator-ids))))))

(deftest expand-mutations-multiple-operators
  (testing "site with N operators produces N mutations"
    (let [sites [{:position [1 5]
                  :operator-ids #{:logical-negate-if :nil-if-if-not}
                  :original-form 'if
                  :source-path "test.clj"}]
          expanded (mut/expand-mutations sites)]
      (is (= 2 (count expanded)))
      (is (= #{:logical-negate-if :nil-if-if-not}
             (set (map :operator-id expanded)))))))

(deftest expand-mutations-multiple-sites
  (testing "multiple sites each with multiple operators expand correctly"
    (let [sites [{:position [1 1]
                  :operator-ids #{:arith-plus-minus}
                  :original-form '+
                  :source-path "test.clj"}
                 {:position [2 3]
                  :operator-ids #{:comp-gt-lt :comp-gt-gte}
                  :original-form '>
                  :source-path "test.clj"}]
          expanded (mut/expand-mutations sites)]
      (is (= 3 (count expanded))))))

(deftest expand-mutations-empty-sites
  (testing "empty sites produces empty expansions"
    (is (empty? (mut/expand-mutations [])))))

;; ---------------------------------------------------------------------------
;; count-mutations (file-based)
;; ---------------------------------------------------------------------------

(deftest count-mutations-simple
  (testing "count-mutations returns correct total for a simple source file"
    (with-temp-source "(defn add [a b] (+ a b))"
      (fn [path]
        (let [cnt (mut/count-mutations path #{:arith-plus-minus})]
          (is (= 1 cnt)))))))

(deftest count-mutations-multiple-sites
  (testing "count-mutations sums operators across all sites"
    (with-temp-source "(defn f [a b] (+ (- a b) (* a b)))"
      (fn [path]
        (let [cnt (mut/count-mutations path #{:arith-plus-minus :arith-minus-plus
                                              :arith-mult-div})]
          ;; + matches :arith-plus-minus, - matches :arith-minus-plus, * matches :arith-mult-div
          (is (= 3 cnt)))))))

;; ---------------------------------------------------------------------------
;; Skip forms (uses file-based find-mutation-sites which checks in-skip-form?)
;; ---------------------------------------------------------------------------

(deftest skip-forms-log-info
  (testing "mutations inside (log/info ...) are skipped but outside are found"
    (with-temp-source "(defn f [] (log/info (+ 1 2)) (+ 3 4))"
      (fn [path]
        (let [sites (mut/find-mutation-sites path #{:arith-plus-minus})
              ;; Only the + in (+ 3 4) should be found, not the one in (log/info ...)
              forms (map :original-form sites)]
          (is (= 1 (count sites))
              "Expected exactly one mutation site outside the log form")
          (is (= '+ (first forms))))))))

(deftest skip-forms-comment
  (testing "mutations inside (comment ...) are skipped"
    (with-temp-source "(defn f [] (comment (+ 1 2)) (+ 3 4))"
      (fn [path]
        (let [sites (mut/find-mutation-sites path #{:arith-plus-minus})]
          (is (= 1 (count sites))
              "Expected exactly one mutation site outside the comment form"))))))

(deftest skip-forms-println
  (testing "mutations inside (println ...) are skipped"
    (with-temp-source "(defn f [] (println (+ 1 2)) (+ 3 4))"
      (fn [path]
        (let [sites (mut/find-mutation-sites path #{:arith-plus-minus})]
          (is (= 1 (count sites))))))))

;; ---------------------------------------------------------------------------
;; navigate-to-position
;; ---------------------------------------------------------------------------

(deftest navigate-to-position-found
  (testing "navigate-to-position finds the correct node"
    (let [zloc   (z/of-string "(+ a b)" {:track-position? true})
          target (mut/navigate-to-position zloc [1 1])]
      (is (some? target))
      (is (z/sexpr-able? target)))))

(deftest navigate-to-position-not-found
  (testing "navigate-to-position returns nil for non-existent position"
    (let [zloc   (z/of-string "(+ a b)" {:track-position? true})
          target (mut/navigate-to-position zloc [99 99])]
      (is (nil? target)))))

(deftest navigate-to-position-inner-form
  (testing "navigate-to-position finds nodes in nested forms"
    (let [src    "(defn f [x] (+ x 1))"
          zloc   (z/of-string src {:track-position? true})
          ;; Find the + position first
          sites  (mut/find-mutation-sites-from-string src #{:arith-plus-minus})
          pos    (:position (first sites))
          target (mut/navigate-to-position zloc pos)]
      (is (some? target))
      (is (= '+ (z/sexpr target))))))
