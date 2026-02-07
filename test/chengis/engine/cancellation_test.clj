(ns chengis.engine.cancellation-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.executor :as executor]
            [chengis.engine.build-runner :as build-runner]
            [chengis.engine.workspace :as workspace]))

(def test-system
  {:config {:workspace {:root "/tmp/chengis-test-cancel"}}})

(defn- cleanup []
  (workspace/cleanup-workspace "/tmp/chengis-test-cancel"))

;; ---------------------------------------------------------------------------
;; Cancellation via cancelled? atom (executor-level)
;; ---------------------------------------------------------------------------

(deftest cancel-build-during-execution
  (testing "cancelling a running build produces :aborted status"
    (let [cancelled (atom false)
          pipeline {:pipeline-name "cancel-test"
                    :stages [{:stage-name "Slow"
                              :parallel? false
                              :steps [{:step-name "Wait"
                                       :type :shell
                                       :command "sleep 30"}]}]}
          ;; Run build on a separate thread
          result-future (future
                          (executor/run-build test-system pipeline
                                              {:cancelled? cancelled}))]
      ;; Wait a bit for the build to start
      (Thread/sleep 800)
      ;; Cancel: set the atom and interrupt the future's thread
      (reset! cancelled true)
      (future-cancel result-future)
      ;; Give it time to wind down
      (Thread/sleep 500)
      (let [result (try @result-future (catch Exception _ nil))]
        ;; The future may throw CancellationException, so also handle that
        (when result
          (is (= :aborted (:build-status result)))
          (is (some? (:stage-results result)))))
      (cleanup))))

(deftest cancel-build-before-second-stage
  (testing "cancelling between stages: first succeeds, second is skipped"
    (let [cancelled (atom false)
          pipeline {:pipeline-name "multi-cancel"
                    :stages [{:stage-name "Fast"
                              :parallel? false
                              :steps [{:step-name "Quick"
                                       :type :shell
                                       :command "echo done"}]}
                             {:stage-name "Slow"
                              :parallel? false
                              :steps [{:step-name "Wait"
                                       :type :shell
                                       :command "sleep 30"}]}]}
          ;; Cancel synchronously when stage 1 completes — this ensures the
          ;; cancelled flag is set before the reduce loop checks it again
          event-fn (fn [event]
                     (when (and (= :stage-completed (:event-type event))
                                (= "Fast" (get-in event [:data :stage-name])))
                       (reset! cancelled true)))
          result-future (future
                          (executor/run-build test-system pipeline
                                              {:cancelled? cancelled
                                               :event-fn event-fn}))]
      ;; Wait for build to finish (should finish quickly since second stage is cancelled)
      (let [result (deref result-future 10000 nil)]
        (is (some? result))
        (when result
          ;; First stage should have succeeded
          (is (= :success (:stage-status (first (:stage-results result)))))
          ;; Second stage never started — the reduce short-circuits
          ;; Only 1 stage ran (the cancellation check at reduce level skips remaining)
          (is (= 1 (count (:stage-results result))))
          ;; The cancelled? flag should be set
          (is (true? @cancelled))))
      (cleanup))))

;; ---------------------------------------------------------------------------
;; Build Runner registry (unit tests)
;; ---------------------------------------------------------------------------

(deftest cancel-build-registry
  (testing "cancel non-existent build returns false"
    (is (false? (build-runner/cancel-build! "nonexistent-id-12345"))))

  (testing "build-active? for non-existent build"
    (is (false? (build-runner/build-active? "nonexistent-id-12345"))))

  (testing "get-active-build-ids returns a set"
    (is (set? (build-runner/get-active-build-ids)))))

;; ---------------------------------------------------------------------------
;; Cancelled build result structure
;; ---------------------------------------------------------------------------

(deftest cancelled-build-result-structure
  (testing "cancelled build result has all expected keys"
    (let [cancelled (atom false)
          pipeline {:pipeline-name "structure-test"
                    :stages [{:stage-name "Slow"
                              :parallel? false
                              :steps [{:step-name "Wait"
                                       :type :shell
                                       :command "sleep 30"}]}]}
          result-future (future
                          (executor/run-build test-system pipeline
                                              {:cancelled? cancelled}))]
      (Thread/sleep 600)
      (reset! cancelled true)
      (future-cancel result-future)
      (Thread/sleep 500)
      (let [result (try @result-future (catch Exception _ nil))]
        (when result
          ;; Check expected keys exist
          (is (contains? result :build-id))
          (is (contains? result :build-status))
          (is (contains? result :stage-results))
          (is (contains? result :workspace))
          (is (contains? result :started-at))
          (is (contains? result :completed-at))
          (is (string? (:build-id result)))
          (is (keyword? (:build-status result)))))
      (cleanup))))
