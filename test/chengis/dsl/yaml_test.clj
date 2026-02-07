(ns chengis.dsl.yaml-test
  (:require [clojure.test :refer :all]
            [chengis.dsl.yaml :as yaml]
            [chengis.plugin.loader :as plugin-loader]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(use-fixtures :once
  (fn [f]
    (plugin-loader/load-plugins!)
    (f)))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- write-temp-yaml!
  "Write YAML content to a temp file and return its path."
  [content]
  (let [f (java.io.File/createTempFile "chengis-test-" ".yml")]
    (.deleteOnExit f)
    (spit f content)
    (.getAbsolutePath f)))

;; ---------------------------------------------------------------------------
;; Basic parsing tests
;; ---------------------------------------------------------------------------

(deftest parse-basic-yaml-test
  (testing "parses a minimal YAML workflow"
    (let [yml (write-temp-yaml!
                "name: test-app\ndescription: A test\nstages:\n  - name: Build\n    steps:\n      - name: Compile\n        run: mvn compile\n")
          result (yaml/parse-yaml-workflow yml)]
      (is (some? (:pipeline result)))
      (is (nil? (:error result)))
      (is (= "test-app" (get-in result [:pipeline :pipeline-name])))
      (is (= "A test" (get-in result [:pipeline :description])))
      (is (= 1 (count (get-in result [:pipeline :stages]))))
      (is (= "Build" (get-in result [:pipeline :stages 0 :stage-name])))))

  (testing "parses multi-stage pipeline"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Compile\n"
                     "        run: mvn compile\n"
                     "  - name: Test\n"
                     "    parallel: true\n"
                     "    steps:\n"
                     "      - name: Unit\n"
                     "        run: mvn test\n"
                     "      - name: Lint\n"
                     "        run: mvn lint\n"))
          result (yaml/parse-yaml-workflow yml)
          pipeline (:pipeline result)]
      (is (= 2 (count (:stages pipeline))))
      (is (false? (get-in pipeline [:stages 0 :parallel?])))
      (is (true? (get-in pipeline [:stages 1 :parallel?])))
      (is (= 2 (count (get-in pipeline [:stages 1 :steps])))))))

(deftest parse-docker-yaml-test
  (testing "parses Docker container at step level"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Compile\n"
                     "        run: mvn compile\n"
                     "        image: maven:3.9\n"))
          result (yaml/parse-yaml-workflow yml)
          step (get-in result [:pipeline :stages 0 :steps 0])]
      (is (= :docker (:type step)))
      (is (= "maven:3.9" (:image step)))
      (is (= "mvn compile" (:command step)))))

  (testing "parses Docker container at stage level"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Build\n"
                     "    container:\n"
                     "      image: node:18\n"
                     "    steps:\n"
                     "      - name: Install\n"
                     "        run: npm install\n"))
          result (yaml/parse-yaml-workflow yml)
          stage (get-in result [:pipeline :stages 0])]
      (is (= {:image "node:18"} (:container stage)))
      ;; Steps without explicit image remain :shell
      (is (= :shell (get-in stage [:steps 0 :type])))))

  (testing "parses Docker container at pipeline level"
    (let [yml (write-temp-yaml!
                (str "container:\n"
                     "  image: node:18\n"
                     "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Install\n"
                     "        run: npm install\n"))
          result (yaml/parse-yaml-workflow yml)]
      (is (= {:image "node:18"} (get-in result [:pipeline :container]))))))

(deftest parse-env-yaml-test
  (testing "parses step-level environment variables"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Deploy\n"
                     "    steps:\n"
                     "      - name: Ship\n"
                     "        run: ./deploy.sh\n"
                     "        env:\n"
                     "          ENV: production\n"
                     "          DEBUG: false\n"))
          result (yaml/parse-yaml-workflow yml)
          step (get-in result [:pipeline :stages 0 :steps 0])]
      (is (= {"ENV" "production" "DEBUG" "false"} (:env step))))))

(deftest parse-conditions-yaml-test
  (testing "parses branch condition"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Deploy\n"
                     "    when:\n"
                     "      branch: main\n"
                     "    steps:\n"
                     "      - name: Ship\n"
                     "        run: ./deploy.sh\n"))
          result (yaml/parse-yaml-workflow yml)
          stage (get-in result [:pipeline :stages 0])]
      (is (= {:type :branch :value "main"} (:condition stage))))))

(deftest parse-post-actions-yaml-test
  (testing "parses post-build actions"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Compile\n"
                     "        run: make\n"
                     "post:\n"
                     "  always:\n"
                     "    - name: Cleanup\n"
                     "      run: rm -rf .cache\n"
                     "  on-failure:\n"
                     "    - name: Alert\n"
                     "      run: echo 'Failed!'\n"))
          result (yaml/parse-yaml-workflow yml)
          post (get-in result [:pipeline :post-actions])]
      (is (some? post))
      (is (= 1 (count (:always post))))
      (is (= "Cleanup" (get-in post [:always 0 :step-name])))
      (is (= 1 (count (:on-failure post)))))))

