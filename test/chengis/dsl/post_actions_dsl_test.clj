(ns chengis.dsl.post-actions-dsl-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.dsl.core :as dsl]
            [chengis.dsl.chengisfile :as cf]
            [clojure.java.io :as io]))

(use-fixtures :each (fn [f] (dsl/clear-registry!) (f)))

;; ---------------------------------------------------------------------------
;; DSL post-action functions
;; ---------------------------------------------------------------------------

(deftest always-fn-test
  (testing "always wraps steps in :always key"
    (let [s1 (dsl/step "Cleanup" (dsl/sh "rm -rf tmp"))
          result (dsl/always s1)]
      (is (map? result))
      (is (contains? result :always))
      (is (= 1 (count (:always result))))
      (is (= "Cleanup" (:step-name (first (:always result)))))))

  (testing "always flattens multiple steps"
    (let [result (dsl/always (dsl/step "A" (dsl/sh "echo a"))
                             (dsl/step "B" (dsl/sh "echo b")))]
      (is (= 2 (count (:always result)))))))

(deftest on-success-fn-test
  (testing "on-success wraps steps in :on-success key"
    (let [result (dsl/on-success (dsl/step "Notify" (dsl/sh "echo success")))]
      (is (map? result))
      (is (contains? result :on-success))
      (is (= "Notify" (:step-name (first (:on-success result))))))))

(deftest on-failure-fn-test
  (testing "on-failure wraps steps in :on-failure key"
    (let [result (dsl/on-failure (dsl/step "Alert" (dsl/sh "echo failed")))]
      (is (map? result))
      (is (contains? result :on-failure))
      (is (= "Alert" (:step-name (first (:on-failure result))))))))

(deftest post-fn-test
  (testing "post merges groups into :post-actions"
    (let [result (dsl/post
                   (dsl/always (dsl/step "A" (dsl/sh "echo a")))
                   (dsl/on-success (dsl/step "B" (dsl/sh "echo b")))
                   (dsl/on-failure (dsl/step "C" (dsl/sh "echo c"))))]
      (is (contains? result :post-actions))
      (let [pa (:post-actions result)]
        (is (contains? pa :always))
        (is (contains? pa :on-success))
        (is (contains? pa :on-failure))
        (is (= 1 (count (:always pa))))
        (is (= 1 (count (:on-success pa))))
        (is (= 1 (count (:on-failure pa))))))))

(deftest defpipeline-with-post-actions
  (testing "defpipeline filters post-actions from stages"
    (let [p (dsl/defpipeline test-with-post
              {:description "Pipeline with post actions"}
              (dsl/stage "Build"
                (dsl/step "Compile" (dsl/sh "echo compile")))
              (dsl/post
                (dsl/always (dsl/step "Cleanup" (dsl/sh "echo cleanup")))
                (dsl/on-failure (dsl/step "Alert" (dsl/sh "echo alert")))))]
      ;; Only "Build" stage, no post-action maps in stages
      (is (= 1 (count (:stages p))))
      (is (= "Build" (:stage-name (first (:stages p)))))
      ;; Post-actions present
      (is (some? (:post-actions p)))
      (is (contains? (:post-actions p) :always))
      (is (contains? (:post-actions p) :on-failure))
      ;; Registered
      (is (= p (dsl/get-pipeline "test-with-post"))))))

;; ---------------------------------------------------------------------------
;; Chengisfile post-action parsing
;; ---------------------------------------------------------------------------

(defn- write-temp-chengisfile
  "Write content to a temp Chengisfile and return the file path."
  [content]
  (let [dir (io/file (str "/tmp/chengis-test-post-" (System/nanoTime)))
        f   (io/file dir "Chengisfile")]
    (.mkdirs dir)
    (spit f content)
    {:dir  (.getAbsolutePath dir)
     :file (.getAbsolutePath f)}))

(deftest chengisfile-with-post-section
  (testing "parse Chengisfile with :post section"
    (let [content (pr-str {:description "Post pipeline"
                           :stages [{:name "Build"
                                     :steps [{:name "Compile" :run "make"}]}]
                           :post {:always [{:name "Cleanup" :run "rm -rf tmp"}]
                                  :on-failure [{:name "Alert" :run "echo FAIL"}]}})
          {:keys [file]} (write-temp-chengisfile content)
          result (cf/parse-chengisfile file)]
      (is (nil? (:error result)))
      (let [p (:pipeline result)]
        (is (some? (:post-actions p)))
        (is (= 1 (count (:always (:post-actions p)))))
        (is (= "Cleanup" (:step-name (first (:always (:post-actions p))))))
        (is (= 1 (count (:on-failure (:post-actions p)))))
        (is (= "Alert" (:step-name (first (:on-failure (:post-actions p))))))))))

(deftest chengisfile-validates-invalid-post
  (testing "invalid :post (not a map)"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "A" :run "echo a"}]}]
                    :post "not-a-map"})]
      (is (not (:valid? result)))
      (is (some #(re-find #":post must be a map" %) (:errors result)))))

  (testing "invalid :post step (missing :name)"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "A" :run "echo a"}]}]
                    :post {:always [{:run "echo cleanup"}]}})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :name" %) (:errors result)))))

  (testing "invalid :post step (missing :run)"
    (let [result (cf/validate-chengisfile
                   {:stages [{:name "Build"
                              :steps [{:name "A" :run "echo a"}]}]
                    :post {:on-success [{:name "Notify"}]}})]
      (is (not (:valid? result)))
      (is (some #(re-find #"missing :run" %) (:errors result))))))
