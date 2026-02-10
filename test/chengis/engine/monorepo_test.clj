(ns chengis.engine.monorepo-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.monorepo :as mono]))

;; ---------------------------------------------------------------------------
;; Path matching
;; ---------------------------------------------------------------------------

(deftest path-matches-exact
  (testing "exact path matching"
    (is (true? (mono/path-matches? "src/main.clj" "src/main.clj")))
    (is (false? (mono/path-matches? "src/main.clj" "src/other.clj")))))

(deftest path-matches-single-star
  (testing "single * matches within one directory level"
    (is (true? (mono/path-matches? "src/*.clj" "src/main.clj")))
    (is (true? (mono/path-matches? "src/*.clj" "src/test.clj")))
    (is (false? (mono/path-matches? "src/*.clj" "src/sub/deep.clj")))
    (is (false? (mono/path-matches? "src/*.clj" "test/main.clj")))))

(deftest path-matches-double-star
  (testing "** matches across directory levels"
    (is (true? (mono/path-matches? "src/**" "src/main.clj")))
    (is (true? (mono/path-matches? "src/**" "src/sub/deep.clj")))
    (is (true? (mono/path-matches? "src/**" "src/a/b/c/d.clj")))
    (is (false? (mono/path-matches? "src/**" "test/main.clj")))))

(deftest path-matches-mixed-patterns
  (testing "combined glob patterns"
    (is (true? (mono/path-matches? "src/**/*.clj" "src/engine/core.clj")))
    (is (true? (mono/path-matches? "src/**/*.clj" "src/a/b/c.clj")))
    (is (false? (mono/path-matches? "src/**/*.clj" "src/engine/core.java")))
    (is (true? (mono/path-matches? "*.md" "README.md")))
    (is (false? (mono/path-matches? "*.md" "docs/README.md")))))

;; ---------------------------------------------------------------------------
;; Changed file extraction
;; ---------------------------------------------------------------------------

(deftest extract-github-files
  (testing "extracts changed files from GitHub payload"
    (let [payload {"commits" [{"added" ["src/new.clj"]
                               "modified" ["src/old.clj"]
                               "removed" ["src/dead.clj"]}
                              {"added" []
                               "modified" ["test/core.clj"]
                               "removed" []}]}
          files (mono/extract-changed-files-github payload)]
      (is (= 4 (count files)))
      (is (= #{"src/new.clj" "src/old.clj" "src/dead.clj" "test/core.clj"}
             (set files))))))

(deftest extract-github-deduplicates
  (testing "deduplicates files modified in multiple commits"
    (let [payload {"commits" [{"added" [] "modified" ["src/main.clj"] "removed" []}
                              {"added" [] "modified" ["src/main.clj"] "removed" []}]}
          files (mono/extract-changed-files-github payload)]
      (is (= 1 (count files))))))

(deftest extract-gitlab-files
  (testing "extracts changed files from GitLab payload"
    (let [payload {"commits" [{"added" ["new.py"]
                               "modified" ["old.py"]
                               "removed" []}]}
          files (mono/extract-changed-files-gitlab payload)]
      (is (= 2 (count files))))))

(deftest extract-empty-payload
  (testing "nil/empty payload returns empty"
    (is (= [] (mono/extract-changed-files :github nil)))
    (is (= [] (mono/extract-changed-files :unknown {})))))

;; ---------------------------------------------------------------------------
;; Trigger evaluation
;; ---------------------------------------------------------------------------

(deftest should-trigger-no-filters
  (testing "no filters = always trigger"
    (is (true? (mono/should-trigger? nil ["src/main.clj"])))
    (is (true? (mono/should-trigger? {} ["src/main.clj"])))))

(deftest should-trigger-nil-files
  (testing "nil changed files = trigger (fail-open)"
    (is (true? (mono/should-trigger? {:include ["src/**"]} nil)))))

(deftest should-trigger-empty-files
  (testing "empty changed files = don't trigger"
    (is (false? (mono/should-trigger? {:include ["src/**"]} [])))))

(deftest should-trigger-include-match
  (testing "triggers when files match include patterns"
    (is (true? (mono/should-trigger?
                 {:include ["src/**"]}
                 ["src/main.clj"])))
    (is (true? (mono/should-trigger?
                 {:include ["src/**" "lib/**"]}
                 ["lib/util.clj"])))))

(deftest should-trigger-include-no-match
  (testing "does not trigger when no files match includes"
    (is (false? (mono/should-trigger?
                  {:include ["src/**"]}
                  ["docs/README.md" "test/core.clj"])))))

(deftest should-trigger-exclude-overrides
  (testing "exclude patterns override includes"
    (is (false? (mono/should-trigger?
                  {:include ["src/**"] :exclude ["src/generated/**"]}
                  ["src/generated/output.clj"])))
    ;; But non-excluded files still trigger
    (is (true? (mono/should-trigger?
                 {:include ["src/**"] :exclude ["src/generated/**"]}
                 ["src/main.clj" "src/generated/output.clj"])))))

(deftest should-trigger-only-excluded-files
  (testing "does not trigger when all matching files are excluded"
    (is (false? (mono/should-trigger?
                  {:include ["**"] :exclude ["*.md" "docs/**"]}
                  ["README.md" "docs/setup.md"])))))

(deftest should-trigger-mixed-files
  (testing "triggers when at least one file passes include+exclude"
    (is (true? (mono/should-trigger?
                 {:include ["src/**" "test/**"]
                  :exclude ["*.md"]}
                 ["README.md" "src/core.clj"])))))

;; ---------------------------------------------------------------------------
;; Job filtering
;; ---------------------------------------------------------------------------

(deftest filter-jobs-flag-disabled
  (testing "when flag disabled, all jobs returned"
    (let [config {:feature-flags {:monorepo-filtering false}}
          jobs [{:name "backend" :path-filters {:include ["src/backend/**"]}}
                {:name "frontend" :path-filters {:include ["src/frontend/**"]}}]]
      (is (= 2 (count (mono/filter-jobs-by-paths config jobs ["src/backend/main.clj"])))))))

(deftest filter-jobs-flag-enabled
  (testing "when flag enabled, only matching jobs returned"
    (let [config {:feature-flags {:monorepo-filtering true}}
          jobs [{:name "backend" :path-filters {:include ["src/backend/**"]}}
                {:name "frontend" :path-filters {:include ["src/frontend/**"]}}
                {:name "shared" :path-filters nil}]]
      ;; Only backend file changed — should trigger backend + shared (no filters)
      (let [filtered (mono/filter-jobs-by-paths config jobs ["src/backend/main.clj"])]
        (is (= 2 (count filtered)))
        (is (= #{"backend" "shared"} (set (map :name filtered))))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest validate-valid-filters
  (testing "valid path filters pass validation"
    (let [result (mono/validate-path-filters {:include ["src/**"] :exclude ["docs/**"]})]
      (is (true? (:valid? result))))))

(deftest validate-invalid-include-type
  (testing "non-sequential include fails validation"
    (let [result (mono/validate-path-filters {:include "src/**"})]
      (is (false? (:valid? result))))))

(deftest validate-non-string-pattern
  (testing "non-string pattern fails validation"
    (let [result (mono/validate-path-filters {:include [42]})]
      (is (false? (:valid? result))))))
