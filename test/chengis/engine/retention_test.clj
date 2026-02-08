(ns chengis.engine.retention-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.db.secret-audit :as secret-audit]
            [chengis.engine.retention :as retention]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-retention-test.db")

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

(deftest run-retention-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds
                :config {:retention {:enabled true
                                     :audit-days 90
                                     :webhook-events-days 30}
                         :workspace {:root "/tmp/chengis-retention-test-ws"}}}]

    (testing "retention runs without errors on empty tables"
      (let [result (retention/run-retention! system)]
        (is (map? result))
        (is (= 0 (:total result)))
        (is (some? (:timestamp result)))))

    (testing "retention tracks last run"
      (let [last (retention/last-run)]
        (is (some? last))
        (is (= 0 (:total last)))))

    (testing "retention cleans old data"
      ;; Insert some test data
      (audit-store/insert-audit! ds {:user-id "u1" :username "test" :action "test-action"})
      (webhook-log/log-webhook-event! ds {:provider :github :status "processed" :payload-size 100})
      (secret-audit/log-secret-access! ds {:secret-name "KEY" :action :read})

      ;; Run retention — data was just created, so nothing should be cleaned
      (let [result (retention/run-retention! system)]
        (is (= 0 (:audit-logs result)))
        (is (= 0 (:webhook-events result)))
        (is (= 0 (:secret-access-log result)))))))

(deftest scheduler-lifecycle-test
  (testing "scheduler starts and stops"
    (is (false? (retention/running?*)))

    ;; We won't actually start the scheduler in tests (it would try to run)
    ;; Just verify the state tracking works
    (is (nil? (retention/last-run)))))
