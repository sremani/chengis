(ns chengis.web.views.deploy-dashboard-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.web.views.environments :as v-environments]
            [chengis.web.views.releases :as v-releases]
            [chengis.web.views.promotions :as v-promotions]
            [chengis.web.views.strategies :as v-strategies]
            [chengis.web.views.deployments :as v-deployments]
            [chengis.web.views.deploy-dashboard :as v-dashboard]))

;; ---------------------------------------------------------------------------
;; View rendering tests — verify each view renders without errors
;; ---------------------------------------------------------------------------

(deftest environments-render-test
  (testing "environments render produces HTML"
    (let [html (v-environments/render
                 {:environments [{:id "e1" :name "Dev" :slug "dev" :env-order 10
                                  :locked false :requires-approval false :auto-promote false}]
                  :csrf-token "tok" :user {:username "admin" :role :admin}})]
      (is (string? html))
      (is (clojure.string/includes? html "Dev"))
      (is (clojure.string/includes? html "Environments")))))

(deftest environments-render-empty-test
  (testing "environments render with empty list"
    (let [html (v-environments/render {:environments [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "0 environment(s)")))))

(deftest environments-render-detail-test
  (testing "environment detail renders"
    (let [html (v-environments/render-detail
                 {:environment {:id "e1" :name "Prod" :slug "prod" :env-order 30
                                :locked true :locked-by "user-1" :requires-approval true
                                :auto-promote false :description "Production"}
                  :current-artifact {:build-id "build-1" :deployed-at "2024-01-01"}
                  :deployments [{:build-id "b1" :status "succeeded"}]
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Prod")))))

(deftest releases-render-test
  (testing "releases render produces HTML"
    (let [html (v-releases/render
                 {:releases [{:id "r1" :version "1.0.0" :title "First" :job-id "j1"
                              :status "draft" :created-at "2024-01-01"}]
                  :builds [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "1.0.0"))
      (is (clojure.string/includes? html "Releases")))))

(deftest releases-render-empty-test
  (testing "releases render with empty list"
    (let [html (v-releases/render {:releases [] :builds [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "No releases yet")))))

(deftest releases-render-detail-test
  (testing "release detail renders"
    (let [html (v-releases/render-detail
                 {:release {:id "r1" :version "1.0.0" :title "Release" :job-id "j1"
                            :build-id "b1" :status "published" :created-by "user-1"
                            :created-at "2024-01-01" :published-at "2024-01-02"}
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "1.0.0")))))

(deftest promotions-render-test
  (testing "promotions render produces HTML"
    (let [html (v-promotions/render
                 {:environments [{:id "e1" :name "Dev" :slug "dev" :env-order 10}
                                 {:id "e2" :name "Prod" :slug "prod" :env-order 30}]
                  :env-artifacts {}
                  :promotions [{:id "p1" :build-id "b1" :status "pending"
                                :promoted-by "user-1" :created-at "2024-01-01"}]
                  :builds [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Promotions")))))

(deftest promotions-render-empty-test
  (testing "promotions render with empty data"
    (let [html (v-promotions/render
                 {:environments [] :env-artifacts {} :promotions []
                  :builds [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "No promotions yet")))))

(deftest strategies-render-test
  (testing "strategies render produces HTML"
    (let [html (v-strategies/render
                 {:strategies [{:id "s1" :name "Direct" :strategy-type "direct"
                                :description "Immediate" :config {}}]
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Direct"))
      (is (clojure.string/includes? html "Strategies")))))

(deftest strategies-render-empty-test
  (testing "strategies render with empty list"
    (let [html (v-strategies/render {:strategies [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "0 strategy(ies)")))))

(deftest deployments-render-test
  (testing "deployments render produces HTML"
    (let [html (v-deployments/render
                 {:deployments [{:id "d1" :build-id "b1" :environment-id "e1"
                                 :status "succeeded" :initiated-by "user-1"
                                 :created-at "2024-01-01"}]
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Deployments")))))

(deftest deployments-render-empty-test
  (testing "deployments render with empty list"
    (let [html (v-deployments/render {:deployments [] :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "No deployments yet")))))

(deftest deployments-render-detail-test
  (testing "deployment detail renders with steps"
    (let [html (v-deployments/render-detail
                 {:deployment {:id "d1" :build-id "b1" :environment-id "e1"
                               :status "succeeded" :initiated-by "user-1"
                               :started-at "2024-01-01" :completed-at "2024-01-01"}
                  :steps [{:id "s1" :step-name "deploy" :step-order 1
                           :status "succeeded" :output "OK"}]
                  :environment {:name "Dev"}
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "deploy")))))

(deftest dashboard-render-test
  (testing "deploy dashboard renders full view"
    (let [html (v-dashboard/render
                 {:environments [{:id "e1" :name "Dev" :slug "dev" :env-order 10
                                  :locked false :requires-approval false}]
                  :env-artifacts {"e1" {:build-id "b1" :deployed-at "2024-01-01"}}
                  :recent-deployments [{:id "d1" :build-id "b1" :environment-id "e1"
                                        :status "succeeded" :created-at "2024-01-01"}]
                  :pending-promotions [{:id "p1" :build-id "b2" :promoted-by "user-1"}]
                  :deployment-stats {"succeeded" 5 "failed" 1}
                  :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Deployment Dashboard")))))

(deftest dashboard-render-empty-test
  (testing "deploy dashboard renders with no data"
    (let [html (v-dashboard/render
                 {:environments [] :env-artifacts {} :recent-deployments []
                  :pending-promotions [] :deployment-stats nil :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Deployment Dashboard")))))

(deftest dashboard-render-multiple-envs-test
  (testing "dashboard renders multiple environments"
    (let [html (v-dashboard/render
                 {:environments [{:id "e1" :name "Dev" :slug "dev" :env-order 10
                                  :locked false :requires-approval false}
                                 {:id "e2" :name "Staging" :slug "staging" :env-order 20
                                  :locked false :requires-approval false}
                                 {:id "e3" :name "Prod" :slug "prod" :env-order 30
                                  :locked true :requires-approval true}]
                  :env-artifacts {} :recent-deployments []
                  :pending-promotions [] :deployment-stats nil :csrf-token "tok"})]
      (is (string? html))
      (is (clojure.string/includes? html "Dev"))
      (is (clojure.string/includes? html "Staging"))
      (is (clojure.string/includes? html "Prod")))))
