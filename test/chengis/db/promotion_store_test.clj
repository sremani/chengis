(ns chengis.db.promotion-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]
            [chengis.db.promotion-store :as promotion-store]))

(def test-db-path "/tmp/chengis-promotion-store-test.db")

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

(deftest create-promotion-test
  (let [ds (test-ds)
        dev (create-test-env! ds "dev" 10)
        staging (create-test-env! ds "staging" 20)]
    (testing "create-promotion! returns promotion with correct fields"
      (let [p (promotion-store/create-promotion! ds
                {:org-id "org-1" :build-id "build-1"
                 :from-environment-id (:id dev) :to-environment-id (:id staging)
                 :promoted-by "user-1"})]
        (is (some? (:id p)))
        (is (= "org-1" (:org-id p)))
        (is (= "build-1" (:build-id p)))
        (is (= "pending" (:status p)))))))

(deftest get-promotion-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "get-promotion retrieves created promotion"
      (let [created (promotion-store/create-promotion! ds
                      {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)})
            retrieved (promotion-store/get-promotion ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-promotion with org-id scoping"
      (let [created (promotion-store/create-promotion! ds
                      {:org-id "org-2" :build-id "b2" :to-environment-id (:id env)})]
        (is (some? (promotion-store/get-promotion ds (:id created) :org-id "org-2")))
        (is (nil? (promotion-store/get-promotion ds (:id created) :org-id "org-other")))))))

(deftest list-promotions-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "list-promotions filters by org"
      (promotion-store/create-promotion! ds {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)})
      (promotion-store/create-promotion! ds {:org-id "org-1" :build-id "b2" :to-environment-id (:id env)})
      (promotion-store/create-promotion! ds {:org-id "org-2" :build-id "b3" :to-environment-id (:id env)})
      (is (= 2 (count (promotion-store/list-promotions ds :org-id "org-1"))))
      (is (= 1 (count (promotion-store/list-promotions ds :org-id "org-2")))))))

(deftest approve-promotion-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging" 20)]
    (testing "approve-promotion! transitions pending to approved"
      (let [p (promotion-store/create-promotion! ds
                {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)})]
        (is (true? (promotion-store/approve-promotion! ds (:id p) "approver-1")))
        (let [approved (promotion-store/get-promotion ds (:id p))]
          (is (= "approved" (:status approved))))))

    (testing "approve-promotion! fails for non-pending"
      (let [p (promotion-store/create-promotion! ds
                {:org-id "org-1" :build-id "b2" :to-environment-id (:id env)})]
        (promotion-store/approve-promotion! ds (:id p) "user-1")
        (is (false? (promotion-store/approve-promotion! ds (:id p) "user-2")))))))

(deftest reject-promotion-test
  (let [ds (test-ds)
        env (create-test-env! ds "prod" 30)]
    (testing "reject-promotion! transitions pending to rejected"
      (let [p (promotion-store/create-promotion! ds
                {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)})]
        (is (true? (promotion-store/reject-promotion! ds (:id p) "Not ready")))
        (let [rejected (promotion-store/get-promotion ds (:id p))]
          (is (= "rejected" (:status rejected)))
          (is (= "Not ready" (:rejection-reason rejected))))))))

(deftest complete-promotion-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging" 20)]
    (testing "complete-promotion! creates environment artifact"
      (let [p (promotion-store/create-promotion! ds
                {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)
                 :promoted-by "user-1"})]
        (is (true? (promotion-store/complete-promotion! ds (:id p))))
        (let [current (promotion-store/get-current-artifact ds (:id env))]
          (is (some? current))
          (is (= "b1" (:build-id current)))
          (is (= "active" (:status current))))))

    (testing "complete-promotion! supersedes previous active"
      (let [p2 (promotion-store/create-promotion! ds
                 {:org-id "org-1" :build-id "b2" :to-environment-id (:id env)
                  :promoted-by "user-1"})]
        (promotion-store/complete-promotion! ds (:id p2))
        (let [current (promotion-store/get-current-artifact ds (:id env))
              history (promotion-store/list-environment-history ds (:id env))]
          (is (= "b2" (:build-id current)))
          (is (= 2 (count history)))
          (is (= 1 (count (filter #(= "superseded" (:status %)) history)))))))))

(deftest get-current-artifact-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "get-current-artifact returns nil when no deployments"
      (is (nil? (promotion-store/get-current-artifact ds (:id env)))))))

(deftest list-environment-history-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "list-environment-history returns empty for new environment"
      (is (empty? (promotion-store/list-environment-history ds (:id env)))))))

(deftest count-promotions-by-status-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev" 10)]
    (testing "count-promotions-by-status groups correctly"
      (promotion-store/create-promotion! ds {:org-id "org-1" :build-id "b1" :to-environment-id (:id env)})
      (promotion-store/create-promotion! ds {:org-id "org-1" :build-id "b2" :to-environment-id (:id env)})
      (let [p3 (promotion-store/create-promotion! ds {:org-id "org-1" :build-id "b3" :to-environment-id (:id env)})]
        (promotion-store/approve-promotion! ds (:id p3) "user-1")
        (let [counts (promotion-store/count-promotions-by-status ds "org-1")]
          (is (= 2 (get counts "pending")))
          (is (= 1 (get counts "approved"))))))))
