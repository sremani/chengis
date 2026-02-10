(ns chengis.engine.build-compare-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.build-compare :as bc]))

;; --- Test helpers ---

(defn- make-build
  "Create a minimal build map for testing."
  [& {:keys [id status started-at completed-at build-number]
      :or {id "build-1" status :success build-number 1}}]
  {:id id :status status :build-number build-number
   :started-at started-at :completed-at completed-at})

(defn- make-stage
  "Create a minimal stage map."
  [name status & {:keys [started-at completed-at]}]
  {:stage-name name :status status
   :started-at started-at :completed-at completed-at})

(defn- make-step
  "Create a minimal step map."
  [stage-name step-name status & {:keys [exit-code started-at completed-at]}]
  {:stage-name stage-name :step-name step-name :status status
   :exit-code (or exit-code 0)
   :started-at started-at :completed-at completed-at})

;; --- Tests ---

(deftest compare-identical-builds
  (testing "Two identical builds produce no differences"
    (let [build (make-build :status :success
                            :started-at "2024-01-01T10:00:00Z"
                            :completed-at "2024-01-01T10:01:00Z")
          stages [(make-stage "Build" :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:01:00Z")]
          steps [(make-step "Build" "Compile" :success :exit-code 0
                            :started-at "2024-01-01T10:00:00Z"
                            :completed-at "2024-01-01T10:01:00Z")]
          result (bc/compare-builds build stages steps []
                                    build stages steps [])]
      (is (false? (get-in result [:summary :status-changed?])))
      (is (= 0 (get-in result [:summary :duration-delta-s])))
      (is (empty? (get-in result [:summary :stages-added])))
      (is (empty? (get-in result [:summary :stages-removed]))))))

(deftest compare-different-statuses
  (testing "Builds with different statuses set status-changed? true"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :failure)
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (true? (get-in result [:summary :status-changed?]))))))

(deftest duration-delta-computed-correctly
  (testing "Duration delta is build-b minus build-a"
    (let [build-a (make-build :id "a" :status :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:01:00Z")
          build-b (make-build :id "b" :status :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:02:00Z")
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (= 60 (get-in result [:summary :duration-a-s])))
      (is (= 120 (get-in result [:summary :duration-b-s])))
      (is (= 60 (get-in result [:summary :duration-delta-s]))))))

(deftest stage-added-in-build-b
  (testing "A stage in B but not A is detected as added"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Build" :success)]
          stages-b [(make-stage "Build" :success) (make-stage "Deploy" :success)]
          result (bc/compare-builds build-a stages-a [] [] build-b stages-b [] [])]
      (is (= ["Deploy"] (get-in result [:summary :stages-added])))
      (is (empty? (get-in result [:summary :stages-removed]))))))

(deftest stage-removed-in-build-b
  (testing "A stage in A but not B is detected as removed"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Build" :success) (make-stage "Test" :success)]
          stages-b [(make-stage "Build" :success)]
          result (bc/compare-builds build-a stages-a [] [] build-b stages-b [] [])]
      (is (= ["Test"] (get-in result [:summary :stages-removed])))
      (is (empty? (get-in result [:summary :stages-added]))))))

(deftest step-status-differences-detected
  (testing "Steps with different statuses are tracked"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :failure)
          stages-a [(make-stage "Build" :success)]
          stages-b [(make-stage "Build" :failure)]
          steps-a [(make-step "Build" "Compile" :success :exit-code 0)]
          steps-b [(make-step "Build" "Compile" :failure :exit-code 1)]
          result (bc/compare-builds build-a stages-a steps-a [] build-b stages-b steps-b [])
          stage-diff (first (:stages result))
          step-diff (first (:steps stage-diff))]
      (is (= :success (:status-a step-diff)))
      (is (= :failure (:status-b step-diff))))))

(deftest step-exit-code-differences-tracked
  (testing "Step exit codes from both builds are tracked"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :failure)
          stages-a [(make-stage "Build" :success)]
          stages-b [(make-stage "Build" :failure)]
          steps-a [(make-step "Build" "Compile" :success :exit-code 0)]
          steps-b [(make-step "Build" "Compile" :failure :exit-code 1)]
          result (bc/compare-builds build-a stages-a steps-a [] build-b stages-b steps-b [])
          step-diff (first (:steps (first (:stages result))))]
      (is (= 0 (:exit-code-a step-diff)))
      (is (= 1 (:exit-code-b step-diff))))))

