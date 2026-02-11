(ns chengis.engine.iac-cost-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [chengis.engine.iac-cost :as iac-cost]))

;; ---------------------------------------------------------------------------
;; Resource cost estimation
;; ---------------------------------------------------------------------------

(deftest estimate-known-resource-test
  (testing "known resource type returns positive cost"
    (let [cost (iac-cost/estimate-resource-cost "aws_instance" "create")]
      (is (pos? (:monthly cost)))
      (is (= "create" (:action cost))))))

(deftest estimate-unknown-resource-test
  (testing "unknown resource type returns 0 monthly cost"
    (let [cost (iac-cost/estimate-resource-cost "unknown_resource_xyz" "create")]
      (is (= 0.0 (:monthly cost))))))

;; ---------------------------------------------------------------------------
;; Plan cost estimation
;; ---------------------------------------------------------------------------

(deftest estimate-plan-cost-test
  (testing "multiple resources sum correctly"
    (let [resources [{:resource-type "aws_instance" :action "create"}
                     {:resource-type "aws_s3_bucket" :action "create"}]
          result (iac-cost/estimate-plan-cost resources "terraform")]
      (is (some? result))
      (is (pos? (:total-monthly result)))
      (is (pos? (:total-hourly result)))
      (is (= "USD" (:currency result)))
      (is (= 2 (count (:resources result)))))))

(deftest no-op-resources-excluded-test
  (testing "no-op actions do not add cost"
    (let [resources [{:resource-type "aws_instance" :action "no-op"}
                     {:resource-type "aws_s3_bucket" :action "no-op"}]
          result (iac-cost/estimate-plan-cost resources "terraform")]
      (is (= 0.0 (:total-monthly result))))))

(deftest delete-resources-excluded-test
  (testing "delete actions do not add cost"
    (let [resources [{:resource-type "aws_instance" :action "delete"}
                     {:resource-type "aws_rds_instance" :action "delete"}]
          result (iac-cost/estimate-plan-cost resources "terraform")]
      (is (= 0.0 (:total-monthly result))))))

(deftest empty-plan-test
  (testing "empty plan returns zero costs"
    (let [result (iac-cost/estimate-plan-cost [] "terraform")]
      (is (= 0.0 (:total-monthly result)))
      (is (= 0.0 (:total-hourly result)))
      (is (empty? (:resources result))))))

;; ---------------------------------------------------------------------------
;; Terraform plan resource parsing
;; ---------------------------------------------------------------------------

(deftest parse-terraform-plan-resources-test
  (testing "extracts resource types and actions from terraform plan JSON"
    (let [plan-json (json/write-str
                      {:resource_changes
                       [{:type "aws_instance" :name "web"
                         :change {:actions ["create"]}}
                        {:type "aws_s3_bucket" :name "logs"
                         :change {:actions ["delete"]}}
                        {:type "aws_rds_instance" :name "db"
                         :change {:actions ["update"]}}]})
          result (iac-cost/parse-terraform-plan-resources plan-json)]
      (is (= 3 (count result)))
      (is (= "aws_instance" (:resource-type (first result))))
      (is (= "create" (:action (first result))))
      (is (= "delete" (:action (second result)))))))

;; ---------------------------------------------------------------------------
;; Pulumi preview resource parsing
;; ---------------------------------------------------------------------------

(deftest parse-pulumi-preview-resources-test
  (testing "extracts resource types from pulumi preview JSON"
    (let [preview-json (json/write-str
                         {:steps
                          [{:op "create" :type "aws:s3:Bucket"
                            :urn "urn:pulumi:dev::proj::aws:s3:Bucket::my-bucket"}
                           {:op "update" :type "aws:ec2:Instance"
                            :urn "urn:pulumi:dev::proj::aws:ec2:Instance::web"}]})
          result (iac-cost/parse-pulumi-preview-resources preview-json)]
      (is (= 2 (count result)))
      (is (= "aws:s3:Bucket" (:resource-type (first result))))
      (is (= "create" (:action (first result)))))))

;; ---------------------------------------------------------------------------
;; CloudFormation changeset parsing
;; ---------------------------------------------------------------------------

(deftest parse-cloudformation-changeset-test
  (testing "extracts resources from cloudformation changeset JSON"
    (let [changeset-json (json/write-str
                           {:Changes
                            [{:ResourceChange
                              {:Action "Add" :ResourceType "AWS::S3::Bucket"
                               :LogicalResourceId "MyBucket"}}
                             {:ResourceChange
                              {:Action "Modify" :ResourceType "AWS::EC2::Instance"
                               :LogicalResourceId "WebServer"}}]})
          result (iac-cost/parse-cloudformation-changeset changeset-json)]
      (is (= 2 (count result)))
      (is (= "AWS::S3::Bucket" (:resource-type (first result))))
      (is (= "create" (:action (first result))))
      (is (= "MyBucket" (:name (first result)))))))

;; ---------------------------------------------------------------------------
;; Cost summary formatting
;; ---------------------------------------------------------------------------

(deftest format-cost-summary-test
  (testing "human-readable format includes dollar amounts"
    (let [estimate {:total-monthly 125.30
                    :total-hourly 0.17
                    :resources [{:resource-type "aws_instance" :monthly 30.0}
                                {:resource-type "aws_s3_bucket" :monthly 2.3}]}
          summary (iac-cost/format-cost-summary estimate)]
      (is (string? summary))
      (is (str/includes? summary "$"))
      (is (str/includes? summary "125.30"))
      (is (str/includes? summary "month")))))

;; ---------------------------------------------------------------------------
;; Phase 2d: or-fallback defaults for cost estimation
;; ---------------------------------------------------------------------------

(deftest parse-terraform-or-fallback-defaults-test
  (testing "empty Terraform plan uses default empty resource_changes"
    (let [resources (iac-cost/parse-terraform-plan-resources (json/write-str {}))]
      (is (= [] resources))))

  (testing "Terraform plan with missing action defaults to 'unknown'"
    (let [resources (iac-cost/parse-terraform-plan-resources
                      (json/write-str
                        {:resource_changes
                         [{:type "aws_instance" :name "web" :change {}}]}))]
      (is (= 1 (count resources)))
      (is (= "unknown" (:action (first resources))))))

  (testing "Terraform plan with missing type/name defaults to empty string"
    (let [resources (iac-cost/parse-terraform-plan-resources
                      (json/write-str
                        {:resource_changes [{:change {:actions ["create"]}}]}))]
      (is (= 1 (count resources)))
      (is (= "" (:resource-type (first resources))))
      (is (= "" (:name (first resources)))))))

(deftest estimate-cost-with-nil-resources-test
  (testing "estimate-plan-cost with nil resources defaults to []"
    (let [result (iac-cost/estimate-plan-cost nil :terraform)]
      (is (= [] (:resources result)))
      (is (= 0.0 (:total-monthly result)))))

  (testing "format-cost-summary handles zero-cost estimate"
    (let [estimate {:total-monthly 0.0 :total-hourly 0.0 :resources []}
          summary (iac-cost/format-cost-summary estimate)]
      (is (string? summary))
      (is (str/includes? summary "0")))))
