(ns chengis.web.views.iac-test
  "Tests for IaC view rendering — verify each view produces HTML containing
   expected elements without requiring a database."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [chengis.web.views.iac :as iac-views]))

;; ---------------------------------------------------------------------------
;; Dashboard rendering
;; ---------------------------------------------------------------------------

(deftest render-dashboard-test
  (testing "dashboard renders with Infrastructure as Code heading"
    (let [html (iac-views/render-dashboard
                 {:projects [{:id "p1" :job-id "my-infra" :tool-type "terraform"
                              :working-dir "." :auto-detect true}]
                  :latest-plans {}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "Infrastructure"))
      (is (str/includes? html "Code"))
      (is (str/includes? html "my-infra")))))

(deftest render-dashboard-empty-test
  (testing "no projects shows empty state"
    (let [html (iac-views/render-dashboard
                 {:projects []
                  :latest-plans {}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "Infrastructure"))
      ;; Should show some indication of no projects
      (is (or (str/includes? html "No IaC projects")
              (str/includes? html "Create"))))))

(deftest render-dashboard-with-projects-test
  (testing "project cards rendered for multiple projects"
    (let [html (iac-views/render-dashboard
                 {:projects [{:id "p1" :job-id "infra-vpc" :tool-type "terraform"
                              :working-dir "./vpc" :auto-detect true}
                             {:id "p2" :job-id "app-stack" :tool-type "pulumi"
                              :working-dir "./app" :auto-detect false}
                             {:id "p3" :job-id "cf-deploy" :tool-type "cloudformation"
                              :working-dir "./cf" :auto-detect false}]
                  :latest-plans {"p1" {:status "succeeded" :action "plan"
                                        :resources-add 2 :resources-change 1
                                        :resources-destroy 0 :created-at "2025-01-15"}}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "infra-vpc"))
      (is (str/includes? html "app-stack"))
      (is (str/includes? html "cf-deploy"))
      ;; Tool types should appear
      (is (str/includes? html "terraform"))
      (is (str/includes? html "pulumi"))
      (is (str/includes? html "cloudformation")))))

;; ---------------------------------------------------------------------------
;; Project detail rendering
;; ---------------------------------------------------------------------------

(deftest render-project-detail-test
  (testing "project info displayed"
    (let [html (iac-views/render-project-detail
                 {:project {:id "p1" :job-id "my-project" :tool-type "terraform"
                            :working-dir "./infra" :auto-detect true :org-id "org-1"}
                  :plans [{:id "plan-1" :action "plan" :status "succeeded"
                           :resources-add 3 :resources-change 1 :resources-destroy 0
                           :created-at "2025-01-15" :initiated-by "user-1" :duration-ms 5000}]
                  :states [{:version 1 :state-hash "abc123def456" :size-bytes 1024
                            :created-by "user-1" :created-at "2025-01-15"}]
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "my-project"))
      (is (str/includes? html "terraform"))
      ;; Plans history section
      (is (str/includes? html "Plans"))
      ;; States section
      (is (str/includes? html "States")))))

;; ---------------------------------------------------------------------------
;; Plan detail rendering
;; ---------------------------------------------------------------------------

(deftest render-plan-detail-test
  (testing "plan info with resource counts rendered"
    (let [html (iac-views/render-plan-detail
                 {:plan {:id "plan-1" :project-id "p1" :action "plan"
                         :status "succeeded" :initiated-by "user-1"
                         :resources-add 3 :resources-change 1
                         :resources-destroy 2 :created-at "2025-01-15"
                         :duration-ms 5000}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "Plan"))
      ;; Resource counts should be visible
      (is (str/includes? html "+3"))
      (is (str/includes? html "-2")))))

(deftest render-plan-diff-test
  (testing "resource table rendered with resource changes"
    (let [html (iac-views/render-plan-detail
                 {:plan {:id "plan-2" :project-id "p1" :action "plan"
                         :status "succeeded"
                         :plan-json {:resources [{:resource-type "aws_instance"
                                                   :name "web" :action "create"}
                                                  {:resource-type "aws_s3_bucket"
                                                   :name "logs" :action "delete"}]}
                         :resources-add 1 :resources-change 0
                         :resources-destroy 1 :created-at "2025-01-15"
                         :initiated-by "user-1"}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      ;; Resource types should appear in the table
      (is (str/includes? html "aws_instance"))
      (is (str/includes? html "aws_s3_bucket"))
      ;; Resource changes section
      (is (str/includes? html "Resource")))))

(deftest render-cost-estimate-test
  (testing "cost numbers displayed in plan detail"
    (let [html (iac-views/render-plan-detail
                 {:plan {:id "plan-3" :project-id "p1" :action "plan"
                         :status "succeeded" :initiated-by "user-1"
                         :resources-add 1 :resources-change 0
                         :resources-destroy 0 :created-at "2025-01-15"}
                  :cost-estimate {:total-monthly 125.50 :total-hourly 0.17
                                  :resources [{:name "web-server"
                                               :monthly 50.0}]}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      ;; Cost section should be rendered
      (is (str/includes? html "Cost"))
      (is (str/includes? html "125.5"))
      (is (str/includes? html "Monthly")))))

(deftest render-states-page-test
  (testing "states table rendered in project detail"
    (let [html (iac-views/render-project-detail
                 {:project {:id "p1" :job-id "stateful-proj" :tool-type "terraform"
                            :working-dir "."}
                  :plans []
                  :states [{:version 1 :state-hash "abc123def456" :size-bytes 2048
                            :created-by "user-1" :created-at "2025-01-15"}
                           {:version 2 :state-hash "def789ghi012" :size-bytes 3072
                            :created-by "user-2" :created-at "2025-01-16"}]
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      ;; States section should be visible
      (is (str/includes? html "States"))
      ;; State hash should appear (truncated)
      (is (str/includes? html "abc123")))))

(deftest render-approval-form-test
  (testing "approve/reject buttons present for awaiting-approval plan"
    (let [html (iac-views/render-plan-detail
                 {:plan {:id "plan-approval" :project-id "p1" :action "apply"
                         :status "awaiting-approval" :initiated-by "user-1"
                         :resources-add 5 :resources-change 2
                         :resources-destroy 1 :created-at "2025-01-15"}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (str/includes? html "Approv"))
      (is (str/includes? html "Reject")))))

(deftest tool-badge-test
  (testing "correct tool type badges appear for each tool"
    ;; Test through dashboard rendering which uses tool-badge
    (let [html (iac-views/render-dashboard
                 {:projects [{:id "p1" :job-id "tf-proj" :tool-type "terraform"
                              :working-dir "." :auto-detect false}
                             {:id "p2" :job-id "pu-proj" :tool-type "pulumi"
                              :working-dir "." :auto-detect false}
                             {:id "p3" :job-id "cf-proj" :tool-type "cloudformation"
                              :working-dir "." :auto-detect false}]
                  :latest-plans {}
                  :csrf-token "tok"
                  :user {:username "admin" :role :admin}})]
      ;; Each tool type should have distinct styling
      (is (str/includes? html "purple"))       ;; terraform badge color
      (is (str/includes? html "blue"))         ;; pulumi badge color
      (is (str/includes? html "orange")))))    ;; cloudformation badge color
