(ns chengis.db.query-router-test
  (:require [clojure.test :refer :all]
            [chengis.db.query-router :as qr]))

(deftest read-ds-test
  (testing "returns replica when available"
    (let [primary {:name "primary"}
          replica {:name "replica"}
          ds (qr/routed-datasource primary replica)]
      (is (= replica (qr/read-ds ds)))))

  (testing "returns primary when no replica"
    (let [primary {:name "primary"}
          ds (qr/routed-datasource primary nil)]
      (is (= primary (qr/read-ds ds)))))

  (testing "passes through non-routed datasource"
    (let [plain-ds {:name "plain"}]
      (is (= plain-ds (qr/read-ds plain-ds))))))

(deftest write-ds-test
  (testing "always returns primary"
    (let [primary {:name "primary"}
          replica {:name "replica"}
          ds (qr/routed-datasource primary replica)]
      (is (= primary (qr/write-ds ds)))))

  (testing "returns primary even without replica"
    (let [primary {:name "primary"}
          ds (qr/routed-datasource primary nil)]
      (is (= primary (qr/write-ds ds)))))

  (testing "passes through non-routed datasource"
    (let [plain-ds {:name "plain"}]
      (is (= plain-ds (qr/write-ds plain-ds))))))

(deftest has-replica-test
  (testing "true when replica configured"
    (let [ds (qr/routed-datasource {:name "p"} {:name "r"})]
      (is (true? (qr/has-replica? ds)))))

  (testing "false when no replica"
    (let [ds (qr/routed-datasource {:name "p"} nil)]
      (is (false? (qr/has-replica? ds)))))

  (testing "false for plain datasource"
    (is (false? (qr/has-replica? {:name "plain"})))))

(deftest routed-datasource-test
  (testing "creates a RoutedDatasource record"
    (let [ds (qr/routed-datasource :primary :replica)]
      (is (instance? chengis.db.query_router.RoutedDatasource ds))
      (is (= :primary (:primary ds)))
      (is (= :replica (:replica ds))))))

(deftest idempotent-routing-test
  (testing "read-ds on already extracted ds returns same"
    (let [primary {:name "primary"}
          replica {:name "replica"}
          ds (qr/routed-datasource primary replica)
          read (qr/read-ds ds)]
      (is (= replica read))
      ;; Calling read-ds on a plain DS just returns it
      (is (= replica (qr/read-ds read))))))

(deftest nil-datasource-test
  (testing "read-ds handles nil gracefully"
    (is (nil? (qr/read-ds nil))))

  (testing "write-ds handles nil gracefully"
    (is (nil? (qr/write-ds nil)))))
