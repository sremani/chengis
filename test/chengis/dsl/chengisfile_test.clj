(ns chengis.dsl.chengisfile-test
  (:require [clojure.test :refer :all]
            [chengis.dsl.chengisfile :as cf]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- write-temp-chengisfile
  "Write content to a temp Chengisfile and return the file path."
  [content]
  (let [dir (io/file (str "/tmp/chengis-test-cf-" (System/nanoTime)))
        f   (io/file dir "Chengisfile")]
    (.mkdirs dir)
    (spit f content)
    {:dir  (.getAbsolutePath dir)
     :file (.getAbsolutePath f)}))

;; ---------------------------------------------------------------------------
;; convert-condition
;; ---------------------------------------------------------------------------

(deftest convert-condition-test
  (testing "nil input returns nil"
    (is (nil? (cf/convert-condition nil))))

  (testing "branch condition"
    (is (= {:type :branch :value "main"}
           (cf/convert-condition {:branch "main"}))))

  (testing "param condition"
    (is (= {:type :param :param "deploy" :value "true"}
           (cf/convert-condition {:param "deploy" :value "true"}))))

  (testing "unknown keys return nil"
    (is (nil? (cf/convert-condition {:foo "bar"})))))

;; ---------------------------------------------------------------------------
;; convert-step
;; ---------------------------------------------------------------------------

(deftest convert-step-test
  (testing "minimal step"
    (is (= {:step-name "Compile"
            :type      :shell
            :command   "mvn compile"}
           (cf/convert-step {:name "Compile" :run "mvn compile"}))))

  (testing "step with env"
    (let [result (cf/convert-step {:name "Deploy" :run "./deploy.sh"
                                   :env {"ENV" "prod" "REGION" "us-east-1"}})]
      (is (= "Deploy" (:step-name result)))
      (is (= :shell (:type result)))
      (is (= "./deploy.sh" (:command result)))
      (is (= {"ENV" "prod" "REGION" "us-east-1"} (:env result)))))

  (testing "step with timeout"
    (let [result (cf/convert-step {:name "Slow" :run "sleep 100" :timeout 120000})]
      (is (= 120000 (:timeout result)))))

  (testing "step with dir"
    (let [result (cf/convert-step {:name "Sub" :run "make" :dir "subproject"})]
      (is (= "subproject" (:dir result)))))

  (testing "step with all options"
    (let [result (cf/convert-step {:name "Full" :run "cmd"
                                   :env {"A" "1"} :timeout 5000 :dir "/app"})]
      (is (= {:step-name "Full" :type :shell :command "cmd"
              :env {"A" "1"} :timeout 5000 :dir "/app"}
             result)))))

;; ---------------------------------------------------------------------------
;; convert-stage
;; ---------------------------------------------------------------------------

(deftest convert-stage-test
  (testing "sequential stage"
    (let [result (cf/convert-stage {:name "Build"
                                    :steps [{:name "A" :run "echo a"}
                                            {:name "B" :run "echo b"}]})]
      (is (= "Build" (:stage-name result)))
      (is (false? (:parallel? result)))
      (is (= 2 (count (:steps result))))
      (is (nil? (:condition result)))))

  (testing "parallel stage"
    (let [result (cf/convert-stage {:name "Test"
                                    :parallel true
                                    :steps [{:name "Unit" :run "test"}]})]
      (is (true? (:parallel? result)))))

  (testing "stage with branch condition"
    (let [result (cf/convert-stage {:name "Deploy"
                                    :when {:branch "main"}
                                    :steps [{:name "Ship" :run "deploy"}]})]
      (is (= {:type :branch :value "main"} (:condition result)))))

  (testing "stage with param condition"
    (let [result (cf/convert-stage {:name "Release"
                                    :when {:param "release" :value "true"}
                                    :steps [{:name "Tag" :run "tag"}]})]
      (is (= {:type :param :param "release" :value "true"} (:condition result))))))

;; ---------------------------------------------------------------------------
;; validate-chengisfile
;; ---------------------------------------------------------------------------

(deftest validate-chengisfile-test
  (testing "valid minimal file"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "Compile" :run "make"}]}]})]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "valid full file"
    (let [result (cf/validate-chengisfile
                   {:description "Full pipeline"
                    :stages [{:name "Build"
                              :steps [{:name "Compile" :run "make"
                                       :env {"CC" "gcc"} :timeout 30000}]}
                             {:name "Test"
                              :parallel true
                              :steps [{:name "Unit" :run "test"}
                                      {:name "Lint" :run "lint"}]}
                             {:name "Deploy"
                              :when {:branch "main"}
                              :steps [{:name "Ship" :run "deploy"}]}]})]
      (is (:valid? result))))

  (testing "missing :stages"
    (let [result (cf/validate-chengisfile {:description "No stages"})]
      (is (not (:valid? result)))
      (is (some #(re-find #"Missing required key :stages" %) (:errors result)))))

  (testing "empty :stages"
    (let [result (cf/validate-chengisfile {:stages []})]
      (is (not (:valid? result)))
      (is (some #(re-find #":stages must not be empty" %) (:errors result)))))

  (testing "stage missing :name"
    (let [result (cf/validate-chengisfile
                   {:stages [{:steps [{:name "A" :run "echo"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :name" %) (:errors result)))))

  (testing "stage missing :steps"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :steps" %) (:errors result)))))

  (testing "step missing :name"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:run "make"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :name" %) (:errors result)))))

  (testing "step missing :run"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "Compile"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :run" %) (:errors result)))))

  (testing "invalid :env type"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "A" :run "cmd" :env "not-a-map"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":env must be a map" %) (:errors result)))))

  (testing "invalid :timeout type"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "A" :run "cmd" :timeout "slow"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":timeout must be a positive integer" %) (:errors result)))))

  (testing "invalid :when clause"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :when {:foo "bar"}
                              :steps [{:name "A" :run "cmd"}]}]})]
      (is (not (:valid? result)))
      (is (some #(re-find #":when must have :branch or :param" %) (:errors result)))))

  (testing "not a map"
    (let [result (cf/validate-chengisfile "not a map")]
      (is (not (:valid? result))))))

;; ---------------------------------------------------------------------------
;; chengisfile-exists?
;; ---------------------------------------------------------------------------

(deftest chengisfile-exists-test
  (testing "exists when file is present"
    (let [{:keys [dir]} (write-temp-chengisfile "{:stages []}")]
      (is (cf/chengisfile-exists? dir))))

  (testing "does not exist for empty directory"
    (let [dir (str "/tmp/chengis-test-empty-" (System/nanoTime))]
      (.mkdirs (io/file dir))
      (is (not (cf/chengisfile-exists? dir))))))

;; ---------------------------------------------------------------------------
;; parse-chengisfile (integration)
;; ---------------------------------------------------------------------------

(deftest parse-chengisfile-test
  (testing "valid Chengisfile end-to-end"
    (let [content (pr-str {:description "Test pipeline"
                           :stages [{:name "Build"
                                     :steps [{:name "Compile" :run "make"}
                                             {:name "Link" :run "make link"
                                              :timeout 60000}]}
                                    {:name "Test"
                                     :parallel true
                                     :steps [{:name "Unit" :run "make test"}
                                             {:name "Lint" :run "make lint"}]}
                                    {:name "Deploy"
                                     :when {:branch "main"}
                                     :steps [{:name "Ship" :run "./deploy.sh"
                                              :env {"ENV" "prod"}}]}]})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:error result)))
      (is (some? (:pipeline result)))
      (let [p (:pipeline result)]
        ;; Description
        (is (= "Test pipeline" (:description p)))
        ;; 3 stages
        (is (= 3 (count (:stages p))))
        ;; Stage 1: Build (sequential, 2 steps)
        (let [s1 (first (:stages p))]
          (is (= "Build" (:stage-name s1)))
          (is (false? (:parallel? s1)))
          (is (nil? (:condition s1)))
          (is (= 2 (count (:steps s1))))
          (is (= {:step-name "Compile" :type :shell :command "make"}
                 (first (:steps s1))))
          (is (= 60000 (:timeout (second (:steps s1))))))
        ;; Stage 2: Test (parallel)
        (let [s2 (second (:stages p))]
          (is (= "Test" (:stage-name s2)))
          (is (true? (:parallel? s2)))
          (is (= 2 (count (:steps s2)))))
        ;; Stage 3: Deploy (condition)
        (let [s3 (nth (:stages p) 2)]
          (is (= "Deploy" (:stage-name s3)))
          (is (= {:type :branch :value "main"} (:condition s3)))
          (is (= {"ENV" "prod"} (:env (first (:steps s3)))))))))

  (testing "missing file"
    (let [result (cf/parse-chengisfile "/tmp/nonexistent-chengisfile-xyz")]
      (is (some? (:error result)))
      (is (re-find #"File not found" (:error result)))))

  (testing "invalid EDN syntax"
    (let [{:keys [file]} (write-temp-chengisfile "{:stages not-valid-edn !!}")
          result (cf/parse-chengisfile file)]
      (is (some? (:error result)))))

  (testing "valid EDN but invalid structure"
    (let [{:keys [file]} (write-temp-chengisfile "{:description \"no stages\"}")
          result (cf/parse-chengisfile file)]
      (is (some? (:error result)))
      (is (re-find #"Validation failed" (:error result)))))

  (testing "no :source in output — source comes from job"
    (let [content (pr-str {:stages [{:name "Build"
                                     :steps [{:name "A" :run "echo a"}]}]})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:source (:pipeline result)))))))
