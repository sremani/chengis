(ns chengis.db.iac-cost-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.iac-cost-store :as iac-cost-store]))

(def test-db-path "/tmp/chengis-iac-cost-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

;; ---------------------------------------------------------------------------
;; save-estimate! tests
;; ---------------------------------------------------------------------------

(deftest save-estimate-test
  (let [ds (test-ds)]
    (testing "save-estimate! creates estimate with correct fields"
      (let [e (iac-cost-store/save-estimate! ds
                {:org-id "org-1"
                 :plan-id "plan-1"
                 :total-monthly 125.50
                 :total-hourly 0.172
                 :currency "USD"
                 :resources [{:resource-type "aws_instance" :monthly-cost 50.0}
                             {:resource-type "aws_s3_bucket" :monthly-cost 5.0}]
                 :estimation-method "builtin"})]
        (is (some? (:id e)))
        (is (= "org-1" (:org-id e)))
        (is (= "plan-1" (:plan-id e)))
        (is (= 125.50 (:total-monthly e)))
        (is (= 0.172 (:total-hourly e)))
        (is (= "USD" (:currency e)))
        (is (= "builtin" (:estimation-method e)))))))

;; ---------------------------------------------------------------------------
;; get-estimate tests
;; ---------------------------------------------------------------------------

(deftest get-estimate-test
  (let [ds (test-ds)]
    (testing "get-estimate retrieves by plan-id"
      (iac-cost-store/save-estimate! ds
        {:org-id "org-1" :plan-id "plan-2" :total-monthly 50.0 :total-hourly 0.068})
      (let [e (iac-cost-store/get-estimate ds "plan-2")]
        (is (some? e))
        (is (= "plan-2" (:plan-id e)))
        (is (= 50.0 (:total-monthly e)))))

    (testing "get-estimate returns nil for non-existent plan"
      (is (nil? (iac-cost-store/get-estimate ds "non-existent-plan"))))))

;; ---------------------------------------------------------------------------
;; list-estimates tests
;; ---------------------------------------------------------------------------

(deftest list-estimates-test
  (let [ds (test-ds)]
    (testing "list-estimates returns all estimates"
      (iac-cost-store/save-estimate! ds {:org-id "org-1" :plan-id "p1" :total-monthly 10.0})
      (iac-cost-store/save-estimate! ds {:org-id "org-1" :plan-id "p2" :total-monthly 20.0})
      (iac-cost-store/save-estimate! ds {:org-id "org-2" :plan-id "p3" :total-monthly 30.0})
      (is (= 3 (count (iac-cost-store/list-estimates ds)))))

    (testing "list-estimates filters by org-id"
      (is (= 2 (count (iac-cost-store/list-estimates ds :org-id "org-1"))))
      (is (= 1 (count (iac-cost-store/list-estimates ds :org-id "org-2")))))

    (testing "list-estimates respects limit"
      (is (= 1 (count (iac-cost-store/list-estimates ds :limit 1)))))))

;; ---------------------------------------------------------------------------
;; JSON round-trip tests
;; ---------------------------------------------------------------------------

(deftest resources-json-round-trip-test
  (let [ds (test-ds)]
    (testing "resources stored and retrieved correctly"
      (let [resources [{:resource-type "aws_instance" :action "create" :monthly-cost 50.0}
                       {:resource-type "aws_s3_bucket" :action "create" :monthly-cost 5.0}]
            e (iac-cost-store/save-estimate! ds
                {:org-id "org-1" :plan-id "plan-rt" :total-monthly 55.0
                 :resources resources})
            retrieved (iac-cost-store/get-estimate ds "plan-rt")]
        (is (some? (:resources retrieved)))
        (is (= 2 (count (:resources retrieved))))
        (is (= "aws_instance" (:resource-type (first (:resources retrieved)))))))))

;; ---------------------------------------------------------------------------
;; Edge cases
;; ---------------------------------------------------------------------------

(deftest estimate-with-zero-costs-test
  (let [ds (test-ds)]
    (testing "handles zero-cost resources"
      (let [e (iac-cost-store/save-estimate! ds
                {:org-id "org-1" :plan-id "plan-zero"
                 :total-monthly 0.0 :total-hourly 0.0
                 :resources [{:resource-type "aws_vpc" :monthly-cost 0.0}]})]
        (is (= 0.0 (:total-monthly e)))
        (is (= 0.0 (:total-hourly e)))))))

(deftest multiple-estimates-per-plan-test
  (let [ds (test-ds)]
    (testing "can save multiple estimates for the same plan"
      (iac-cost-store/save-estimate! ds
        {:org-id "org-1" :plan-id "plan-multi" :total-monthly 50.0})
      (iac-cost-store/save-estimate! ds
        {:org-id "org-1" :plan-id "plan-multi" :total-monthly 55.0})
      ;; get-estimate returns one (the most recent or first match)
      (let [e (iac-cost-store/get-estimate ds "plan-multi")]
        (is (some? e))
        (is (= "plan-multi" (:plan-id e)))))))

(deftest org-scoping-test
  (let [ds (test-ds)]
    (testing "estimates filtered by org"
      (iac-cost-store/save-estimate! ds {:org-id "org-a" :plan-id "pa1" :total-monthly 10.0})
      (iac-cost-store/save-estimate! ds {:org-id "org-b" :plan-id "pb1" :total-monthly 20.0})
      (is (= 1 (count (iac-cost-store/list-estimates ds :org-id "org-a"))))
      (is (= 1 (count (iac-cost-store/list-estimates ds :org-id "org-b")))))))

(deftest default-values-test
  (let [ds (test-ds)]
    (testing "currency defaults to USD"
      (let [e (iac-cost-store/save-estimate! ds
                {:org-id "org-1" :plan-id "plan-defaults" :total-monthly 10.0})]
        (is (= "USD" (:currency e)))))

    (testing "total-monthly defaults to 0.0"
      (let [e (iac-cost-store/save-estimate! ds
                {:org-id "org-1" :plan-id "plan-defaults2"})]
        (is (= 0.0 (:total-monthly e)))
        (is (= 0.0 (:total-hourly e)))))))
