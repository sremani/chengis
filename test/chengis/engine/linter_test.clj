(ns chengis.engine.linter-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.linter :as linter]))

;; ---------------------------------------------------------------------------
;; Helper: minimal valid pipeline
;; ---------------------------------------------------------------------------

(def valid-pipeline
  {:description "Test pipeline"
   :stages [{:stage-name "Build"
             :parallel? false
             :steps [{:step-name "Compile" :type :shell :command "make build"}
                     {:step-name "Lint" :type :shell :command "make lint"}]}
            {:stage-name "Test"
             :parallel? true
             :steps [{:step-name "Unit" :type :shell :command "make test"}
                     {:step-name "Integration" :type :shell :command "make itest"}]}]
   :source {:type :git :url "https://github.com/example/repo"}})

(defn- has-rule? [issues rule]
  (some #(= rule (:rule %)) issues))

;; ---------------------------------------------------------------------------
;; 1. Valid pipeline passes
;; ---------------------------------------------------------------------------

(deftest valid-pipeline-passes
  (testing "a well-formed pipeline returns no errors"
    (let [result (linter/lint-pipeline valid-pipeline)]
      (is (:valid? result))
      (is (empty? (:errors result))))))

;; ---------------------------------------------------------------------------
;; 2. Missing stages detected
;; ---------------------------------------------------------------------------

(deftest missing-stages-detected
  (testing "pipeline without :stages key is an error"
    (let [result (linter/lint-pipeline {:description "no stages"})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :missing-stages)))))

;; ---------------------------------------------------------------------------
;; 3. Empty stages detected
;; ---------------------------------------------------------------------------

(deftest empty-stages-detected
  (testing "empty stages vector is an error"
    (let [result (linter/lint-pipeline {:stages []})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :empty-stages)))))

;; ---------------------------------------------------------------------------
;; 4. Duplicate stage names detected
;; ---------------------------------------------------------------------------

(deftest duplicate-stage-names-detected
  (testing "duplicate stage names are reported"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build" :steps [{:step-name "s1" :type :shell :command "echo"}]}
                             {:stage-name "Build" :steps [{:step-name "s2" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :duplicate-stage-name)))))

;; ---------------------------------------------------------------------------
;; 5. Duplicate step names within stage detected
;; ---------------------------------------------------------------------------

(deftest duplicate-step-names-within-stage
  (testing "duplicate step names in same stage are reported"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Run" :type :shell :command "echo again"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :duplicate-step-name)))))

;; ---------------------------------------------------------------------------
;; 6. Invalid step type detected
;; ---------------------------------------------------------------------------

(deftest invalid-step-type-detected
  (testing "unrecognized step type is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Bad" :type :kubernetes :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-step-type)))))

;; ---------------------------------------------------------------------------
;; 7. DAG: invalid dependency reference detected
;; ---------------------------------------------------------------------------

(deftest dag-invalid-reference
  (testing "depends-on referencing non-existent stage is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "s1" :type :shell :command "echo"}]}
                             {:stage-name "Deploy"
                              :depends-on ["NonExistent"]
                              :steps [{:step-name "s2" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-dag-reference)))))

;; ---------------------------------------------------------------------------
;; 8. DAG: cycle detected
;; ---------------------------------------------------------------------------

(deftest dag-cycle-detected
  (testing "circular dependency is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "A"
                              :depends-on ["B"]
                              :steps [{:step-name "s1" :type :shell :command "echo"}]}
                             {:stage-name "B"
                              :depends-on ["A"]
                              :steps [{:step-name "s2" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :circular-dependency)))))

;; ---------------------------------------------------------------------------
;; 9. Docker step missing image detected
;; ---------------------------------------------------------------------------

(deftest docker-step-missing-image
  (testing "docker step without :image is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :docker :command "npm test"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :docker-missing-image)))))

;; ---------------------------------------------------------------------------
;; 10. Invalid timeout detected
;; ---------------------------------------------------------------------------

(deftest invalid-timeout-detected
  (testing "negative timeout is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo" :timeout -1}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-timeout))))

  (testing "string timeout is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo" :timeout "30"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-timeout)))))

;; ---------------------------------------------------------------------------
;; 11. Matrix validation: bad keys
;; ---------------------------------------------------------------------------

(deftest matrix-invalid-values
  (testing "matrix key with non-vector value is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :matrix {:os "linux"}})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-matrix-values)))))

;; ---------------------------------------------------------------------------
;; 12. Matrix validation: exclude references
;; ---------------------------------------------------------------------------

(deftest matrix-invalid-exclude
  (testing "matrix exclude referencing unknown key is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :matrix {:os ["linux" "mac"]
                             :exclude [{:arch "arm64"}]}})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-matrix-exclude)))))

;; ---------------------------------------------------------------------------
;; 13. Parameter validation: missing name/type
;; ---------------------------------------------------------------------------

