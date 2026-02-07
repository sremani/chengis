(ns chengis.engine.post-actions-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.executor :as executor]
            [chengis.engine.workspace :as workspace]))

(def test-system
  {:config {:workspace {:root "/tmp/chengis-test-post-actions"}}})

(defn- cleanup []
  (workspace/cleanup-workspace "/tmp/chengis-test-post-actions"))

;; Helper: build a pipeline data map directly (no DSL macro needed)
(defn- make-pipeline
  "Create a pipeline map with stages and optional post-actions."
  [{:keys [name stages post-actions]}]
  (cond-> {:pipeline-name (or name "test-pipeline")
           :stages stages}
    post-actions (assoc :post-actions post-actions)))

(defn- success-stage [stage-name]
  {:stage-name stage-name
   :parallel? false
   :steps [{:step-name "OK" :type :shell :command "echo ok"}]})

(defn- failure-stage [stage-name]
  {:stage-name stage-name
   :parallel? false
   :steps [{:step-name "Fail" :type :shell :command "exit 1"}]})

(defn- post-step [step-name cmd]
  {:step-name step-name :type :shell :command cmd})

;; ---------------------------------------------------------------------------
;; Post-action tests
;; ---------------------------------------------------------------------------

(deftest post-always-runs-on-success
  (testing ":always post-action runs when build succeeds"
    (let [pipeline (make-pipeline
                     {:stages [(success-stage "Build")]
                      :post-actions {:always [(post-step "Cleanup" "echo cleanup")]}})
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      ;; Stage results should include "post:always"
      (let [stage-names (mapv :stage-name (:stage-results result))]
        (is (some #{"post:always"} stage-names)))
      ;; The post:always stage should be successful
      (let [post-stage (first (filter #(= "post:always" (:stage-name %))
                                       (:stage-results result)))]
        (is (= :success (:stage-status post-stage))))
      (cleanup))))

(deftest post-always-runs-on-failure
  (testing ":always post-action runs even when build fails"
    (let [pipeline (make-pipeline
                     {:stages [(failure-stage "Build")]
                      :post-actions {:always [(post-step "Cleanup" "echo cleanup")]}})
          result (executor/run-build test-system pipeline {})]
      (is (= :failure (:build-status result)))
      ;; post:always should still appear
      (let [stage-names (mapv :stage-name (:stage-results result))]
        (is (some #{"post:always"} stage-names)))
      (cleanup))))

(deftest post-on-success-runs-only-on-success
  (testing ":on-success runs, :on-failure does not, when build succeeds"
    (let [pipeline (make-pipeline
                     {:stages [(success-stage "Build")]
                      :post-actions {:on-success [(post-step "Notify" "echo success")]
                                     :on-failure [(post-step "Alert" "echo failure")]}})
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (let [stage-names (set (mapv :stage-name (:stage-results result)))]
        (is (contains? stage-names "post:on-success"))
        (is (not (contains? stage-names "post:on-failure"))))
      (cleanup))))

(deftest post-on-failure-runs-only-on-failure
  (testing ":on-failure runs, :on-success does not, when build fails"
    (let [pipeline (make-pipeline
                     {:stages [(failure-stage "Build")]
                      :post-actions {:on-success [(post-step "Notify" "echo success")]
                                     :on-failure [(post-step "Alert" "echo failure")]}})
          result (executor/run-build test-system pipeline {})]
      (is (= :failure (:build-status result)))
      (let [stage-names (set (mapv :stage-name (:stage-results result)))]
        (is (contains? stage-names "post:on-failure"))
        (is (not (contains? stage-names "post:on-success"))))
      (cleanup))))

(deftest post-action-failure-does-not-change-build-status
  (testing "post-action failure does not affect the build status"
    (let [pipeline (make-pipeline
                     {:stages [(success-stage "Build")]
                      :post-actions {:always [(post-step "Bad Cleanup" "exit 1")]}})
          result (executor/run-build test-system pipeline {})]
      ;; Build status should still be :success even though post-action failed
      (is (= :success (:build-status result)))
      ;; The post:always stage itself should be :failure
      (let [post-stage (first (filter #(= "post:always" (:stage-name %))
                                       (:stage-results result)))]
        (is (= :failure (:stage-status post-stage))))
      (cleanup))))

(deftest no-post-actions-is-fine
  (testing "pipeline without post-actions runs normally"
    (let [pipeline (make-pipeline
                     {:stages [(success-stage "Build")]})
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (is (= 1 (count (:stage-results result))))
      (cleanup))))

(deftest all-three-post-groups-on-success
  (testing ":always and :on-success run; :on-failure does not"
    (let [pipeline (make-pipeline
                     {:stages [(success-stage "Build")]
                      :post-actions {:always [(post-step "Always" "echo always")]
                                     :on-success [(post-step "OK" "echo ok")]
                                     :on-failure [(post-step "Bad" "echo bad")]}})
          result (executor/run-build test-system pipeline {})]
      (is (= :success (:build-status result)))
      (let [stage-names (set (mapv :stage-name (:stage-results result)))]
        (is (contains? stage-names "post:always"))
        (is (contains? stage-names "post:on-success"))
        (is (not (contains? stage-names "post:on-failure"))))
      (cleanup))))
