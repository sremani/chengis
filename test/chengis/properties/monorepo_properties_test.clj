(ns chengis.properties.monorepo-properties-test
  "Property-based tests for monorepo path-based trigger filtering.
   Covers glob->regex (private), path-matches?, extract-changed-files-github,
   extract-changed-files-gitlab, extract-changed-files, and should-trigger?."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.monorepo :as monorepo]))

(def ^:private glob->regex #'chengis.engine.monorepo/glob->regex)

;; ---------------------------------------------------------------------------
;; glob->regex — literal dots are escaped
;; ---------------------------------------------------------------------------

(defspec glob-regex-escapes-dots 100
  (prop/for-all [name (gen/not-empty gen/string-alphanumeric)
                 ext (gen/elements [".clj" ".java" ".py" ".md"])]
    (let [pattern (str name ext)
          result (glob->regex pattern)]
      (str/includes? result "\\."))))

;; ---------------------------------------------------------------------------
;; glob->regex — single star does not match path separators
;; ---------------------------------------------------------------------------

(defspec glob-regex-single-star-no-slash 50
  (prop/for-all [_ (gen/return nil)]
    (let [regex-str (glob->regex "src/*")]
      ;; Single * should become [^/]* which does not match /
      (not (re-matches (re-pattern (str "^" regex-str "$")) "src/a/b")))))

;; ---------------------------------------------------------------------------
;; glob->regex — double star matches path separators
;; ---------------------------------------------------------------------------

(defspec glob-regex-double-star-matches-slash 50
  (prop/for-all [_ (gen/return nil)]
    (let [regex-str (glob->regex "src/**")]
      ;; ** should become .* which matches /
      (boolean (re-matches (re-pattern (str "^" regex-str "$")) "src/a/b")))))

;; ---------------------------------------------------------------------------
;; path-matches? — nil inputs return nil
;; ---------------------------------------------------------------------------

(defspec path-matches-nil-pattern-returns-nil 50
  (prop/for-all [path cgen/gen-file-path-with-dirs]
    (nil? (monorepo/path-matches? nil path))))

(defspec path-matches-nil-path-returns-nil 50
  (prop/for-all [pattern (gen/not-empty gen/string-alphanumeric)]
    (nil? (monorepo/path-matches? pattern nil))))

;; ---------------------------------------------------------------------------
;; path-matches? — exact match
;; ---------------------------------------------------------------------------

(defspec path-matches-exact-self-match 100
  (prop/for-all [path cgen/gen-file-path-with-dirs]
    (true? (monorepo/path-matches? path path))))

;; ---------------------------------------------------------------------------
;; path-matches? — "**" matches everything
;; ---------------------------------------------------------------------------

(defspec path-matches-doublestar-matches-all 100
  (prop/for-all [path cgen/gen-file-path-with-dirs]
    (true? (monorepo/path-matches? "**" path))))

;; ---------------------------------------------------------------------------
;; extract-changed-files — nil payload returns nil for github/gitlab
;; ---------------------------------------------------------------------------

(defspec extract-changed-files-github-nil-returns-nil 20
  (prop/for-all [_ (gen/return nil)]
    (nil? (monorepo/extract-changed-files-github nil))))

(defspec extract-changed-files-gitlab-nil-returns-nil 20
  (prop/for-all [_ (gen/return nil)]
    (nil? (monorepo/extract-changed-files-gitlab nil))))

;; ---------------------------------------------------------------------------
;; extract-changed-files — unknown provider returns empty vec
;; ---------------------------------------------------------------------------

(defspec extract-changed-files-unknown-provider 50
  (prop/for-all [_ (gen/return nil)]
    (= [] (monorepo/extract-changed-files :bitbucket {"commits" []}))))

;; ---------------------------------------------------------------------------
;; should-trigger? — nil/empty filters always trigger
;; ---------------------------------------------------------------------------

(defspec should-trigger-nil-filters-always-true 100
  (prop/for-all [files (gen/vector cgen/gen-file-path-with-dirs 0 5)]
    (and (true? (monorepo/should-trigger? nil files))
         (true? (monorepo/should-trigger? {} files)))))

;; ---------------------------------------------------------------------------
;; should-trigger? — nil changed-files triggers (fail-open)
;; ---------------------------------------------------------------------------

(defspec should-trigger-nil-files-triggers 50
  (prop/for-all [_ (gen/return nil)]
    (true? (monorepo/should-trigger? {:include ["src/**"]} nil))))

;; ---------------------------------------------------------------------------
;; should-trigger? — empty changed-files does not trigger
;; ---------------------------------------------------------------------------

(defspec should-trigger-empty-files-no-trigger 50
  (prop/for-all [_ (gen/return nil)]
    (false? (monorepo/should-trigger? {:include ["src/**"]} []))))