(deftest parameter-missing-name
  (testing "parameter without :name is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :parameters [{:type :text}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :parameter-missing-name)))))

(deftest parameter-missing-type
  (testing "parameter without :type is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :parameters [{:name "env"}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :parameter-missing-type)))))

;; ---------------------------------------------------------------------------
;; 14. Parameter validation: choice missing choices
;; ---------------------------------------------------------------------------

(deftest choice-parameter-missing-choices
  (testing "choice parameter without :choices is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :parameters [{:name "env" :type :choice}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :choice-missing-choices)))))

;; ---------------------------------------------------------------------------
;; 15. Post-action validation
;; ---------------------------------------------------------------------------

(deftest invalid-post-action-key
  (testing "invalid post-action key is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :post-actions {:always [] :on-error []}})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-post-action-key)))))

;; ---------------------------------------------------------------------------
;; 16. Environment validation
;; ---------------------------------------------------------------------------

(deftest invalid-env-type
  (testing "non-map :env is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo" :env "bad"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-env-type)))))

;; ---------------------------------------------------------------------------
;; 17. Warning: missing description
;; ---------------------------------------------------------------------------

(deftest warning-missing-description
  (testing "pipeline without description generates a warning"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (has-rule? (:warnings result) :missing-description)))))

;; ---------------------------------------------------------------------------
;; 18. Warning: very long timeout
;; ---------------------------------------------------------------------------

(deftest warning-long-timeout
  (testing "timeout > 1 hour generates a warning"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"
                                       :timeout 7200000}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (has-rule? (:warnings result) :long-timeout)))))

;; ---------------------------------------------------------------------------
;; 19. Info: correct counts
;; ---------------------------------------------------------------------------

(deftest info-correct-counts
  (testing "info summary has correct stage and step counts"
    (let [result (linter/lint-pipeline valid-pipeline)]
      (is (= 2 (get-in result [:info :stages])))
      (is (= 4 (get-in result [:info :steps])))
      (is (false? (get-in result [:info :has-dag?])))
      (is (false? (get-in result [:info :has-matrix?])))
      (is (false? (get-in result [:info :has-docker?]))))))

;; ---------------------------------------------------------------------------
;; 20. Info: DAG detection
;; ---------------------------------------------------------------------------

(deftest info-dag-detection
  (testing "info correctly reports DAG presence"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "A"
                              :steps [{:step-name "s1" :type :shell :command "echo"}
                                      {:step-name "s2" :type :shell :command "echo"}]}
                             {:stage-name "B"
                              :depends-on ["A"]
                              :steps [{:step-name "s3" :type :shell :command "echo"}
                                      {:step-name "s4" :type :shell :command "echo"}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (true? (get-in result [:info :has-dag?]))))))

;; ---------------------------------------------------------------------------
;; 21. Info: Docker detection
;; ---------------------------------------------------------------------------

(deftest info-docker-detection
  (testing "info correctly reports Docker step presence"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :docker :command "npm test" :image "node:18"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (true? (get-in result [:info :has-docker?]))))))

;; ---------------------------------------------------------------------------
;; 22. Empty stage detected
;; ---------------------------------------------------------------------------

(deftest empty-stage-steps-detected
  (testing "stage with empty steps list is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Empty" :steps []}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :empty-stage)))))

;; ---------------------------------------------------------------------------
;; 23. Notify missing type
;; ---------------------------------------------------------------------------

(deftest notify-missing-type
  (testing "notify entry without :type is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :notify [{:webhook-url "https://example.com"}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :notify-missing-type)))))

;; ---------------------------------------------------------------------------
;; 24. Cache config validation
;; ---------------------------------------------------------------------------

(deftest cache-missing-key
  (testing "cache config without :key is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :cache {:paths ["/tmp"]}
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :cache-missing-key)))))

(deftest cache-missing-paths
  (testing "cache config without :paths is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :cache {:key "npm-cache"}
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :cache-missing-paths)))))

;; ---------------------------------------------------------------------------
;; 25. Multiple errors accumulated correctly
;; ---------------------------------------------------------------------------

(deftest multiple-errors-accumulated
  (testing "multiple issues are collected in a single lint run"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :docker :command "npm test"}
                                      {:step-name "Run" :type :shell :command "echo" :timeout -5}]}]})]
      (is (not (:valid? result)))
      ;; Should have at least docker-missing-image and invalid-timeout and duplicate-step-name
      (is (>= (count (:errors result)) 3))
      (is (has-rule? (:errors result) :docker-missing-image))
      (is (has-rule? (:errors result) :invalid-timeout))
      (is (has-rule? (:errors result) :duplicate-step-name)))))

;; ---------------------------------------------------------------------------
;; 26. Clean pipeline returns no errors and no warnings (with all fields)
;; ---------------------------------------------------------------------------

(deftest clean-pipeline-no-issues
  (testing "fully-specified pipeline returns no errors"
    (let [result (linter/lint-pipeline
                   {:description "A complete pipeline"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Compile" :type :shell :command "make"}
                                      {:step-name "Lint" :type :shell :command "make lint"}]}
                             {:stage-name "Test"
                              :depends-on ["Build"]
                              :steps [{:step-name "Unit" :type :shell :command "make test"}
                                      {:step-name "Integration" :type :shell :command "make itest"}]}]
                    :source {:type :git :url "https://github.com/example/repo"}})]
      (is (:valid? result))
      (is (empty? (:errors result))))))

