(ns cljest.selector
  "Namespace discovery, filtering, and source-to-test matching.
   Discovers source and test namespaces from project paths,
   applies include/exclude regex patterns, and matches source
   namespaces to their corresponding test namespaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.find :as ns-find]))

;; ---------------------------------------------------------------------------
;; Namespace discovery
;; ---------------------------------------------------------------------------

(defn discover-namespaces
  "Discover all Clojure namespaces in the given directory paths.
   Returns a sorted set of namespace symbols."
  [paths]
  (->> paths
       (filter #(.isDirectory (io/file %)))
       (mapcat #(ns-find/find-namespaces-in-dir (io/file %)))
       (into (sorted-set))))

(defn discover-source-namespaces
  "Discover source namespaces from :source-paths."
  [source-paths]
  (discover-namespaces source-paths))

(defn discover-test-namespaces
  "Discover test namespaces from :test-paths."
  [test-paths]
  (discover-namespaces test-paths))

;; ---------------------------------------------------------------------------
;; Filtering
;; ---------------------------------------------------------------------------

(defn- matches-any-pattern?
  "Check if a namespace name matches any of the given regex patterns."
  [ns-name patterns]
  (some #(re-find % ns-name) patterns))

(defn filter-namespaces
  "Apply include/exclude regex patterns to a seq of namespace symbols.
   If :namespaces patterns are given, only matching namespaces are included.
   If :exclude-namespaces patterns are given, matching namespaces are excluded."
  [nses config]
  (let [include-patterns (:namespaces config)
        exclude-patterns (:exclude-namespaces config)]
    (cond->> nses
      (seq include-patterns)
      (filter #(matches-any-pattern? (str %) include-patterns))

      (seq exclude-patterns)
      (remove #(matches-any-pattern? (str %) exclude-patterns)))))

;; ---------------------------------------------------------------------------
;; Source-to-test matching
;; ---------------------------------------------------------------------------

(defn- test-ns-candidates
  "Generate candidate test namespace names for a source namespace.
   Convention: foo.bar → foo.bar-test, foo.bar-tests."
  [src-ns]
  (let [ns-str (str src-ns)]
    [(symbol (str ns-str "-test"))
     (symbol (str ns-str "-tests"))
     ;; Also handle properties tests (common in Chengis)
     (symbol (str ns-str "-properties-test"))]))

(defn match-source-to-tests
  "Given a source namespace and a set of all test namespaces,
   find the matching test namespaces.
   Returns a vec of matched test namespace symbols."
  [src-ns test-nses]
  (let [candidates (set (test-ns-candidates src-ns))
        matched (filterv #(contains? candidates %) test-nses)]
    matched))

;; ---------------------------------------------------------------------------
;; Namespace → file path
;; ---------------------------------------------------------------------------

(defn namespace-to-file
  "Convert a namespace symbol to its source file path.
   Searches the given source-paths for the file.
   Returns the absolute file path string, or nil."
  [ns-sym source-paths]
  (let [;; Convert ns to relative path: foo.bar-baz → foo/bar_baz.clj
        relative (-> (str ns-sym)
                     (str/replace "-" "_")
                     (str/replace "." "/")
                     (str ".clj"))]
    (some (fn [base]
            (let [f (io/file base relative)]
              (when (.exists f)
                (.getAbsolutePath f))))
          source-paths)))

;; ---------------------------------------------------------------------------
;; Summary
;; ---------------------------------------------------------------------------

(defn discover-mutation-targets
  "Discover and resolve all mutation targets.
   Returns a seq of maps:
     {:source-ns sym
      :source-file path
      :test-namespaces [sym ...]}

   Only includes namespaces that have at least one matching test namespace
   and a source file on disk."
  [config]
  (let [source-nses (discover-source-namespaces (:source-paths config))
        test-nses (into #{} (discover-test-namespaces (:test-paths config)))
        filtered (filter-namespaces source-nses config)]
    (->> filtered
         (map (fn [src-ns]
                (let [src-file (namespace-to-file src-ns (:source-paths config))
                      test-matches (match-source-to-tests src-ns test-nses)]
                  (when (and src-file (seq test-matches))
                    {:source-ns src-ns
                     :source-file src-file
                     :test-namespaces test-matches}))))
         (remove nil?)
         vec)))
