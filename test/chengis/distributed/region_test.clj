(ns chengis.distributed.region-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.region :as region]))

(deftest same-region-test
  (testing "same region returns true"
    (is (true? (region/same-region? "us-east-1" "us-east-1"))))

  (testing "different regions return false"
    (is (false? (region/same-region? "us-east-1" "eu-west-1"))))

  (testing "nil master region returns false"
    (is (false? (region/same-region? nil "us-east-1"))))

  (testing "nil agent region returns false"
    (is (false? (region/same-region? "us-east-1" nil))))

  (testing "both nil returns false"
    (is (false? (region/same-region? nil nil)))))

(deftest locality-bonus-test
  (testing "same region gets full bonus"
    (is (= 0.3 (region/locality-bonus "us-east-1" "us-east-1" 0.3))))

  (testing "different region gets zero"
    (is (= 0.0 (region/locality-bonus "us-east-1" "eu-west-1" 0.3))))

  (testing "nil region gets zero"
    (is (= 0.0 (region/locality-bonus nil "us-east-1" 0.3)))
    (is (= 0.0 (region/locality-bonus "us-east-1" nil 0.3))))

  (testing "custom weight"
    (is (= 0.5 (region/locality-bonus "us-east-1" "us-east-1" 0.5)))))

(deftest region-aware-score-test
  (testing "same region adds bonus"
    (is (= 1.0 (region/region-aware-score 0.7 "us-east-1" "us-east-1" 0.3))))

  (testing "different region adds nothing"
    (is (= 0.7 (region/region-aware-score 0.7 "us-east-1" "eu-west-1" 0.3))))

  (testing "capped at 1.5"
    (is (= 1.5 (region/region-aware-score 1.4 "us-east-1" "us-east-1" 0.3))))

  (testing "nil base score treated as 0.0"
    (is (= 0.3 (region/region-aware-score nil "us-east-1" "us-east-1" 0.3))))

  (testing "no region preference"
    (is (= 0.8 (region/region-aware-score 0.8 nil "us-east-1" 0.3))))

  (testing "zero weight"
    (is (= 0.8 (region/region-aware-score 0.8 "us-east-1" "us-east-1" 0.0)))))

(deftest edge-cases-test
  (testing "empty string regions treated as no region"
    (is (false? (region/same-region? "" "")))
    (is (false? (region/same-region? "" "us-east-1"))))

  (testing "case sensitivity"
    (is (false? (region/same-region? "US-EAST-1" "us-east-1")))
    (is (true? (region/same-region? "us-east-1" "us-east-1")))))
