(ns chengis.engine.log-context-test
  "Tests for the log correlation context macros."
  (:require [clojure.test :refer :all]
            [chengis.engine.log-context :as log-ctx]
            [taoensso.timbre :as log]))

(deftest with-build-context-test
  (testing "with-build-context sets build-id, job-id, org-id in Timbre context"
    (log-ctx/with-build-context "build-123" "job-456" "org-789"
      (let [ctx log/*context*]
        (is (= "build-123" (:build-id ctx)))
        (is (= "job-456" (:job-id ctx)))
        (is (= "org-789" (:org-id ctx))))))

  (testing "with-build-context uses default-org when org is nil"
    (log-ctx/with-build-context "b1" "j1" "default-org"
      (is (= "default-org" (:org-id log/*context*))))))

(deftest with-stage-context-test
  (testing "with-stage-context sets stage-name in Timbre context"
    (log-ctx/with-stage-context "Build"
      (let [ctx log/*context*]
        (is (= "Build" (:stage-name ctx))))))

  (testing "with-stage-context nests inside build context"
    (log-ctx/with-build-context "build-1" "job-1" "org-1"
      (log-ctx/with-stage-context "Test"
        (let [ctx log/*context*]
          (is (= "build-1" (:build-id ctx)))
          (is (= "job-1" (:job-id ctx)))
          (is (= "org-1" (:org-id ctx)))
          (is (= "Test" (:stage-name ctx))))))))

(deftest with-step-context-test
  (testing "with-step-context sets step-name in Timbre context"
    (log-ctx/with-step-context "compile"
      (let [ctx log/*context*]
        (is (= "compile" (:step-name ctx))))))

  (testing "with-step-context nests inside stage and build context"
    (log-ctx/with-build-context "build-2" "job-2" "org-2"
      (log-ctx/with-stage-context "Build"
        (log-ctx/with-step-context "compile"
          (let [ctx log/*context*]
            (is (= "build-2" (:build-id ctx)))
            (is (= "job-2" (:job-id ctx)))
            (is (= "org-2" (:org-id ctx)))
            (is (= "Build" (:stage-name ctx)))
            (is (= "compile" (:step-name ctx)))))))))

(deftest with-trace-context-test
  (testing "with-trace-context sets trace-id and span-id"
    (log-ctx/with-trace-context "abcdef1234567890abcdef1234567890" "0123456789abcdef"
      (let [ctx log/*context*]
        (is (= "abcdef1234567890abcdef1234567890" (:trace-id ctx)))
        (is (= "0123456789abcdef" (:span-id ctx))))))

  (testing "trace context nests with build context"
    (log-ctx/with-build-context "build-3" "job-3" "org-3"
      (log-ctx/with-trace-context "trace-abc" "span-def"
        (let [ctx log/*context*]
          (is (= "build-3" (:build-id ctx)))
          (is (= "trace-abc" (:trace-id ctx)))
          (is (= "span-def" (:span-id ctx))))))))

(deftest context-isolation-test
  (testing "context does not leak outside macro scope"
    (log-ctx/with-build-context "build-inner" "job-inner" "org-inner"
      (is (= "build-inner" (:build-id log/*context*))))
    ;; After the macro, context should be empty (or not contain build-id)
    (is (nil? (:build-id log/*context*))))

  (testing "stage context does not leak between stages"
    (log-ctx/with-build-context "b" "j" "o"
      (log-ctx/with-stage-context "stage-A"
        (is (= "stage-A" (:stage-name log/*context*))))
      ;; After stage-A scope, stage-name should be gone
      (is (nil? (:stage-name log/*context*))))))
