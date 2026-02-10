(ns chengis.engine.test-parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.test-parser :as tp]))

;; ---------------------------------------------------------------------------
;; JUnit XML
;; ---------------------------------------------------------------------------

(deftest parse-junit-xml-pass-test
  (testing "parse-junit-xml parses passing tests"
    (let [xml "<testsuite name=\"MySuite\" tests=\"2\">
                <testcase name=\"test_add\" classname=\"math\" time=\"0.05\"/>
                <testcase name=\"test_sub\" classname=\"math\" time=\"0.03\"/>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 2 (count results)))
      (is (every? #(= "pass" (:status %)) results))
      (is (= "test_add" (:test-name (first results))))
      (is (= "math" (:test-suite (first results))))
      (is (= 50 (:duration-ms (first results)))))))

(deftest parse-junit-xml-failure-test
  (testing "parse-junit-xml parses failures"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_fail\" classname=\"suite\">
                  <failure message=\"expected 1 got 2\">assertion error</failure>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "fail" (:status (first results))))
      (is (some? (:error-msg (first results)))))))

(deftest parse-junit-xml-error-test
  (testing "parse-junit-xml parses errors"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_error\" classname=\"suite\">
                  <error message=\"NPE\">null pointer</error>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "error" (:status (first results)))))))

(deftest parse-junit-xml-skipped-test
  (testing "parse-junit-xml parses skipped tests"
    (let [xml "<testsuite name=\"MySuite\">
                <testcase name=\"test_skip\" classname=\"suite\">
                  <skipped/>
                </testcase>
              </testsuite>"
          results (tp/parse-junit-xml xml)]
      (is (= 1 (count results)))
      (is (= "skip" (:status (first results)))))))

(deftest parse-junit-xml-nil-test
  (testing "parse-junit-xml returns nil for non-XML"
    (is (nil? (tp/parse-junit-xml "not xml")))
    (is (nil? (tp/parse-junit-xml nil)))
    (is (nil? (tp/parse-junit-xml "")))))

;; ---------------------------------------------------------------------------
;; TAP format
;; ---------------------------------------------------------------------------

(deftest parse-tap-output-test
  (testing "parse-tap-output parses TAP output"
    (let [output "TAP version 13\n1..3\nok 1 - test addition\nnot ok 2 - test subtraction\nok 3 - test multiply # skip reason"
          results (tp/parse-tap-output output)]
      (is (= 3 (count results)))
      (is (= "pass" (:status (first results))))
      (is (= "fail" (:status (second results))))
      (is (= "skip" (:status (nth results 2)))))))

(deftest parse-tap-output-nil-test
  (testing "parse-tap-output returns nil for non-TAP"
    (is (nil? (tp/parse-tap-output "regular output")))
    (is (nil? (tp/parse-tap-output nil)))))

;; ---------------------------------------------------------------------------
;; Generic pattern
;; ---------------------------------------------------------------------------

(deftest parse-generic-passed-failed-test
  (testing "parse-generic-output detects 'X passed, Y failed'"
    (let [result (tp/parse-generic-output "10 passed, 2 failed")]
      (is (some? result))
      (is (= 10 (:total-pass result)))
      (is (= 2 (:total-fail result))))))

(deftest parse-generic-tests-failures-test
  (testing "parse-generic-output detects 'X tests, Y failures'"
    (let [result (tp/parse-generic-output "25 tests, 3 failures")]
      (is (some? result))
      (is (= 22 (:total-pass result)))
      (is (= 3 (:total-fail result))))))

(deftest parse-generic-ran-tests-test
  (testing "parse-generic-output detects 'Ran X tests'"
    (let [result (tp/parse-generic-output "Ran 42 tests in 3.5 seconds")]
      (is (some? result))
      (is (= 42 (:total-run result))))))

(deftest parse-generic-no-match-test
  (testing "parse-generic-output returns nil for no pattern"
    (is (nil? (tp/parse-generic-output "hello world")))
    (is (nil? (tp/parse-generic-output nil)))))

;; ---------------------------------------------------------------------------
;; Combined parser
;; ---------------------------------------------------------------------------

(deftest extract-test-results-junit-test
  (testing "extract-test-results prefers JUnit XML"
    (let [xml "<testsuite><testcase name=\"t1\" classname=\"s\"/></testsuite>"
          results (tp/extract-test-results xml)]
      (is (= 1 (count results)))
      (is (= "t1" (:test-name (first results)))))))

(deftest extract-test-results-tap-test
  (testing "extract-test-results falls back to TAP"
    (let [tap "TAP version 13\n1..1\nok 1 - works"
          results (tp/extract-test-results tap)]
      (is (= 1 (count results)))
      (is (= "pass" (:status (first results)))))))

(deftest extract-test-results-generic-test
  (testing "extract-test-results falls back to generic"
    (let [output "5 passed, 1 failed"
          results (tp/extract-test-results output :stage-name "Test" :step-name "run")]
      (is (= 2 (count results)))
      (is (some #(= "pass" (:status %)) results))
      (is (some #(= "fail" (:status %)) results)))))

(deftest extract-test-results-nil-test
  (testing "extract-test-results returns nil for empty/nil"
    (is (nil? (tp/extract-test-results nil)))
    (is (nil? (tp/extract-test-results "")))
    (is (nil? (tp/extract-test-results "   ")))))
