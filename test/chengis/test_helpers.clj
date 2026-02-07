(ns chengis.test-helpers
  "Shared utilities for testing Hiccup-based views.")

(defn hiccup-contains?
  "Recursively check if a Hiccup data structure contains a string `s`.
   Walks vectors, seqs, and maps (values only). Returns true/false."
  [tree s]
  (cond
    (nil? tree) false
    (string? tree) (.contains ^String tree ^String s)
    (keyword? tree) false
    (number? tree) (.contains (str tree) s)
    (map? tree) (some #(hiccup-contains? % s) (vals tree))
    (sequential? tree) (some #(hiccup-contains? % s) tree)
    :else false))

(defn hiccup-find-class
  "Check if any element in the Hiccup tree has a :class attribute
   containing `class-substr`. Returns true/false."
  [tree class-substr]
  (cond
    (nil? tree) false
    (map? tree) (or (when-let [c (:class tree)]
                      (and (string? c) (.contains ^String c ^String class-substr)))
                    (some #(hiccup-find-class % class-substr) (vals tree)))
    (sequential? tree) (some #(hiccup-find-class % class-substr) tree)
    :else false))
