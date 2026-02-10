(ns chengis.engine.branch-overrides-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.branch-overrides :as bo]))

;; ---------------------------------------------------------------------------
;; Pattern matching
;; ---------------------------------------------------------------------------

(deftest exact-match
  (testing "exact branch matching"
    (is (true? (bo/branch-matches? "main" "main")))
    (is (true? (bo/branch-matches? "develop" "develop")))
    (is (false? (bo/branch-matches? "main" "master")))
    (is (false? (bo/branch-matches? "main" "main-2")))))

(deftest glob-single-star
  (testing "single * matches anything except /"
    (is (true? (bo/branch-matches? "release/*" "release/1.0")))
    (is (true? (bo/branch-matches? "release/*" "release/v2.3.4")))
    (is (false? (bo/branch-matches? "release/*" "release/1.0/hotfix")))
    (is (true? (bo/branch-matches? "feature/*" "feature/my-feature")))
    (is (false? (bo/branch-matches? "feature/*" "feature/deep/nested")))))

(deftest glob-double-star
  (testing "** matches anything including /"
    (is (true? (bo/branch-matches? "feature/**" "feature/my-feature")))
    (is (true? (bo/branch-matches? "feature/**" "feature/deep/nested")))
    (is (true? (bo/branch-matches? "feature/**" "feature/a/b/c/d")))
    (is (false? (bo/branch-matches? "feature/**" "bugfix/something")))))

(deftest regex-pattern
  (testing "~ prefix enables regex matching"
    (is (true? (bo/branch-matches? "~release/v\\d+\\.\\d+\\.\\d+" "release/v1.2.3")))
    (is (false? (bo/branch-matches? "~release/v\\d+\\.\\d+\\.\\d+" "release/beta")))
    (is (true? (bo/branch-matches? "~(main|master)" "main")))
    (is (true? (bo/branch-matches? "~(main|master)" "master")))
    (is (false? (bo/branch-matches? "~(main|master)" "develop")))))

(deftest nil-handling
  (testing "nil branch or pattern returns nil/falsy"
    (is (nil? (bo/branch-matches? nil "main")))
    (is (nil? (bo/branch-matches? "main" nil)))
    (is (nil? (bo/branch-matches? nil nil)))))

;; ---------------------------------------------------------------------------
;; Override resolution
;; ---------------------------------------------------------------------------

(deftest find-matching-override-first-match-wins
  (testing "returns first matching override"
    (let [overrides [{:pattern "release/*" :parameters {:env "prod"}}
                     {:pattern "release/**" :parameters {:env "staging"}}]]
      (is (= {:pattern "release/*" :parameters {:env "prod"}}
             (bo/find-matching-override overrides "release/1.0")))
      ;; Nested path only matches **
      (is (= {:pattern "release/**" :parameters {:env "staging"}}
             (bo/find-matching-override overrides "release/1.0/hotfix"))))))

(deftest find-matching-override-no-match
  (testing "returns nil when no match"
    (let [overrides [{:pattern "release/*" :parameters {:env "prod"}}]]
      (is (nil? (bo/find-matching-override overrides "feature/foo")))
      (is (nil? (bo/find-matching-override [] "main")))
      (is (nil? (bo/find-matching-override nil "main"))))))

;; ---------------------------------------------------------------------------
;; Override application
;; ---------------------------------------------------------------------------

(def base-pipeline
  {:pipeline-name "test-pipeline"
   :stages [{:stage-name "Build" :steps [{:name "compile" :command "make"}]}
            {:stage-name "Test"  :steps [{:name "test" :command "make test"}]}
            {:stage-name "Deploy" :steps [{:name "deploy" :command "make deploy"}]}]
   :parameters {:env "dev"}
   :environment {"APP_ENV" "development"}})

