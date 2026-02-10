(ns chengis.web.views.pipeline-viz-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.web.views.pipeline-viz :as viz]
            [chengis.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; compute-dag-layout
;; ---------------------------------------------------------------------------

(deftest compute-dag-layout-empty
  (testing "empty stages returns empty layout"
    (is (= [] (viz/compute-dag-layout [])))))

(deftest compute-dag-layout-single-stage
  (testing "single stage goes in column 0"
    (let [stages [{:stage-name "Build" :steps [{:step-name "compile"}]}]
          layout (viz/compute-dag-layout stages)]
      (is (= 1 (count layout)))
      (is (= 0 (:column (first layout))))
      (is (= 1 (count (:stages (first layout)))))
      (is (= "Build" (:stage-name (first (:stages (first layout)))))))))

(deftest compute-dag-layout-sequential-no-deps
  (testing "sequential pipeline (no deps) puts each stage in its own column"
    (let [stages [{:stage-name "Build" :steps []}
                  {:stage-name "Test" :steps []}
                  {:stage-name "Deploy" :steps []}]
          layout (viz/compute-dag-layout stages)]
      (is (= 3 (count layout)))
      (is (= [0 1 2] (mapv :column layout)))
      ;; Each column has exactly one stage
      (is (every? #(= 1 (count (:stages %))) layout))
      ;; Stages are in order
      (is (= ["Build" "Test" "Deploy"]
             (mapv #(:stage-name (first (:stages %))) layout))))))

(deftest compute-dag-layout-parallel-stages
  (testing "parallel stages (same depth) end up in the same column"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["A"] :steps []}]
          layout (viz/compute-dag-layout stages)]
      ;; 2 columns: col 0 = A, col 1 = B and C
      (is (= 2 (count layout)))
      (is (= 1 (count (:stages (first layout)))))
      (is (= "A" (:stage-name (first (:stages (first layout))))))
      ;; Column 1 has B and C
      (is (= 2 (count (:stages (second layout)))))
      (is (= #{"B" "C"}
             (set (map :stage-name (:stages (second layout)))))))))

(deftest compute-dag-layout-diamond-pattern
  (testing "diamond pattern (A->B, A->C, B->D, C->D) gives 3 columns"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["A"] :steps []}
                  {:stage-name "D" :depends-on ["B" "C"] :steps []}]
          layout (viz/compute-dag-layout stages)]
      ;; 3 columns: col 0 = A, col 1 = B,C, col 2 = D
      (is (= 3 (count layout)))
      (is (= [0 1 2] (mapv :column layout)))
      ;; A in first column
      (is (= #{"A"} (set (map :stage-name (:stages (nth layout 0))))))
      ;; B, C in second column
      (is (= #{"B" "C"} (set (map :stage-name (:stages (nth layout 1))))))
      ;; D in third column
      (is (= #{"D"} (set (map :stage-name (:stages (nth layout 2)))))))))

(deftest compute-dag-layout-complex-multi-level
  (testing "complex multi-level DAG: A->B->D, A->C->D, D->E"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["A"] :steps []}
                  {:stage-name "D" :depends-on ["B" "C"] :steps []}
                  {:stage-name "E" :depends-on ["D"] :steps []}]
          layout (viz/compute-dag-layout stages)]
      ;; 4 columns: A(0), B+C(1), D(2), E(3)
      (is (= 4 (count layout)))
      (is (= #{"A"} (set (map :stage-name (:stages (nth layout 0))))))
      (is (= #{"B" "C"} (set (map :stage-name (:stages (nth layout 1))))))
      (is (= #{"D"} (set (map :stage-name (:stages (nth layout 2))))))
      (is (= #{"E"} (set (map :stage-name (:stages (nth layout 3)))))))))

(deftest compute-dag-layout-linear-with-deps
  (testing "linear chain with explicit deps (A->B->C) gets 3 columns"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["B"] :steps []}]
          layout (viz/compute-dag-layout stages)]
      (is (= 3 (count layout)))
      (is (= #{"A"} (set (map :stage-name (:stages (nth layout 0))))))
      (is (= #{"B"} (set (map :stage-name (:stages (nth layout 1))))))
      (is (= #{"C"} (set (map :stage-name (:stages (nth layout 2)))))))))

;; ---------------------------------------------------------------------------
;; render-dag-pipeline
;; ---------------------------------------------------------------------------

(deftest render-dag-pipeline-empty-stages
  (testing "empty stages returns placeholder message"
    (let [result (viz/render-dag-pipeline [])]
      (is (vector? result))
      (is (h/hiccup-contains? result "No pipeline stages defined.")))))

(deftest render-dag-pipeline-sequential
  (testing "renders valid Hiccup for sequential pipeline"
    (let [stages [{:stage-name "Build" :steps [{:step-name "compile"}]}
                  {:stage-name "Test" :steps [{:step-name "unit"}]}]
          result (viz/render-dag-pipeline stages)]
      (is (vector? result))
      (is (h/hiccup-contains? result "dag-pipeline-viz"))
      (is (h/hiccup-contains? result "Build"))
      (is (h/hiccup-contains? result "Test"))
      (is (h/hiccup-contains? result "compile"))
      (is (h/hiccup-contains? result "unit"))
      (is (h/hiccup-contains? result "Pipeline")))))

(deftest render-dag-pipeline-dag
  (testing "renders valid Hiccup for DAG pipeline with DAG badge"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps [{:step-name "lint"}]}
                  {:stage-name "C" :depends-on ["A"] :steps [{:step-name "test"}]}
                  {:stage-name "D" :depends-on ["B" "C"] :steps []}]
          result (viz/render-dag-pipeline stages)]
      (is (vector? result))
      (is (h/hiccup-contains? result "dag-pipeline-viz"))
      ;; DAG badge should be present
      (is (h/hiccup-contains? result "DAG"))
      ;; All stage names present
      (is (h/hiccup-contains? result "A"))
      (is (h/hiccup-contains? result "B"))
      (is (h/hiccup-contains? result "C"))
      (is (h/hiccup-contains? result "D"))
      ;; Steps present
      (is (h/hiccup-contains? result "lint"))
      (is (h/hiccup-contains? result "test"))
      ;; SVG arrows present (check for stroke attribute used in arrow paths)
      (is (h/hiccup-contains? result "#9CA3AF")))))

(deftest render-dag-pipeline-with-status-colors
  (testing "status results color nodes correctly"
    (let [stages [{:stage-name "Build" :steps [{:step-name "compile"}]}
                  {:stage-name "Test" :depends-on ["Build"] :steps [{:step-name "unit"}]}]
          result (viz/render-dag-pipeline stages
                   {:stage-results [{:stage-name "Build" :status :success}
                                    {:stage-name "Test" :status :failure}]})]
      ;; Success stage has green border
      (is (h/hiccup-find-class result "border-green-400"))
      ;; Failure stage has red border
      (is (h/hiccup-find-class result "border-red-400")))))

(deftest render-dag-pipeline-with-step-results
  (testing "step results show colored dots"
    (let [stages [{:stage-name "Build" :steps [{:step-name "compile"} {:step-name "lint"}]}]
          result (viz/render-dag-pipeline stages
                   {:step-results [{:stage-name "Build" :step-name "compile" :status :success}
                                   {:stage-name "Build" :step-name "lint" :status :failure}]})]
      ;; Should have green dot for success
      (is (h/hiccup-find-class result "bg-green-500"))
      ;; Should have red dot for failure
      (is (h/hiccup-find-class result "bg-red-500")))))

(deftest render-dag-pipeline-single-stage
  (testing "single stage pipeline renders correctly"
    (let [stages [{:stage-name "Only" :steps [{:step-name "run"}]}]
          result (viz/render-dag-pipeline stages)]
      (is (vector? result))
      (is (h/hiccup-contains? result "Only"))
      (is (h/hiccup-contains? result "run"))
      (is (h/hiccup-contains? result "Pipeline")))))

(deftest render-dag-pipeline-post-action-stages
  (testing "post-action stages get post badge"
    (let [stages [{:stage-name "Build" :steps []}
                  {:stage-name "post:cleanup" :depends-on ["Build"] :steps [{:step-name "clean"}]}]
          result (viz/render-dag-pipeline stages)]
      (is (h/hiccup-contains? result "post"))
      (is (h/hiccup-contains? result "post:cleanup")))))

(deftest render-dag-pipeline-matrix-stages
  (testing "pipeline with matrix-style stages renders"
    (let [stages [{:stage-name "Build [os=linux]" :steps [{:step-name "make"}]}
                  {:stage-name "Build [os=mac]" :steps [{:step-name "make"}]}
                  {:stage-name "Deploy" :depends-on ["Build [os=linux]" "Build [os=mac]"] :steps []}]
          result (viz/render-dag-pipeline stages)]
      (is (vector? result))
      (is (h/hiccup-contains? result "Build [os=linux]"))
      (is (h/hiccup-contains? result "Build [os=mac]"))
      (is (h/hiccup-contains? result "Deploy")))))

(deftest render-dag-pipeline-running-status
  (testing "running status produces animate-pulse class"
    (let [stages [{:stage-name "Build" :steps []}]
          result (viz/render-dag-pipeline stages
                   {:stage-results [{:stage-name "Build" :status :running}]})]
      (is (h/hiccup-find-class result "animate-pulse")))))

;; ---------------------------------------------------------------------------
;; render-pipeline-detail-page
;; ---------------------------------------------------------------------------

(deftest render-pipeline-detail-page-full
  (testing "renders full pipeline detail page with all sections"
    (let [job {:name "my-project"
               :pipeline {:description "A test pipeline"
                          :stages [{:stage-name "Build" :steps [{:step-name "compile"}]}
                                   {:stage-name "Test" :depends-on ["Build"]
                                    :steps [{:step-name "unit"} {:step-name "integration"}]}
                                   {:stage-name "Deploy" :depends-on ["Test"]
                                    :steps [{:step-name "ship"}]}]}}
          result (viz/render-pipeline-detail-page
                   {:job job
                    :pipeline (:pipeline job)
                    :csrf-token "test-token"
                    :user nil
                    :auth-enabled false})]
      ;; Should be a string (rendered HTML from layout/base-layout)
      (is (string? result))
      ;; Should contain the job name in breadcrumb
      (is (.contains ^String result "my-project"))
      ;; Should contain "Pipeline Visualization" heading
      (is (.contains ^String result "Pipeline Visualization"))
      ;; Should contain stage names
      (is (.contains ^String result "Build"))
      (is (.contains ^String result "Test"))
      (is (.contains ^String result "Deploy"))
      ;; Should contain "Back to Job" link
      (is (.contains ^String result "Back to Job"))
      ;; Should contain stage details table
      (is (.contains ^String result "Stage Details"))
      ;; Should contain pipeline description
      (is (.contains ^String result "A test pipeline")))))

(deftest render-pipeline-detail-page-empty-pipeline
  (testing "renders gracefully with no stages"
    (let [job {:name "empty-job"
               :pipeline {:stages []}}
          result (viz/render-pipeline-detail-page
                   {:job job
                    :pipeline (:pipeline job)
                    :csrf-token "token"
                    :user nil
                    :auth-enabled false})]
      (is (string? result))
      (is (.contains ^String result "empty-job"))
      (is (.contains ^String result "No pipeline stages defined.")))))

(deftest render-pipeline-detail-page-sequential-layout-label
  (testing "sequential pipeline shows 'Sequential' layout label"
    (let [job {:name "seq-job"
               :pipeline {:stages [{:stage-name "A" :steps []}
                                   {:stage-name "B" :steps []}]}}
          result (viz/render-pipeline-detail-page
                   {:job job
                    :pipeline (:pipeline job)
                    :csrf-token "token"
                    :user nil
                    :auth-enabled false})]
      (is (.contains ^String result "Sequential")))))

(deftest render-pipeline-detail-page-dag-layout-label
  (testing "DAG pipeline shows 'DAG (parallel paths)' layout label"
    (let [job {:name "dag-job"
               :pipeline {:stages [{:stage-name "A" :steps []}
                                   {:stage-name "B" :depends-on ["A"] :steps []}]}}
          result (viz/render-pipeline-detail-page
                   {:job job
                    :pipeline (:pipeline job)
                    :csrf-token "token"
                    :user nil
                    :auth-enabled false})]
      (is (.contains ^String result "DAG (parallel paths)")))))
