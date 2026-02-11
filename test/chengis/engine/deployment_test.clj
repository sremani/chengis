(ns chengis.engine.deployment-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]
            [chengis.db.deployment-store :as deployment-store]
            [chengis.db.strategy-store :as strategy-store]
            [chengis.engine.deployment :as deployment-engine]))

(def test-db-path "/tmp/chengis-deployment-engine-test.db")

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

(deftest execute-deployment-direct-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)
        system {:db ds}]
    (testing "direct deployment succeeds"
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env)
                 :build-id "build-1" :initiated-by "user-1"})
            result (deployment-engine/execute-deployment! system (:id d))]
        (is (true? (:success result)))
        (let [updated (deployment-store/get-deployment ds (:id d))]
          (is (= "succeeded" (:status updated)))
          (is (some? (:started-at updated)))
          (is (some? (:completed-at updated))))
        ;; Verify steps were created
        (let [steps (deployment-store/get-deployment-steps ds (:id d))]
          (is (>= (count steps) 1)))
        ;; Verify environment is unlocked after
        (let [e (env-store/get-environment ds (:id env))]
          (is (= false (:locked e))))))))

(deftest execute-deployment-with-strategy-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging" 20)
        system {:db ds}]
    (testing "blue-green strategy creates multiple steps"
      (let [strategy (strategy-store/create-strategy! ds
                       {:org-id "org-1" :name "BG" :strategy-type "blue-green"})
            d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env)
                 :build-id "build-2" :strategy-id (:id strategy)
                 :initiated-by "user-1"})
            result (deployment-engine/execute-deployment! system (:id d))]
        (is (true? (:success result)))
        (let [steps (deployment-store/get-deployment-steps ds (:id d))]
          (is (= 4 (count steps)))
          (is (every? #(= "succeeded" (:status %)) steps)))))))

(deftest execute-deployment-concurrent-prevention-test
  (let [ds (test-ds)
        env (create-test-env! ds "prod" 30)
        system {:db ds}]
    (testing "rejects when environment is already locked"
      ;; Manually lock the env
      (env-store/lock-environment! ds (:id env) "other-user")
      (let [d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env)
                 :build-id "build-3" :initiated-by "user-1"})
            result (deployment-engine/execute-deployment! system (:id d))]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "locked"))))))

(deftest execute-deployment-nonexistent-test
  (let [ds (test-ds)
        system {:db ds}]
    (testing "rejects non-existent deployment"
      (let [result (deployment-engine/execute-deployment! system "non-existent")]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "not found"))))))

(deftest rollback-deployment-test
  (let [ds (test-ds)
        env (create-test-env! ds "prod2" 30)
        system {:db ds}]
    (testing "rollback creates reverse deployment"
      (let [d1 (deployment-store/create-deployment! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :build-id "build-old" :initiated-by "user-1"})
            _ (deployment-engine/execute-deployment! system (:id d1))
            ;; Need different timestamps for created_at comparison (SQLite second precision)
            _ (Thread/sleep 1100)
            d2 (deployment-store/create-deployment! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :build-id "build-new" :initiated-by "user-1"})
            _ (deployment-engine/execute-deployment! system (:id d2))
            ;; Force d2 to failed for rollback scenario
            _ (deployment-store/update-deployment-status! ds (:id d2) "failed")
            result (deployment-engine/rollback-deployment! system (:id d2))]
        (is (true? (:success result)))))

    (testing "rollback fails when no previous successful deployment"
      (let [env2 (create-test-env! ds "dev2" 10)
            d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env2) :build-id "b-new" :initiated-by "user-1"})
            _ (deployment-store/update-deployment-status! ds (:id d) "failed")
            result (deployment-engine/rollback-deployment! system (:id d))]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "No previous"))))))

(deftest cancel-deployment-test
  (let [ds (test-ds)]
    (testing "cancel-deployment! cancels pending deployment"
      (let [env (create-test-env! ds "dev3" 10)
            d (deployment-store/create-deployment! ds
                {:org-id "org-1" :environment-id (:id env) :build-id "b1"})
            result (deployment-engine/cancel-deployment! ds (:id d))]
        (is (true? (:success result)))))

    (testing "cancel-deployment! fails for non-existent"
      (let [result (deployment-engine/cancel-deployment! ds "non-existent")]
        (is (false? (:success result)))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation remediation: boolean return values
;; ---------------------------------------------------------------------------

(deftest rollback-nonexistent-deployment-test
  (let [ds (test-ds)]
    (testing "rollback returns false success for non-existent deployment"
      (let [result (deployment-engine/rollback-deployment!
                     {:db ds :config {}} "nonexistent-id")]
        (is (false? (:success result))
            ":success must be false (not true) for missing deployment")
        (is (string? (:reason result)))))))
