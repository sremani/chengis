(ns chengis.engine.promotion-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.environment-store :as env-store]
            [chengis.db.promotion-store :as promotion-store]
            [chengis.engine.promotion :as promotion-engine]))

(def test-db-path "/tmp/chengis-promotion-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(defn- create-test-build! [ds org-id job-id status]
  (job-store/create-job! ds {:pipeline-name job-id} :org-id org-id)
  (let [build (build-store/create-build! ds {:job-id job-id :org-id org-id})]
    (build-store/update-build-status! ds (:id build) status)
    (build-store/get-build ds (:id build) :org-id org-id)))

(defn- create-test-env! [ds slug order & {:keys [requires-approval]}]
  (env-store/create-environment! ds
    {:org-id "org-1" :name slug :slug slug :env-order order
     :requires-approval (boolean requires-approval)}))

(deftest check-promotion-eligibility-test
  (let [ds (test-ds)]
    (testing "eligible for successful build"
      (let [build (create-test-build! ds "org-1" "app1" :success)
            result (promotion-engine/check-promotion-eligibility ds (:id build))]
        (is (true? (:eligible result)))))

    (testing "ineligible for failed build"
      (let [build (create-test-build! ds "org-1" "app2" :failure)
            result (promotion-engine/check-promotion-eligibility ds (:id build))]
        (is (false? (:eligible result)))))

    (testing "ineligible for non-existent build"
      (let [result (promotion-engine/check-promotion-eligibility ds "non-existent")]
        (is (false? (:eligible result)))))))

(deftest execute-promotion-auto-approve-test
  (let [ds (test-ds)]
    (testing "auto-approves when target doesn't require approval"
      (let [build (create-test-build! ds "org-1" "app3" :success)
            dev (create-test-env! ds "dev" 10)
            staging (create-test-env! ds "staging" 20)
            system {:db ds :config {}}
            result (promotion-engine/execute-promotion! system
                     {:org-id "org-1" :build-id (:id build)
                      :from-environment-id (:id dev)
                      :to-environment-id (:id staging)
                      :user-id "user-1"})]
        (is (true? (:success result)))
        (is (true? (:promoted result)))
        ;; Verify artifact is in target env
        (let [current (promotion-store/get-current-artifact ds (:id staging))]
          (is (some? current))
          (is (= (:id build) (:build-id current))))))))

(deftest execute-promotion-await-approval-test
  (let [ds (test-ds)]
    (testing "awaits approval when target requires it"
      (let [build (create-test-build! ds "org-1" "app4" :success)
            prod (create-test-env! ds "prod" 30 :requires-approval true)
            system {:db ds :config {}}
            result (promotion-engine/execute-promotion! system
                     {:org-id "org-1" :build-id (:id build)
                      :to-environment-id (:id prod)
                      :user-id "user-1"})]
        (is (true? (:success result)))
        (is (true? (:awaiting-approval result)))
        ;; No artifact yet
        (is (nil? (promotion-store/get-current-artifact ds (:id prod))))))))

(deftest execute-promotion-reject-failed-build-test
  (let [ds (test-ds)]
    (testing "rejects promotion of failed build"
      (let [build (create-test-build! ds "org-1" "app5" :failure)
            env (create-test-env! ds "dev5" 10)
            system {:db ds :config {}}
            result (promotion-engine/execute-promotion! system
                     {:org-id "org-1" :build-id (:id build)
                      :to-environment-id (:id env)
                      :user-id "user-1"})]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "failure"))))))

(deftest execute-promotion-reject-missing-env-test
  (let [ds (test-ds)]
    (testing "rejects promotion to non-existent environment"
      (let [build (create-test-build! ds "org-1" "app6" :success)
            system {:db ds :config {}}
            result (promotion-engine/execute-promotion! system
                     {:org-id "org-1" :build-id (:id build)
                      :to-environment-id "non-existent"
                      :user-id "user-1"})]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "not found"))))))