(deftest apply-skip-stages
  (testing "skip-stages removes named stages"
    (let [override {:pattern "feature/*" :skip-stages ["Deploy"]}
          result (bo/apply-override base-pipeline override)]
      (is (= 2 (count (:stages result))))
      (is (= #{"Build" "Test"} (set (map :stage-name (:stages result))))))))

(deftest apply-additional-stages
  (testing "additional stages are appended"
    (let [override {:pattern "release/*"
                    :stages [{:stage-name "Publish" :steps [{:name "npm-publish" :command "npm publish"}]}]}
          result (bo/apply-override base-pipeline override)]
      (is (= 4 (count (:stages result))))
      (is (= "Publish" (:stage-name (last (:stages result))))))))

(deftest apply-parameter-overrides
  (testing "parameters are merged (override wins)"
    (let [override {:pattern "release/*" :parameters {:env "production" :version "1.0"}}
          result (bo/apply-override base-pipeline override)]
      (is (= "production" (get-in result [:parameters :env])))
      (is (= "1.0" (get-in result [:parameters :version]))))))

(deftest apply-environment-overrides
  (testing "environment vars are merged"
    (let [override {:pattern "release/*" :environment {"APP_ENV" "production" "DEBUG" "false"}}
          result (bo/apply-override base-pipeline override)]
      (is (= "production" (get-in result [:environment "APP_ENV"])))
      (is (= "false" (get-in result [:environment "DEBUG"]))))))

(deftest apply-combined-overrides
  (testing "skip + append + params all applied"
    (let [override {:pattern "release/*"
                    :skip-stages ["Test"]
                    :stages [{:stage-name "Canary" :steps [{:name "canary" :command "deploy canary"}]}]
                    :parameters {:env "production"}}
          result (bo/apply-override base-pipeline override)]
      (is (= 3 (count (:stages result))))
      (is (= ["Build" "Deploy" "Canary"] (mapv :stage-name (:stages result))))
      (is (= "production" (get-in result [:parameters :env]))))))

(deftest apply-nil-override-returns-unchanged
  (testing "nil override returns pipeline unchanged"
    (is (= base-pipeline (bo/apply-override base-pipeline nil)))))

;; ---------------------------------------------------------------------------
;; Resolve pipeline (feature flag gating)
;; ---------------------------------------------------------------------------

(deftest resolve-pipeline-flag-disabled
  (testing "resolve-pipeline returns unchanged pipeline when flag disabled"
    (let [config {:feature-flags {:branch-overrides false}}
          overrides [{:pattern "release/*" :skip-stages ["Deploy"]}]]
      (is (= base-pipeline (bo/resolve-pipeline config base-pipeline "release/1.0" overrides))))))

(deftest resolve-pipeline-flag-enabled
  (testing "resolve-pipeline applies matching override when flag enabled"
    (let [config {:feature-flags {:branch-overrides true}}
          overrides [{:pattern "release/*" :parameters {:env "production"}}]]
      (let [result (bo/resolve-pipeline config base-pipeline "release/1.0" overrides)]
        (is (= "production" (get-in result [:parameters :env])))))))

(deftest resolve-pipeline-no-match
  (testing "resolve-pipeline returns unchanged when no pattern matches"
    (let [config {:feature-flags {:branch-overrides true}}
          overrides [{:pattern "release/*" :parameters {:env "production"}}]]
      (is (= base-pipeline (bo/resolve-pipeline config base-pipeline "feature/foo" overrides))))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(deftest validate-valid-overrides
  (testing "valid overrides pass validation"
    (let [result (bo/validate-overrides [{:pattern "release/*" :skip-stages ["Deploy"]}
                                         {:pattern "main" :parameters {:env "prod"}}])]
      (is (true? (:valid? result)))
      (is (empty? (:errors result))))))

(deftest validate-missing-pattern
  (testing "override without pattern fails validation"
    (let [result (bo/validate-overrides [{:skip-stages ["Deploy"]}])]
      (is (false? (:valid? result)))
      (is (= 1 (count (:errors result)))))))

(deftest validate-wrong-types
  (testing "wrong types fail validation"
    (let [result (bo/validate-overrides [{:pattern "main" :skip-stages "Deploy"}])]
      (is (false? (:valid? result))))))

;; ---------------------------------------------------------------------------
;; Parse overrides
;; ---------------------------------------------------------------------------

(deftest parse-empty-string
  (testing "blank string returns empty vec"
    (is (= [] (bo/parse-overrides "")))
    (is (= [] (bo/parse-overrides nil)))))

(deftest parse-valid-edn
  (testing "valid EDN parses correctly"
    (let [result (bo/parse-overrides "[{:pattern \"main\" :skip-stages [\"Deploy\"]}]")]
      (is (= 1 (count result)))
      (is (= "main" (:pattern (first result)))))))