;; ---------------------------------------------------------------------------
;; 27. Approval gate validation
;; ---------------------------------------------------------------------------

(deftest invalid-approval-config
  (testing "non-map approval gate is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Deploy"
                              :approval "yes"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Verify" :type :shell :command "echo"}]}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-approval-config)))))

;; ---------------------------------------------------------------------------
;; 28. Warning: single-step stage
;; ---------------------------------------------------------------------------

(deftest warning-single-step-stage
  (testing "single-step stage generates a warning"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (has-rule? (:warnings result) :single-step-stage)))))

;; ---------------------------------------------------------------------------
;; 29. Warning: missing source
;; ---------------------------------------------------------------------------

(deftest warning-missing-source
  (testing "pipeline without source/triggers generates a warning"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]})]
      (is (:valid? result))
      (is (has-rule? (:warnings result) :missing-source)))))

;; ---------------------------------------------------------------------------
;; 30. Warning: duplicate env vars
;; ---------------------------------------------------------------------------

(deftest warning-duplicate-env-vars
  (testing "same env var in multiple steps of a stage generates a warning"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"
                                       :env {"PATH" "/usr/bin"}}
                                      {:step-name "Lint" :type :shell :command "echo"
                                       :env {"PATH" "/usr/local/bin"}}]}]
                    :source {:type :git}})]
      (is (:valid? result))
      (is (has-rule? (:warnings result) :duplicate-env-var)))))

;; ---------------------------------------------------------------------------
;; 31. lint-file: missing file
;; ---------------------------------------------------------------------------

(deftest lint-file-missing
  (testing "lint-file on non-existent file returns file-not-found error"
    (let [result (linter/lint-file "/tmp/does-not-exist-pipeline.edn")]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :file-not-found)))))

;; ---------------------------------------------------------------------------
;; 32. lint-file: unknown extension
;; ---------------------------------------------------------------------------

(deftest lint-file-unknown-extension
  (testing "lint-file on unknown extension returns unknown-format error"
    ;; Create a temp file with .txt extension
    (let [f (java.io.File/createTempFile "pipeline" ".txt")]
      (try
        (spit f "{}")
        (let [result (linter/lint-file (.getAbsolutePath f))]
          (is (not (:valid? result)))
          (is (has-rule? (:errors result) :unknown-format)))
        (finally
          (.delete f))))))

;; ---------------------------------------------------------------------------
;; 33. lint-content: EDN format
;; ---------------------------------------------------------------------------

(deftest lint-content-edn
  (testing "lint-content parses and lints EDN content"
    (let [content (pr-str {:description "test"
                           :stages [{:name "Build"
                                     :steps [{:name "Compile" :run "make build"}
                                             {:name "Lint" :run "make lint"}]}]})
          result (linter/lint-content content "edn")]
      (is (:valid? result))
      (is (= "edn" (get-in result [:info :format]))))))

;; ---------------------------------------------------------------------------
;; 34. lint-content: invalid EDN
;; ---------------------------------------------------------------------------

(deftest lint-content-invalid-edn
  (testing "lint-content with bad EDN returns parse error"
    (let [result (linter/lint-content "{{bad edn" "edn")]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :parse-error)))))

;; ---------------------------------------------------------------------------
;; 35. lint-content: YAML format
;; ---------------------------------------------------------------------------

(deftest lint-content-yaml
  (testing "lint-content parses and lints YAML content"
    (let [content "name: test\ndescription: A test\nstages:\n  - name: Build\n    steps:\n      - name: Compile\n        run: make build\n      - name: Lint\n        run: make lint\n"
          result (linter/lint-content content "yaml")]
      (is (:valid? result))
      (is (= "yaml" (get-in result [:info :format]))))))

;; ---------------------------------------------------------------------------
;; 36. Format detection in info
;; ---------------------------------------------------------------------------

(deftest format-in-info
  (testing "format is recorded in info"
    (let [result (linter/lint-pipeline valid-pipeline "edn")]
      (is (= "edn" (get-in result [:info :format]))))))

;; ---------------------------------------------------------------------------
;; 37. Invalid parameter type
;; ---------------------------------------------------------------------------

(deftest invalid-parameter-type
  (testing "parameter with invalid type is an error"
    (let [result (linter/lint-pipeline
                   {:stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :parameters [{:name "env" :type :dropdown}]})]
      (is (not (:valid? result)))
      (is (has-rule? (:errors result) :invalid-parameter-type)))))

;; ---------------------------------------------------------------------------
;; 38. Matrix valid config passes
;; ---------------------------------------------------------------------------

(deftest matrix-valid-config
  (testing "valid matrix config does not generate errors"
    (let [result (linter/lint-pipeline
                   {:description "test"
                    :stages [{:stage-name "Build"
                              :steps [{:step-name "Run" :type :shell :command "echo"}
                                      {:step-name "Lint" :type :shell :command "echo"}]}]
                    :matrix {:os ["linux" "mac"] :node ["16" "18"]}
                    :source {:type :git}})]
      (is (:valid? result))
      (is (true? (get-in result [:info :has-matrix?]))))))
