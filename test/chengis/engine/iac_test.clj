(ns chengis.engine.iac-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [chengis.engine.iac :as iac]))

;; ---------------------------------------------------------------------------
;; Tool Detection
;; ---------------------------------------------------------------------------

(defn- with-temp-dir
  "Create a temp dir, run f with the dir path, then clean up."
  [f]
  (let [dir (io/file (str "/tmp/chengis-iac-detect-test-" (System/currentTimeMillis)))]
    (.mkdirs dir)
    (try
      (f (.getAbsolutePath dir))
      (finally
        (doseq [child (.listFiles dir)] (.delete child))
        (.delete dir)))))

(deftest detect-tool-type-terraform-test
  (testing "workspace with .tf files detects as :terraform"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/main.tf") "resource \"aws_instance\" \"test\" {}")
        (is (= :terraform (iac/detect-tool-type dir)))))))

(deftest detect-tool-type-pulumi-test
  (testing "workspace with Pulumi.yaml detects as :pulumi"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/Pulumi.yaml") "name: my-project")
        (is (= :pulumi (iac/detect-tool-type dir)))))))

(deftest detect-tool-type-cloudformation-test
  (testing "workspace with template.json detects as :cloudformation"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/template.json") "{\"AWSTemplateFormatVersion\": \"2010-09-09\"}")
        (is (= :cloudformation (iac/detect-tool-type dir)))))))

(deftest detect-tool-type-none-test
  (testing "empty workspace returns nil"
    (with-temp-dir
      (fn [dir]
        (is (nil? (iac/detect-tool-type dir)))))))

(deftest detect-iac-project-test
  (testing "detect-iac-project returns map with tool-type and working-dir"
    (with-temp-dir
      (fn [dir]
        (spit (str dir "/main.tf") "variable \"name\" {}")
        (let [result (iac/detect-iac-project dir)]
          (is (some? result))
          (is (= :terraform (:tool-type result)))
          (is (= dir (:working-dir result)))))))

  (testing "detect-iac-project returns nil for empty dir"
    (with-temp-dir
      (fn [dir]
        (is (nil? (iac/detect-iac-project dir)))))))

;; ---------------------------------------------------------------------------
;; Terraform Command Builder
;; ---------------------------------------------------------------------------

(deftest build-terraform-command-plan-test
  (testing "terraform plan command has correct structure"
    (let [cmd (iac/build-terraform-command {:action "plan"})]
      (is (string? cmd))
      (is (str/includes? cmd "terraform"))
      (is (str/includes? cmd "plan"))
      (is (str/includes? cmd "-no-color"))
      (is (str/includes? cmd "-input=false")))))

(deftest build-terraform-command-apply-test
  (testing "terraform apply command includes auto-approve"
    (let [cmd (iac/build-terraform-command {:action "apply"})]
      (is (str/includes? cmd "apply"))
      (is (str/includes? cmd "-auto-approve")))))

;; ---------------------------------------------------------------------------
;; Pulumi Command Builder
;; ---------------------------------------------------------------------------

(deftest build-pulumi-command-preview-test
  (testing "pulumi preview command has correct structure"
    (let [cmd (iac/build-pulumi-command {:action "preview"})]
      (is (string? cmd))
      (is (str/includes? cmd "pulumi"))
      (is (str/includes? cmd "preview"))
      (is (str/includes? cmd "--non-interactive"))
      (is (str/includes? cmd "--json")))))

;; ---------------------------------------------------------------------------
;; CloudFormation Command Builder
;; ---------------------------------------------------------------------------

(deftest build-cloudformation-command-validate-test
  (testing "cloudformation validate command has correct structure"
    (let [cmd (iac/build-cloudformation-command
                {:action "validate" :template-file "template.json"})]
      (is (string? cmd))
      (is (str/includes? cmd "aws"))
      (is (str/includes? cmd "cloudformation"))
      (is (str/includes? cmd "validate-template"))
      (is (str/includes? cmd "template.json"))
      (is (str/includes? cmd "--output json")))))

;; ---------------------------------------------------------------------------
;; Plan Parsing — Terraform
;; ---------------------------------------------------------------------------

(deftest parse-terraform-plan-summary-test
  (testing "parse terraform plan JSON extracts resource counts"
    (let [plan-json (json/write-str
                      {:resource_changes
                       [{:type "aws_instance" :name "web"
                         :change {:actions ["create"]}}
                        {:type "aws_s3_bucket" :name "logs"
                         :change {:actions ["update"]}}
                        {:type "aws_security_group" :name "old"
                         :change {:actions ["delete"]}}]})
          result (iac/parse-terraform-plan-summary plan-json)]
      (is (= 1 (:resources-add result)))
      (is (= 1 (:resources-change result)))
      (is (= 1 (:resources-destroy result)))
      (is (= 3 (count (:resources result))))
      (is (= "aws_instance" (:resource-type (first (:resources result))))))))

;; ---------------------------------------------------------------------------
;; Plan Parsing — Pulumi
;; ---------------------------------------------------------------------------

(deftest parse-pulumi-preview-summary-test
  (testing "parse pulumi preview JSON extracts resource counts"
    (let [preview-json (json/write-str
                         {:steps
                          [{:op "create" :type "aws:s3:Bucket"
                            :urn "urn:pulumi:dev::proj::aws:s3:Bucket::my-bucket"}
                           {:op "update" :type "aws:ec2:Instance"
                            :urn "urn:pulumi:dev::proj::aws:ec2:Instance::web"}
                           {:op "same" :type "aws:iam:Role"
                            :urn "urn:pulumi:dev::proj::aws:iam:Role::role"}]})
          result (iac/parse-pulumi-preview-summary preview-json)]
      (is (= 1 (:resources-add result)))
      (is (= 1 (:resources-change result)))
      (is (= 0 (:resources-destroy result)))
      (is (= 3 (count (:resources result)))))))

;; ---------------------------------------------------------------------------
;; Plan Parsing — CloudFormation
;; ---------------------------------------------------------------------------

(deftest parse-cloudformation-changeset-summary-test
  (testing "parse cloudformation changeset JSON extracts resource counts"
    (let [changeset-json (json/write-str
                           {:Changes
                            [{:ResourceChange
                              {:Action "Add" :ResourceType "AWS::S3::Bucket"
                               :LogicalResourceId "MyBucket"}}
                             {:ResourceChange
                              {:Action "Modify" :ResourceType "AWS::EC2::Instance"
                               :LogicalResourceId "WebServer"}}
                             {:ResourceChange
                              {:Action "Remove" :ResourceType "AWS::Lambda::Function"
                               :LogicalResourceId "OldFunction"}}]})
          result (iac/parse-cloudformation-changeset-summary changeset-json)]
      (is (= 1 (:resources-add result)))
      (is (= 1 (:resources-change result)))
      (is (= 1 (:resources-destroy result)))
      (is (= 3 (count (:resources result))))
      (is (= "MyBucket" (:name (first (:resources result))))))))