(deftest duration-deltas-for-stages
  (testing "Duration deltas are computed for stages"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Build" :success
                                :started-at "2024-01-01T10:00:00Z"
                                :completed-at "2024-01-01T10:00:30Z")]
          stages-b [(make-stage "Build" :success
                                :started-at "2024-01-01T10:00:00Z"
                                :completed-at "2024-01-01T10:01:00Z")]
          result (bc/compare-builds build-a stages-a [] [] build-b stages-b [] [])
          stage-diff (first (:stages result))]
      (is (= 30 (:duration-a-s stage-diff)))
      (is (= 60 (:duration-b-s stage-diff)))
      (is (= 30 (:duration-delta-s stage-diff))))))

(deftest duration-deltas-for-steps
  (testing "Duration deltas are computed for steps"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Build" :success)]
          stages-b [(make-stage "Build" :success)]
          steps-a [(make-step "Build" "Compile" :success :exit-code 0
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:00:10Z")]
          steps-b [(make-step "Build" "Compile" :success :exit-code 0
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:00:15Z")]
          result (bc/compare-builds build-a stages-a steps-a [] build-b stages-b steps-b [])
          step-diff (first (:steps (first (:stages result))))]
      (is (= 10 (:duration-a-s step-diff)))
      (is (= 15 (:duration-b-s step-diff)))
      (is (= 5 (:duration-delta-s step-diff))))))

(deftest artifacts-only-in-a-detected
  (testing "Artifacts only in build A are detected"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          arts-a [{:filename "app.jar" :size-bytes 1024}
                  {:filename "report.html" :size-bytes 512}]
          arts-b [{:filename "app.jar" :size-bytes 1024}]
          result (bc/compare-builds build-a [] [] arts-a build-b [] [] arts-b)]
      (is (= ["report.html"] (get-in result [:artifacts :only-in-a]))))))

(deftest artifacts-only-in-b-detected
  (testing "Artifacts only in build B are detected"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          arts-a [{:filename "app.jar" :size-bytes 1024}]
          arts-b [{:filename "app.jar" :size-bytes 1024}
                  {:filename "new-file.txt" :size-bytes 256}]
          result (bc/compare-builds build-a [] [] arts-a build-b [] [] arts-b)]
      (is (= ["new-file.txt"] (get-in result [:artifacts :only-in-b]))))))

(deftest artifacts-in-both-with-size-changes
  (testing "Artifacts in both builds with size changes are detected"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          arts-a [{:filename "app.jar" :size-bytes 1024}]
          arts-b [{:filename "app.jar" :size-bytes 2048}]
          result (bc/compare-builds build-a [] [] arts-a build-b [] [] arts-b)
          changes (get-in result [:artifacts :size-changes])]
      (is (= 1 (count changes)))
      (is (= "app.jar" (:filename (first changes))))
      (is (= 1024 (:size-a (first changes))))
      (is (= 2048 (:size-b (first changes))))
      (is (= 1024 (:delta (first changes)))))))

(deftest nil-timestamps-handled-gracefully
  (testing "Nil timestamps result in nil durations, not exceptions"
    (let [build-a (make-build :id "a" :status :success
                              :started-at nil :completed-at nil)
          build-b (make-build :id "b" :status :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at nil)
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (nil? (get-in result [:summary :duration-a-s])))
      (is (nil? (get-in result [:summary :duration-b-s])))
      (is (nil? (get-in result [:summary :duration-delta-s]))))))

(deftest empty-stages-handled
  (testing "Empty stage lists don't cause errors"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (empty? (:stages result)))
      (is (empty? (get-in result [:summary :stages-added])))
      (is (empty? (get-in result [:summary :stages-removed]))))))

(deftest single-stage-pipeline-comparison
  (testing "Single stage pipeline comparison works correctly"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :failure)
          stages-a [(make-stage "Build" :success
                                :started-at "2024-01-01T10:00:00Z"
                                :completed-at "2024-01-01T10:01:00Z")]
          stages-b [(make-stage "Build" :failure
                                :started-at "2024-01-01T10:00:00Z"
                                :completed-at "2024-01-01T10:00:30Z")]
          result (bc/compare-builds build-a stages-a [] [] build-b stages-b [] [])
          stage (first (:stages result))]
      (is (= 1 (count (:stages result))))
      (is (= "Build" (:stage-name stage)))
      (is (= :success (:status-a stage)))
      (is (= :failure (:status-b stage)))
      (is (= -30 (:duration-delta-s stage))))))

