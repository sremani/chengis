(ns chengis.db.environment-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]))

(def test-db-path "/tmp/chengis-environment-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(deftest create-environment-test
  (let [ds (test-ds)]
    (testing "create-environment! returns environment with correct fields"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Development" :slug "dev"
                   :env-order 10 :description "Dev environment"})]
        (is (some? (:id env)))
        (is (= "org-1" (:org-id env)))
        (is (= "Development" (:name env)))
        (is (= "dev" (:slug env)))
        (is (= 10 (:env-order env)))
        (is (= false (:locked env)))
        (is (= false (:requires-approval env)))
        (is (= false (:auto-promote env)))))

    (testing "create with requires-approval and auto-promote"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Production" :slug "prod"
                   :env-order 30 :requires-approval true :auto-promote true})]
        (is (= true (:requires-approval env)))
        (is (= true (:auto-promote env)))))

    (testing "create with config JSON roundtrip"
      (let [config {:region "us-east-1" :replicas 3}
            env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Staging" :slug "staging"
                   :env-order 20 :config config})]
        (is (= config (:config env)))))))

(deftest get-environment-test
  (let [ds (test-ds)]
    (testing "get-environment retrieves created environment"
      (let [created (env-store/create-environment! ds
                      {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
            retrieved (env-store/get-environment ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))
        (is (= "Dev" (:name retrieved)))))

    (testing "get-environment with org-id scoping"
      (let [created (env-store/create-environment! ds
                      {:org-id "org-2" :name "Staging" :slug "staging-2" :env-order 20})]
        (is (some? (env-store/get-environment ds (:id created) :org-id "org-2")))
        (is (nil? (env-store/get-environment ds (:id created) :org-id "org-other")))))

    (testing "get-environment returns nil for non-existent"
      (is (nil? (env-store/get-environment ds "non-existent"))))))

(deftest get-environment-by-slug-test
  (let [ds (test-ds)]
    (testing "get-environment-by-slug finds by org and slug"
      (env-store/create-environment! ds
        {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
      (let [found (env-store/get-environment-by-slug ds "org-1" "dev")]
        (is (some? found))
        (is (= "dev" (:slug found)))))

    (testing "get-environment-by-slug returns nil for wrong org"
      (is (nil? (env-store/get-environment-by-slug ds "org-other" "dev"))))))

(deftest list-environments-test
  (let [ds (test-ds)]
    (testing "list-environments returns ordered by env_order"
      (env-store/create-environment! ds {:org-id "org-1" :name "Prod" :slug "prod" :env-order 30})
      (env-store/create-environment! ds {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
      (env-store/create-environment! ds {:org-id "org-1" :name "Staging" :slug "staging" :env-order 20})
      (let [envs (env-store/list-environments ds :org-id "org-1")]
        (is (= 3 (count envs)))
        (is (= [10 20 30] (mapv :env-order envs)))))

    (testing "list-environments scoped by org"
      (env-store/create-environment! ds {:org-id "org-2" :name "Other" :slug "other" :env-order 10})
      (is (= 1 (count (env-store/list-environments ds :org-id "org-2")))))))

(deftest update-environment-test
  (let [ds (test-ds)]
    (testing "update-environment! updates fields"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
            cnt (env-store/update-environment! ds (:id env) {:name "Development" :description "Updated"})]
        (is (= 1 cnt))
        (let [updated (env-store/get-environment ds (:id env))]
          (is (= "Development" (:name updated)))
          (is (= "Updated" (:description updated))))))

    (testing "update-environment! with org-id scoping"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Staging" :slug "stg2" :env-order 20})]
        (is (= 0 (env-store/update-environment! ds (:id env) {:name "Changed"} :org-id "wrong-org")))
        (is (= 1 (env-store/update-environment! ds (:id env) {:name "Changed"} :org-id "org-1")))))))

(deftest lock-environment-test
  (let [ds (test-ds)]
    (testing "lock-environment! acquires lock atomically"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Prod" :slug "prod3" :env-order 30})]
        (is (true? (env-store/lock-environment! ds (:id env) "user-1")))
        (let [locked (env-store/get-environment ds (:id env))]
          (is (= true (:locked locked)))
          (is (= "user-1" (:locked-by locked))))))

    (testing "lock-environment! fails if already locked"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Staging" :slug "stg3" :env-order 20})]
        (is (true? (env-store/lock-environment! ds (:id env) "user-1")))
        (is (false? (env-store/lock-environment! ds (:id env) "user-2")))))

    (testing "lock-environment! with org-id scoping"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Dev" :slug "dev3" :env-order 10})]
        (is (false? (env-store/lock-environment! ds (:id env) "user-1" :org-id "wrong-org")))
        (is (true? (env-store/lock-environment! ds (:id env) "user-1" :org-id "org-1")))))))

(deftest unlock-environment-test
  (let [ds (test-ds)]
    (testing "unlock-environment! releases lock"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Prod" :slug "prod4" :env-order 30})]
        (env-store/lock-environment! ds (:id env) "user-1")
        (is (true? (env-store/unlock-environment! ds (:id env))))
        (let [unlocked (env-store/get-environment ds (:id env))]
          (is (= false (:locked unlocked)))
          (is (nil? (:locked-by unlocked))))))))

(deftest delete-environment-test
  (let [ds (test-ds)]
    (testing "delete-environment! removes environment"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Temp" :slug "temp" :env-order 99})]
        (is (true? (env-store/delete-environment! ds (:id env))))
        (is (nil? (env-store/get-environment ds (:id env))))))

    (testing "delete-environment! returns false for non-existent"
      (is (false? (env-store/delete-environment! ds "non-existent"))))

    (testing "delete-environment! with org-id scoping"
      (let [env (env-store/create-environment! ds
                  {:org-id "org-1" :name "Temp2" :slug "temp2" :env-order 99})]
        (is (false? (env-store/delete-environment! ds (:id env) :org-id "wrong-org")))
        (is (true? (env-store/delete-environment! ds (:id env) :org-id "org-1")))))))

(deftest slug-uniqueness-test
  (let [ds (test-ds)]
    (testing "duplicate slug within same org throws"
      (env-store/create-environment! ds {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
      (is (thrown? Exception
            (env-store/create-environment! ds {:org-id "org-1" :name "Dev2" :slug "dev" :env-order 11}))))))

(deftest cross-org-isolation-test
  (let [ds (test-ds)]
    (testing "environments are isolated by org"
      (env-store/create-environment! ds {:org-id "org-1" :name "Dev" :slug "dev" :env-order 10})
      (env-store/create-environment! ds {:org-id "org-2" :name "Dev" :slug "dev" :env-order 10})
      (is (= 1 (count (env-store/list-environments ds :org-id "org-1"))))
      (is (= 1 (count (env-store/list-environments ds :org-id "org-2")))))))
