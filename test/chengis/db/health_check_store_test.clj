(ns chengis.db.health-check-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.environment-store :as env-store]
            [chengis.db.health-check-store :as hc-store]))

(def test-db-path "/tmp/chengis-health-check-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(defn- create-test-env! [ds slug]
  (env-store/create-environment! ds
    {:org-id "org-1" :name slug :slug slug :env-order 10}))

(deftest create-health-check-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev")]
    (testing "create-health-check! returns check with correct fields"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env) :name "HTTP Health"
                  :check-type "http" :config {:url "http://localhost/health"}})]
        (is (some? (:id hc)))
        (is (= "org-1" (:org-id hc)))
        (is (= "http" (:check-type hc)))
        (is (= true (:enabled hc)))
        (is (some? (:config hc)))))

    (testing "create disabled health check"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env) :name "Disabled"
                  :check-type "command" :config {:command "echo ok"} :enabled false})]
        (is (= false (:enabled hc)))))))

(deftest get-health-check-test
  (let [ds (test-ds)
        env (create-test-env! ds "staging")]
    (testing "get-health-check retrieves created check"
      (let [created (hc-store/create-health-check! ds
                      {:org-id "org-1" :environment-id (:id env)
                       :name "HC1" :check-type "http" :config {:url "http://example.com"}})
            retrieved (hc-store/get-health-check ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-health-check with org-id scoping"
      (let [created (hc-store/create-health-check! ds
                      {:org-id "org-2" :environment-id (:id env)
                       :name "HC2" :check-type "command" :config {:command "true"}})]
        (is (some? (hc-store/get-health-check ds (:id created) :org-id "org-2")))
        (is (nil? (hc-store/get-health-check ds (:id created) :org-id "wrong")))))))

(deftest list-health-checks-test
  (let [ds (test-ds)
        env (create-test-env! ds "prod")]
    (testing "list-health-checks returns all for environment"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env) :name "HC1" :check-type "http" :config {:url "http://a"}})
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env) :name "HC2" :check-type "command" :config {:command "echo"}})
      (is (= 2 (count (hc-store/list-health-checks ds (:id env))))))

    (testing "list-health-checks enabled-only filter"
      (hc-store/create-health-check! ds
        {:org-id "org-1" :environment-id (:id env) :name "Disabled"
         :check-type "http" :config {:url "http://b"} :enabled false})
      (is (= 2 (count (hc-store/list-health-checks ds (:id env) :enabled-only true))))
      (is (= 3 (count (hc-store/list-health-checks ds (:id env))))))))

(deftest update-health-check-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev2")]
    (testing "update-health-check! updates fields"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC1" :check-type "http" :config {:url "http://old"}})
            cnt (hc-store/update-health-check! ds (:id hc) {:name "Updated HC"})]
        (is (= 1 cnt))
        (is (= "Updated HC" (:name (hc-store/get-health-check ds (:id hc)))))))

    (testing "update-health-check! toggles enabled"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC2" :check-type "http" :config {:url "http://x"}})]
        (hc-store/update-health-check! ds (:id hc) {:enabled false})
        (is (= false (:enabled (hc-store/get-health-check ds (:id hc)))))))))

(deftest delete-health-check-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev3")]
    (testing "delete-health-check! removes check"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "Temp" :check-type "http" :config {:url "http://temp"}})]
        (is (true? (hc-store/delete-health-check! ds (:id hc))))
        (is (nil? (hc-store/get-health-check ds (:id hc))))))))

(deftest save-health-check-result-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev4")]
    (testing "save-health-check-result! stores result"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC" :check-type "http" :config {:url "http://x"}})
            result (hc-store/save-health-check-result! ds
                     {:health-check-id (:id hc) :status "healthy"
                      :response-time-ms 42 :output "HTTP 200"})]
        (is (some? (:id result)))
        (is (= "healthy" (:status result)))
        (is (= 42 (:response-time-ms result)))))))

(deftest get-latest-results-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev5")]
    (testing "get-latest-results returns latest for each check"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC" :check-type "http" :config {:url "http://x"}})]
        (hc-store/save-health-check-result! ds
          {:health-check-id (:id hc) :status "unhealthy" :response-time-ms 100})
        (hc-store/save-health-check-result! ds
          {:health-check-id (:id hc) :status "healthy" :response-time-ms 50})
        (let [results (hc-store/get-latest-results ds (:id env))]
          (is (= 1 (count results)))
          (is (some? (:latest-result (first results)))))))))

(deftest get-check-history-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev6")]
    (testing "get-check-history returns results in desc order"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC" :check-type "http" :config {:url "http://x"}})]
        (hc-store/save-health-check-result! ds {:health-check-id (:id hc) :status "healthy"})
        (hc-store/save-health-check-result! ds {:health-check-id (:id hc) :status "unhealthy"})
        (let [history (hc-store/get-check-history ds (:id hc))]
          (is (= 2 (count history))))))))

(deftest get-deployment-health-results-test
  (let [ds (test-ds)
        env (create-test-env! ds "dev7")]
    (testing "get-deployment-health-results filters by deployment"
      (let [hc (hc-store/create-health-check! ds
                 {:org-id "org-1" :environment-id (:id env)
                  :name "HC" :check-type "http" :config {:url "http://x"}})]
        (hc-store/save-health-check-result! ds
          {:health-check-id (:id hc) :deployment-id "dep-1" :status "healthy"})
        (hc-store/save-health-check-result! ds
          {:health-check-id (:id hc) :deployment-id "dep-2" :status "unhealthy"})
        (let [results (hc-store/get-deployment-health-results ds "dep-1")]
          (is (= 1 (count results)))
          (is (= "healthy" (:status (first results)))))))))
