(ns chengis.db.strategy-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.strategy-store :as strategy-store]))

(def test-db-path "/tmp/chengis-strategy-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(deftest create-strategy-test
  (let [ds (test-ds)]
    (testing "create-strategy! returns strategy with correct fields"
      (let [s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Direct Deploy" :strategy-type "direct"
                 :description "Immediate replacement" :config {}})]
        (is (some? (:id s)))
        (is (= "org-1" (:org-id s)))
        (is (= "Direct Deploy" (:name s)))
        (is (= "direct" (:strategy-type s)))
        (is (= {} (:config s)))))

    (testing "create with complex config"
      (let [config {:increments [10 25 50 100] :interval-ms 60000}
            s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Canary" :strategy-type "canary" :config config})]
        (is (= config (:config s)))))))

(deftest get-strategy-test
  (let [ds (test-ds)]
    (testing "get-strategy retrieves created strategy"
      (let [created (strategy-store/create-strategy! ds
                      {:org-id "org-1" :name "BG" :strategy-type "blue-green"})
            retrieved (strategy-store/get-strategy ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-strategy with org-id scoping"
      (let [created (strategy-store/create-strategy! ds
                      {:org-id "org-2" :name "Rolling" :strategy-type "rolling"})]
        (is (some? (strategy-store/get-strategy ds (:id created) :org-id "org-2")))
        (is (nil? (strategy-store/get-strategy ds (:id created) :org-id "org-other")))))))

(deftest list-strategies-test
  (let [ds (test-ds)]
    (testing "list-strategies returns all for org"
      (strategy-store/create-strategy! ds {:org-id "org-1" :name "S1" :strategy-type "direct"})
      (strategy-store/create-strategy! ds {:org-id "org-1" :name "S2" :strategy-type "canary"})
      (strategy-store/create-strategy! ds {:org-id "org-2" :name "S3" :strategy-type "direct"})
      (is (= 2 (count (strategy-store/list-strategies ds :org-id "org-1"))))
      (is (= 1 (count (strategy-store/list-strategies ds :org-id "org-2")))))

    (testing "list-strategies filters by type"
      (is (= 1 (count (strategy-store/list-strategies ds :org-id "org-1" :strategy-type "canary")))))))

(deftest update-strategy-test
  (let [ds (test-ds)]
    (testing "update-strategy! updates fields"
      (let [s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Old" :strategy-type "direct"})
            cnt (strategy-store/update-strategy! ds (:id s) {:name "New" :description "Updated"})]
        (is (= 1 cnt))
        (let [updated (strategy-store/get-strategy ds (:id s))]
          (is (= "New" (:name updated)))
          (is (= "Updated" (:description updated))))))

    (testing "update-strategy! with config roundtrip"
      (let [s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Canary2" :strategy-type "canary" :config {:a 1}})
            new-config {:a 2 :b 3}]
        (strategy-store/update-strategy! ds (:id s) {:config new-config})
        (let [updated (strategy-store/get-strategy ds (:id s))]
          (is (= new-config (:config updated))))))))

(deftest delete-strategy-test
  (let [ds (test-ds)]
    (testing "delete-strategy! removes strategy"
      (let [s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Temp" :strategy-type "direct"})]
        (is (true? (strategy-store/delete-strategy! ds (:id s))))
        (is (nil? (strategy-store/get-strategy ds (:id s))))))

    (testing "delete-strategy! with org-id scoping"
      (let [s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Temp2" :strategy-type "direct"})]
        (is (false? (strategy-store/delete-strategy! ds (:id s) :org-id "wrong-org")))
        (is (true? (strategy-store/delete-strategy! ds (:id s) :org-id "org-1")))))))

(deftest seed-default-strategies-test
  (let [ds (test-ds)]
    (testing "seed-default-strategies! creates 4 default strategies"
      (strategy-store/seed-default-strategies! ds "org-1")
      (let [strategies (strategy-store/list-strategies ds :org-id "org-1")]
        (is (= 4 (count strategies)))
        (is (= #{"direct" "blue-green" "canary" "rolling"}
               (set (map :strategy-type strategies))))))

    (testing "seed-default-strategies! is idempotent"
      (strategy-store/seed-default-strategies! ds "org-1")
      (is (= 4 (count (strategy-store/list-strategies ds :org-id "org-1")))))))

(deftest name-uniqueness-test
  (let [ds (test-ds)]
    (testing "duplicate name within same org throws"
      (strategy-store/create-strategy! ds {:org-id "org-1" :name "Direct" :strategy-type "direct"})
      (is (thrown? Exception
            (strategy-store/create-strategy! ds {:org-id "org-1" :name "Direct" :strategy-type "rolling"}))))))

(deftest config-roundtrip-test
  (let [ds (test-ds)]
    (testing "complex config survives JSON roundtrip"
      (let [config {:switch-timeout-ms 30000 :keep-old-ms 300000 :nested {:a [1 2 3]}}
            s (strategy-store/create-strategy! ds
                {:org-id "org-1" :name "Complex" :strategy-type "blue-green" :config config})
            retrieved (strategy-store/get-strategy ds (:id s))]
        (is (= config (:config retrieved)))))))
