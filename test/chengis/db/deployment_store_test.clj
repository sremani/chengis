(ns chengis.db.deployment-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]
            [chengis.db.deployment-store :as deployment-store]))

(def test-db-path "/tmp/chengis-deployment-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(defn- create-test-env! [ds slug order]
  (env-store/create-environment! ds
    {:org-id "org-1" :name slug :slug slug :env-order order}))

(deftest create-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "create-deployment! returns deployment with correct fields"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "build-1"
                 :initiated-by "user-1"})]
        (is (some? (:id d)))
        (is (= "org-1" (:org-id d)))
        (is (= "build-1" (:build-id d)))
        (is (= "pending" (:status d)))))

    (testing "create with metadata JSON"
      (let [meta {:reason "hotfix" :tag "v1.0.1"}
            d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "build-2"
                 :metadata meta})]
        (is (= meta (:metadata d)))))))

(deftest get-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging" 20)]
    (testing "get-deployment retrieves created deployment"
      (let [created (deployment-store/create-deployment! ds
                      {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
            retrieved (deployment-store/get-deployment ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-deployment with org-id scoping"
      (let [created (deployment-store/create-deployment! ds
                      {:org-id "org-2" :environment-id (:id env) :build-id "b2"})]
        (is (some? (deployment-store/get-deployment ds (:id created) :org-id "org-2")))
        (is (nil? (deployment-store/get-deployment ds (:id created) :org-id "other")))))))

(deftest list-deployments-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "list-deployments with org filter"
      (deployment-store/create-deployment! ds {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
      (deployment-store/create-deployment! ds {:org-id "org-1" :environment-id (:id env) :build-id "b2"})
      (deployment-store/create-deployment! ds {:org-id "org-2" :environment-id (:id env) :build-id "b3"})
      (is (= 2 (count (deployment-store/list-deployments ds :org-id "org-1"))))
      (is (= 1 (count (deployment-store/list-deployments ds :org-id "org-2")))))

    (testing "list-deployments with status filter"
      (let [d (deployment-store/create-deployment! ds {:org-id "org-1" :environment-id (:id env) :build-id "b4"})]
        (deployment-store/update-deployment-status! ds (:id d) "succeeded")
        (is (= 1 (count (deployment-store/list-deployments ds :org-id "org-1" :status "succeeded"))))))))

(deftest update-deployment-status-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "update-deployment-status! changes status"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b1"})]
        (deployment-store/update-deployment-status! ds (:id d) "in-progress" :started-at true)
        (let [updated (deployment-store/get-deployment ds (:id d))]
          (is (= "in-progress" (:status updated)))
          (is (some? (:started-at updated))))))))

(deftest cancel-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "cancel-deployment! cancels pending deployment"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b1"})]
        (is (true? (deployment-store/cancel-deployment! ds (:id d))))
        (is (= "cancelled" (:status (deployment-store/get-deployment ds (:id d)))))))

    (testing "cancel-deployment! fails for completed deployment"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b2"})]
        (deployment-store/update-deployment-status! ds (:id d) "succeeded")
        (is (false? (deployment-store/cancel-deployment! ds (:id d))))))))

(deftest get-active-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "get-active-deployment returns in-progress deployment"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b1"})]
        (deployment-store/update-deployment-status! ds (:id d) "in-progress")
        (let [active (deployment-store/get-active-deployment ds (:id env))]
          (is (some? active))
          (is (= (:id d) (:id active))))))

    (testing "get-active-deployment returns nil when no active"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b2"})]
        (deployment-store/update-deployment-status! ds (:id d) "succeeded")
        ;; All succeeded, so next call should return nil (no pending/in-progress)
        ;; But the first deployment from previous test is still "in-progress"
        ;; Let's check with a fresh env
        (let [env2 (create-test-env! ds "staging" 20)]
          (is (nil? (deployment-store/get-active-deployment ds (:id env2)))))))))

(deftest deployment-steps-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "add-deployment-step! and get-deployment-steps"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
            s1 (deployment-store/add-deployment-step! ds
                 {:deployment-id (:id d) :step-name "deploy" :step-order 1})
            s2 (deployment-store/add-deployment-step! ds
                 {:deployment-id (:id d) :step-name "verify" :step-order 2})
            steps (deployment-store/get-deployment-steps ds (:id d))]
        (is (= 2 (count steps)))
        (is (= ["deploy" "verify"] (mapv :step-name steps)))))

    (testing "update-step-status! updates step"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b2"})
            s (deployment-store/add-deployment-step! ds
                {:deployment-id (:id d) :step-name "deploy" :step-order 1})]
        (deployment-store/update-step-status! ds (:id s) "in-progress")
        (deployment-store/update-step-status! ds (:id s) "succeeded" :output "Done!")
        (let [steps (deployment-store/get-deployment-steps ds (:id d))
              step (first steps)]
          (is (= "succeeded" (:status step)))
          (is (= "Done!" (:output step))))))))

(deftest get-previous-successful-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "prod" 30)]
    (testing "get-previous-successful-deployment finds last success"
      (let [d1 (deployment-store/create-deployment! ds
                 {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
            _ (deployment-store/update-deployment-status! ds (:id d1) "succeeded" :completed-at true)
            ;; Need different created_at timestamps (SQLite second precision)
            _ (Thread/sleep 1100)
            d2 (deployment-store/create-deployment! ds
                 {:org-id "org-1" :environment-id (:id env) :build-id "b2"})
            _ (deployment-store/update-deployment-status! ds (:id d2) "failed" :completed-at true)
            prev (deployment-store/get-previous-successful-deployment ds (:id env) (:id d2))]
        (is (some? prev))
        (is (= "b1" (:build-id prev)))))

    (testing "get-previous-successful-deployment returns nil when none"
      (let [env2 (create-test-env! ds "dev2" 10)
            d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env2) :build-id "b3"})
            _ (deployment-store/update-deployment-status! ds (:id d) "failed")]
        (is (nil? (deployment-store/get-previous-successful-deployment ds (:id env2) (:id d))))))))

(deftest count-deployments-by-status-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "count-deployments-by-status groups correctly"
      (let [d1 (deployment-store/create-deployment! ds {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
            d2 (deployment-store/create-deployment! ds {:org-id "org-1" :environment-id (:id env) :build-id "b2"})]
        (deployment-store/update-deployment-status! ds (:id d1) "succeeded")
        (let [counts (deployment-store/count-deployments-by-status ds "org-1")]
          (is (= 1 (get counts "succeeded")))
          (is (= 1 (get counts "pending"))))))))