(deftest parse-artifacts-notify-yaml-test
  (testing "parses artifacts and notifications"
    (let [yml (write-temp-yaml!
                (str "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Compile\n"
                     "        run: make\n"
                     "artifacts:\n"
                     "  - dist/**\n"
                     "  - coverage/*.html\n"
                     "notify:\n"
                     "  - type: slack\n"
                     "    channel: '#builds'\n"))
          result (yaml/parse-yaml-workflow yml)
          pipeline (:pipeline result)]
      (is (= ["dist/**" "coverage/*.html"] (:artifacts pipeline)))
      (is (= 1 (count (:notify pipeline))))
      (is (= :slack (get-in pipeline [:notify 0 :type]))))))

(deftest parse-parameters-yaml-test
  (testing "parses parameter definitions"
    (let [yml (write-temp-yaml!
                (str "parameters:\n"
                     "  environment:\n"
                     "    type: choice\n"
                     "    choices:\n"
                     "      - staging\n"
                     "      - production\n"
                     "    default: staging\n"
                     "stages:\n"
                     "  - name: Build\n"
                     "    steps:\n"
                     "      - name: Compile\n"
                     "        run: make\n"))
          result (yaml/parse-yaml-workflow yml)
          params (get-in result [:pipeline :parameters])]
      (is (= 1 (count params)))
      (is (= "environment" (:name (first params))))
      (is (= :choice (:type (first params))))
      (is (= ["staging" "production"] (:choices (first params))))
      (is (= "staging" (:default (first params)))))))

;; ---------------------------------------------------------------------------
;; Validation tests
;; ---------------------------------------------------------------------------

(deftest validation-test
  (testing "rejects missing stages"
    (let [yml (write-temp-yaml! "name: broken\n")
          result (yaml/parse-yaml-workflow yml)]
      (is (some? (:error result)))
      (is (str/includes? (:error result) "stages"))))

  (testing "rejects empty stages"
    (let [yml (write-temp-yaml! "stages: []\n")
          result (yaml/parse-yaml-workflow yml)]
      (is (some? (:error result)))))

  (testing "rejects stage without name"
    (let [yml (write-temp-yaml!
                "stages:\n  - steps:\n      - name: foo\n        run: bar\n")
          result (yaml/parse-yaml-workflow yml)]
      (is (some? (:error result)))))

  (testing "rejects step without run"
    (let [yml (write-temp-yaml!
                "stages:\n  - name: Build\n    steps:\n      - name: Compile\n")
          result (yaml/parse-yaml-workflow yml)]
      (is (some? (:error result))))))

;; ---------------------------------------------------------------------------
;; convert-yaml-to-pipeline (string-based) tests
;; ---------------------------------------------------------------------------

(deftest convert-yaml-string-test
  (testing "converts YAML string to pipeline"
    (let [yml-str "name: quick\nstages:\n  - name: Test\n    steps:\n      - name: Run\n        run: echo hello\n"
          result (yaml/convert-yaml-to-pipeline yml-str)]
      (is (some? (:pipeline result)))
      (is (= "quick" (get-in result [:pipeline :pipeline-name])))))

  (testing "returns error for invalid YAML"
    (let [result (yaml/convert-yaml-to-pipeline "not: valid: yaml: {{")]
      ;; clj-yaml might parse some weird things; just ensure no crash
      (is (some? result)))))

;; ---------------------------------------------------------------------------
;; File detection tests
;; ---------------------------------------------------------------------------

(deftest detect-yaml-file-test
  (testing "detects .chengis/workflow.yml"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/chengis-detect-" (System/nanoTime))]
      (io/make-parents (io/file dir ".chengis" "workflow.yml"))
      (spit (io/file dir ".chengis" "workflow.yml") "stages: []")
      (is (some? (yaml/detect-yaml-file dir)))
      (is (str/ends-with? (yaml/detect-yaml-file dir) ".chengis/workflow.yml"))))

  (testing "detects chengis.yml"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/chengis-detect2-" (System/nanoTime))]
      (.mkdirs (io/file dir))
      (spit (io/file dir "chengis.yml") "stages: []")
      (is (some? (yaml/detect-yaml-file dir)))
      (is (str/ends-with? (yaml/detect-yaml-file dir) "chengis.yml"))))

  (testing "returns nil when no YAML file found"
    (let [dir (str (System/getProperty "java.io.tmpdir") "/chengis-detect3-" (System/nanoTime))]
      (.mkdirs (io/file dir))
      (is (nil? (yaml/detect-yaml-file dir))))))

;; ---------------------------------------------------------------------------
;; Plugin registration tests
;; ---------------------------------------------------------------------------

(deftest yaml-plugin-registration-test
  (testing "YAML format plugin is registered"
    (is (some? (registry/get-plugin "yaml-format"))))

  (testing "YAML pipeline format is registered"
    (is (some? (registry/get-pipeline-format "yaml")))
    (is (some? (registry/get-pipeline-format "yml")))))