(deftest summary-duration-delta-correct
  (testing "Summary duration delta is correct (B - A)"
    (let [build-a (make-build :id "a" :status :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:05:00Z")
          build-b (make-build :id "b" :status :success
                              :started-at "2024-01-01T10:00:00Z"
                              :completed-at "2024-01-01T10:03:00Z")
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (= 300 (get-in result [:summary :duration-a-s])))
      (is (= 180 (get-in result [:summary :duration-b-s])))
      (is (= -120 (get-in result [:summary :duration-delta-s]))))))

(deftest stages-ordered-consistently
  (testing "Stage comparison results preserve insertion order from both builds"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Checkout" :success)
                    (make-stage "Build" :success)
                    (make-stage "Test" :success)]
          stages-b [(make-stage "Checkout" :success)
                    (make-stage "Build" :success)
                    (make-stage "Deploy" :success)]
          result (bc/compare-builds build-a stages-a [] [] build-b stages-b [] [])
          names (mapv :stage-name (:stages result))]
      ;; All unique stages should appear: Checkout, Build, Test (from A), Deploy (from B)
      (is (= ["Checkout" "Build" "Test" "Deploy"] names)))))

(deftest sqlite-timestamp-format-handled
  (testing "SQLite-style timestamps (space separator) are parsed correctly"
    (let [build-a (make-build :id "a" :status :success
                              :started-at "2024-01-01 10:00:00"
                              :completed-at "2024-01-01 10:02:00")
          build-b (make-build :id "b" :status :success
                              :started-at "2024-01-01 10:00:00"
                              :completed-at "2024-01-01 10:03:00")
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (= 120 (get-in result [:summary :duration-a-s])))
      (is (= 180 (get-in result [:summary :duration-b-s])))
      (is (= 60 (get-in result [:summary :duration-delta-s]))))))

(deftest build-a-and-build-b-preserved-in-result
  (testing "Original build records are preserved in the result"
    (let [build-a (make-build :id "a-123" :status :success :build-number 10)
          build-b (make-build :id "b-456" :status :failure :build-number 11)
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (= "a-123" (get-in result [:build-a :id])))
      (is (= "b-456" (get-in result [:build-b :id])))
      (is (= 10 (get-in result [:build-a :build-number])))
      (is (= 11 (get-in result [:build-b :build-number]))))))

(deftest artifacts-both-empty
  (testing "Empty artifact lists produce empty diff"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          result (bc/compare-builds build-a [] [] [] build-b [] [] [])]
      (is (empty? (get-in result [:artifacts :only-in-a])))
      (is (empty? (get-in result [:artifacts :only-in-b])))
      (is (empty? (get-in result [:artifacts :in-both])))
      (is (empty? (get-in result [:artifacts :size-changes]))))))

(deftest nil-artifacts-handled
  (testing "Nil artifact lists don't cause errors"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          result (bc/compare-builds build-a [] [] nil build-b [] [] nil)]
      (is (empty? (get-in result [:artifacts :only-in-a])))
      (is (empty? (get-in result [:artifacts :only-in-b]))))))

(deftest step-only-in-one-build
  (testing "Steps present in only one build are tracked without error"
    (let [build-a (make-build :id "a" :status :success)
          build-b (make-build :id "b" :status :success)
          stages-a [(make-stage "Build" :success)]
          stages-b [(make-stage "Build" :success)]
          steps-a [(make-step "Build" "Compile" :success :exit-code 0)]
          steps-b [(make-step "Build" "Compile" :success :exit-code 0)
                   (make-step "Build" "Lint" :success :exit-code 0)]
          result (bc/compare-builds build-a stages-a steps-a [] build-b stages-b steps-b [])
          stage-diff (first (:stages result))
          step-names (mapv :step-name (:steps stage-diff))
          lint-step (second (:steps stage-diff))]
      (is (= ["Compile" "Lint"] step-names))
      ;; Lint only in B: status-a should be nil
      (is (nil? (:status-a lint-step)))
      (is (= :success (:status-b lint-step))))))
