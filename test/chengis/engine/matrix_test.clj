(ns chengis.engine.matrix-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.matrix :as matrix]
            [chengis.dsl.core :as dsl]
            [chengis.dsl.chengisfile :as chengisfile]
            [chengis.dsl.yaml :as yaml-parser]
            [clojure.string :as str]))

(deftest expand-matrix-2x2-test
  (testing "2x2 matrix produces 4 combinations"
    (let [combos (matrix/expand-matrix {:os ["linux" "macos"]
                                        :jdk ["11" "17"]})]
      (is (= 4 (count combos)))
      (is (some #(= {:os "linux" :jdk "11"} %) combos))
      (is (some #(= {:os "linux" :jdk "17"} %) combos))
      (is (some #(= {:os "macos" :jdk "11"} %) combos))
      (is (some #(= {:os "macos" :jdk "17"} %) combos)))))

(deftest expand-matrix-3x3-test
  (testing "3x3 matrix produces 9 combinations"
    (let [combos (matrix/expand-matrix {:os ["linux" "macos" "windows"]
                                        :jdk ["11" "17" "21"]})]
      (is (= 9 (count combos))))))

(deftest expand-matrix-single-dimension-test
  (testing "single dimension matrix produces N combinations"
    (let [combos (matrix/expand-matrix {:jdk ["11" "17" "21"]})]
      (is (= 3 (count combos)))
      (is (some #(= {:jdk "11"} %) combos))
      (is (some #(= {:jdk "17"} %) combos))
      (is (some #(= {:jdk "21"} %) combos)))))

(deftest expand-matrix-with-exclude-test
  (testing "exclude removes matching combinations"
    (let [combos (matrix/expand-matrix {:os ["linux" "macos"]
                                        :jdk ["11" "17"]
                                        :exclude [{:os "macos" :jdk "11"}]})]
      (is (= 3 (count combos)))
      (is (not (some #(= {:os "macos" :jdk "11"} %) combos)))
      (is (some #(= {:os "linux" :jdk "11"} %) combos))
      (is (some #(= {:os "linux" :jdk "17"} %) combos))
      (is (some #(= {:os "macos" :jdk "17"} %) combos)))))

(deftest expand-matrix-multiple-excludes-test
  (testing "multiple exclude rules"
    (let [combos (matrix/expand-matrix {:os ["linux" "macos"]
                                        :jdk ["11" "17"]
                                        :exclude [{:os "macos" :jdk "11"}
                                                  {:os "linux" :jdk "17"}]})]
      (is (= 2 (count combos)))
      (is (some #(= {:os "linux" :jdk "11"} %) combos))
      (is (some #(= {:os "macos" :jdk "17"} %) combos)))))

(deftest max-combinations-limit-test
  (testing "exceeding max combinations throws exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"exceeding limit"
                          (matrix/expand-matrix {:a ["1" "2" "3"]
                                                 :b ["1" "2" "3"]
                                                 :c ["1" "2" "3"]
                                                 :d ["1" "2" "3"]}
                                                :max 10)))))

(deftest empty-matrix-test
  (testing "empty matrix (no dimensions) returns single empty combination"
    (let [combos (matrix/expand-matrix {})]
      (is (= 1 (count combos)))
      (is (= {} (first combos))))))

(deftest matrix-label-test
  (testing "generates readable label"
    (is (= "jdk=11, os=linux"
           (matrix/matrix-label {:os "linux" :jdk "11"})))
    (is (= "jdk=17"
           (matrix/matrix-label {:jdk "17"})))))

(deftest matrix-env-test
  (testing "generates MATRIX_* environment variables"
    (let [env (matrix/matrix-env {:os "linux" :jdk "11"})]
      (is (= "linux" (get env "MATRIX_OS")))
      (is (= "11" (get env "MATRIX_JDK")))
      (is (= 2 (count env)))))

  (testing "handles hyphenated dimension names"
    (let [env (matrix/matrix-env {:node-version "18"})]
      (is (= "18" (get env "MATRIX_NODE_VERSION"))))))

(deftest expand-stages-naming-test
  (testing "expanded stages get suffixed names"
    (let [stages [{:stage-name "Build"
                   :parallel? false
                   :steps [{:step-name "Compile" :type :shell :command "make"}]}]
          expanded (matrix/expand-stages stages
                     {:os ["linux" "macos"] :jdk ["11" "17"]})]
      (is (= 4 (count expanded)))
      ;; All stage names contain the original + matrix label
      (is (every? #(str/starts-with? (:stage-name %) "Build [") expanded))
      (is (some #(str/includes? (:stage-name %) "os=linux") expanded))
      (is (some #(str/includes? (:stage-name %) "os=macos") expanded))
      (is (some #(str/includes? (:stage-name %) "jdk=11") expanded))
      (is (some #(str/includes? (:stage-name %) "jdk=17") expanded)))))

(deftest expand-stages-env-injection-test
  (testing "MATRIX_* env vars are injected into each step"
    (let [stages [{:stage-name "Test"
                   :parallel? false
                   :steps [{:step-name "Run" :type :shell :command "test"
                            :env {"CI" "true"}}]}]
          expanded (matrix/expand-stages stages {:jdk ["11" "17"]})]
      (is (= 2 (count expanded)))
      ;; Each expanded stage's step should have MATRIX_JDK env var
      (doseq [stage expanded]
        (let [step (first (:steps stage))
              env (:env step)]
          (is (contains? env "MATRIX_JDK"))
          ;; Existing env vars preserved
          (is (= "true" (get env "CI"))))))))

(deftest expand-stages-passthrough-test
  (testing "nil matrix config returns stages unchanged"
    (let [stages [{:stage-name "Build" :steps []}]
          result (matrix/expand-stages stages nil)]
      (is (= stages result))))

  (testing "empty matrix config returns stages unchanged"
    (let [stages [{:stage-name "Build" :steps []}]
          result (matrix/expand-stages stages {})]
      (is (= stages result)))))

(deftest expand-stages-preserves-matrix-combination-test
  (testing "expanded stages have :matrix-combination metadata"
    (let [stages [{:stage-name "Build" :steps []}]
          expanded (matrix/expand-stages stages {:os ["linux" "macos"]})]
      (is (= 2 (count expanded)))
      (is (some #(= {:os "linux"} (:matrix-combination %)) expanded))
      (is (some #(= {:os "macos"} (:matrix-combination %)) expanded)))))

;; --- Integration tests with DSL parsers ---

(deftest dsl-matrix-integration-test
  (testing "build-pipeline includes matrix from DSL"
    (let [pipeline (dsl/build-pipeline 'test-app {}
                     [(dsl/matrix {:os ["linux" "macos"] :jdk ["11" "17"]})
                      (dsl/stage "Build"
                        (dsl/step "Compile" (dsl/sh "make")))])]
      (is (= {:os ["linux" "macos"] :jdk ["11" "17"]}
             (:matrix pipeline)))
      (is (= 1 (count (:stages pipeline)))))))

(deftest dsl-matrix-with-exclude-integration-test
  (testing "build-pipeline includes matrix with exclude from DSL"
    (let [pipeline (dsl/build-pipeline 'test-app {}
                     [(dsl/matrix {:os ["linux" "macos"] :jdk ["11" "17"]}
                                  :exclude [{:os "macos" :jdk "11"}])
                      (dsl/stage "Build"
                        (dsl/step "Compile" (dsl/sh "make")))])]
      (is (= {:os ["linux" "macos"]
              :jdk ["11" "17"]
              :exclude [{:os "macos" :jdk "11"}]}
             (:matrix pipeline))))))

(deftest chengisfile-matrix-integration-test
  (testing "Chengisfile with :matrix is parsed correctly"
    (let [tmp (java.io.File/createTempFile "chengisfile-matrix" ".edn")]
      (try
        (spit tmp (pr-str {:stages [{:name "Build"
                                     :steps [{:name "Compile" :run "make"}]}]
                           :matrix {:os ["linux" "macos"]
                                    :jdk ["11" "17"]}}))
        (let [result (chengisfile/parse-chengisfile (.getAbsolutePath tmp))
              pipeline (:pipeline result)]
          (is (some? pipeline))
          (is (= {:os ["linux" "macos"] :jdk ["11" "17"]}
                 (:matrix pipeline))))
        (finally (.delete tmp))))))

(deftest yaml-matrix-integration-test
  (testing "YAML with strategy.matrix is parsed correctly"
    (let [result (yaml-parser/convert-yaml-to-pipeline
                   "stages:\n  - name: Build\n    steps:\n      - name: Compile\n        run: make\nstrategy:\n  matrix:\n    os:\n      - linux\n      - macos\n    jdk:\n      - 11\n      - 17\n")
          pipeline (:pipeline result)]
      (is (some? pipeline))
      (is (some? (:matrix pipeline)))
      (is (contains? (:matrix pipeline) :os))
      (is (contains? (:matrix pipeline) :jdk))
      ;; Values should be string vectors
      (is (= 2 (count (:os (:matrix pipeline)))))
      (is (= 2 (count (:jdk (:matrix pipeline)))))))

  (testing "YAML with top-level matrix is parsed correctly"
    (let [result (yaml-parser/convert-yaml-to-pipeline
                   "stages:\n  - name: Build\n    steps:\n      - name: Compile\n        run: make\nmatrix:\n  os:\n    - linux\n    - macos\n")
          pipeline (:pipeline result)]
      (is (some? pipeline))
      (is (some? (:matrix pipeline)))
      (is (contains? (:matrix pipeline) :os)))))

(deftest full-pipeline-expand-test
  (testing "full pipeline flow: parse → expand → verify stage count"
    (let [pipeline {:stages [{:stage-name "Build"
                              :parallel? false
                              :steps [{:step-name "Compile" :type :shell :command "make"}]}
                             {:stage-name "Test"
                              :parallel? false
                              :steps [{:step-name "Unit" :type :shell :command "test"}]}]
                    :matrix {:os ["linux" "macos"]}}
          expanded (matrix/expand-stages (:stages pipeline) (:matrix pipeline))]
      ;; 2 stages × 2 OS = 4 expanded stages
      (is (= 4 (count expanded)))
      ;; Each original stage appears twice
      (is (= 2 (count (filter #(str/starts-with? (:stage-name %) "Build") expanded))))
      (is (= 2 (count (filter #(str/starts-with? (:stage-name %) "Test") expanded)))))))
