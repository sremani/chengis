(ns chengis.web.views-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.web.views.components :as c]
            [chengis.test-helpers :as h]))

;; ---------------------------------------------------------------------------
;; status-badge
;; ---------------------------------------------------------------------------

(deftest status-badge-all-statuses
  (testing "renders a badge for each known status"
    (doseq [[status label] [[:success "Success"]
                             [:failure "Failure"]
                             [:running "Running"]
                             [:queued "Queued"]
                             [:pending "Pending"]
                             [:skipped "Skipped"]
                             [:aborted "Aborted"]]]
      (let [result (c/status-badge status)]
        (is (vector? result) (str "Badge for " status " should be a vector"))
        (is (= :span (first result)) (str "Badge for " status " should be a span"))
        (is (h/hiccup-contains? result label)
            (str "Badge for " status " should contain label '" label "'"))))))

;; ---------------------------------------------------------------------------
;; pipeline-graph
;; ---------------------------------------------------------------------------

(deftest pipeline-graph-basic
  (testing "renders stages with stage names and pipeline-viz id"
    (let [stages [{:stage-name "Build" :parallel? false
                   :steps [{:step-name "Compile"}]}
                  {:stage-name "Test" :parallel? false
                   :steps [{:step-name "Unit"} {:step-name "Lint"}]}
                  {:stage-name "Deploy" :parallel? false
                   :steps [{:step-name "Ship"}]}]
          result (c/pipeline-graph stages)]
      (is (vector? result))
      (is (h/hiccup-contains? result "pipeline-viz"))
      (is (h/hiccup-contains? result "Build"))
      (is (h/hiccup-contains? result "Test"))
      (is (h/hiccup-contains? result "Deploy"))
      (is (h/hiccup-contains? result "Compile"))
      (is (h/hiccup-contains? result "Unit"))
      (is (h/hiccup-contains? result "Pipeline")))))

(deftest pipeline-graph-with-status
  (testing "status data adds color classes to stage nodes"
    (let [stages [{:stage-name "Build" :parallel? false :steps [{:step-name "A"}]}
                  {:stage-name "Test" :parallel? false :steps [{:step-name "B"}]}
                  {:stage-name "Deploy" :parallel? false :steps [{:step-name "C"}]}]
          result (c/pipeline-graph stages
                   {:stage-results [{:stage-name "Build" :status :success}
                                    {:stage-name "Test" :status :failure}
                                    {:stage-name "Deploy" :status :running}]})]
      ;; Success stage has green border
      (is (h/hiccup-find-class result "border-green-400"))
      ;; Failure stage has red border
      (is (h/hiccup-find-class result "border-red-400"))
      ;; Running stage has blue border
      (is (h/hiccup-find-class result "border-blue-400")))))

(deftest pipeline-stage-node-parallel-badge
  (testing "parallel stage shows P badge"
    (let [stages [{:stage-name "Test" :parallel? true
                   :steps [{:step-name "Unit"} {:step-name "Lint"}]}]
          result (c/pipeline-graph stages)]
      (is (h/hiccup-contains? result "P")))))

(deftest pipeline-stage-node-post-badge
  (testing "post-action stage shows post badge"
    (let [stages [{:stage-name "post:always" :parallel? false
                   :steps [{:step-name "Cleanup"}]}]
          result (c/pipeline-graph stages)]
      (is (h/hiccup-contains? result "post")))))

;; ---------------------------------------------------------------------------
;; build-stats-row
;; ---------------------------------------------------------------------------

(deftest build-stats-row-rendering
  (testing "renders stats cards with correct values and green color"
    (let [stats {:total 10 :success 8 :failure 1 :aborted 1 :success-rate 0.8}
          result (c/build-stats-row stats)]
      (is (vector? result))
      ;; Values present
      (is (h/hiccup-contains? result "80%"))
      (is (h/hiccup-contains? result "10"))
      (is (h/hiccup-contains? result "8"))
      ;; Labels present
      (is (h/hiccup-contains? result "Success Rate"))
      (is (h/hiccup-contains? result "Total Builds"))
      (is (h/hiccup-contains? result "Successful"))
      (is (h/hiccup-contains? result "Failed"))
      (is (h/hiccup-contains? result "Aborted"))
      ;; High success rate → green
      (is (h/hiccup-find-class result "text-green-600")))))

(deftest build-stats-row-red-rate
  (testing "low success rate renders in red"
    (let [stats {:total 10 :success 3 :failure 6 :aborted 1 :success-rate 0.3}
          result (c/build-stats-row stats)]
      (is (h/hiccup-contains? result "30%"))
      (is (h/hiccup-find-class result "text-red-600")))))

;; ---------------------------------------------------------------------------
;; build-history-chart
;; ---------------------------------------------------------------------------

(deftest build-history-chart-rendering
  (testing "renders bar chart with colored bars and legend"
    (let [builds [{:id "b1" :build-number 1 :status :success}
                  {:id "b2" :build-number 2 :status :failure}
                  {:id "b3" :build-number 3 :status :success}
                  {:id "b4" :build-number 4 :status :aborted}
                  {:id "b5" :build-number 5 :status :success}]
          result (c/build-history-chart builds)]
      (is (vector? result))
      ;; Contains build links
      (is (h/hiccup-contains? result "/builds/b1"))
      (is (h/hiccup-contains? result "/builds/b2"))
      ;; Contains status colors
      (is (h/hiccup-find-class result "bg-green-500"))
      (is (h/hiccup-find-class result "bg-red-500"))
      (is (h/hiccup-find-class result "bg-orange-500"))
      ;; Legend
      (is (h/hiccup-contains? result "Success"))
      (is (h/hiccup-contains? result "Failure"))
      (is (h/hiccup-contains? result "Aborted")))))

(deftest build-history-chart-empty
  (testing "returns nil when no builds"
    (is (nil? (c/build-history-chart [])))
    (is (nil? (c/build-history-chart nil)))))
