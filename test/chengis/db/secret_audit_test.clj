(ns chengis.db.secret-audit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.secret-audit :as secret-audit]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-secret-audit-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(deftest log-and-query-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "log a secret access event"
      (let [id (secret-audit/log-secret-access! ds
                 {:secret-name "DB_PASSWORD"
                  :scope "global"
                  :action :read
                  :user-id "user-1"
                  :ip-address "10.0.0.1"})]
        (is (some? id))))

    (testing "log multiple access types"
      (secret-audit/log-secret-access! ds
        {:secret-name "API_KEY" :scope "job-123" :action :write :user-id "user-2"})
      (secret-audit/log-secret-access! ds
        {:secret-name "DB_PASSWORD" :scope "global" :action :build-read})
      (secret-audit/log-secret-access! ds
        {:secret-name "OLD_KEY" :scope "global" :action :delete :user-id "user-1"}))

    (testing "list all accesses"
      (let [accesses (secret-audit/list-secret-accesses ds)]
        (is (= 4 (count accesses)))))

    (testing "filter by secret name"
      (let [accesses (secret-audit/list-secret-accesses ds :secret-name "DB_PASSWORD")]
        (is (= 2 (count accesses)))
        (is (every? #(= "DB_PASSWORD" (:secret-name %)) accesses))))

    (testing "filter by scope"
      (let [accesses (secret-audit/list-secret-accesses ds :scope "job-123")]
        (is (= 1 (count accesses)))
        (is (= "API_KEY" (:secret-name (first accesses))))))

    (testing "count accesses"
      (is (= 4 (secret-audit/count-secret-accesses ds)))
      (is (= 2 (secret-audit/count-secret-accesses ds :secret-name "DB_PASSWORD"))))

    (testing "cleanup old accesses (none old enough)"
      (let [cleaned (secret-audit/cleanup-old-accesses! ds 1)]
        (is (zero? cleaned))
        (is (= 4 (secret-audit/count-secret-accesses ds)))))))
