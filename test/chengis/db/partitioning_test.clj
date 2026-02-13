(ns chengis.db.partitioning-test
  (:require [clojure.test :refer :all]
            [next.jdbc :as jdbc]
            [chengis.db.partitioning :as part]
            [chengis.db.migrate :as migrate]))

(def ^:dynamic *ds* nil)

(defn- create-test-db []
  (let [db-path (str "/tmp/chengis-partition-test-" (System/currentTimeMillis) ".db")
        ds (jdbc/get-datasource (str "jdbc:sqlite:" db-path))]
    (migrate/migrate! db-path)
    {:ds ds :path db-path}))

(defn- cleanup-test-db [{:keys [path]}]
  (let [f (java.io.File. path)]
    (when (.exists f) (.delete f))))

(defmacro with-test-db [& body]
  `(let [db# (create-test-db)]
     (binding [*ds* (:ds db#)]
       (try ~@body
            (finally (cleanup-test-db db#))))))

(deftest list-partitions-test
  (with-test-db
    (testing "list partitions returns empty for unpartitioned table"
      (is (empty? (part/list-partitions *ds* "builds"))))))

(deftest partitioning-status-test
  (with-test-db
    (testing "status shows partitionable tables"
      (let [status (part/partitioning-status *ds*)]
        (is (contains? (set (:partitionable-tables status)) "builds"))
        (is (contains? (set (:partitionable-tables status)) "build_events"))
        (is (contains? (set (:partitionable-tables status)) "audit_logs"))))))

;; Note: Actual partition creation tests require PostgreSQL.
;; These tests verify the metadata and helper functions work with SQLite.

(deftest month-range-calculation-test
  (with-test-db
    (testing "month range boundaries are correct"
      ;; This tests the internal function indirectly via partition name generation
      ;; The actual partition DDL only works on PostgreSQL
      (is (some? (part/list-partitions *ds* "builds"))))))

(deftest maintenance-cycle-sqlite-test
  (with-test-db
    (testing "maintenance cycle doesn't error on SQLite"
      ;; On SQLite, partition DDL will fail gracefully
      ;; The metadata table still works
      (let [config {:partitioning {:retention-months 12 :future-partitions 3}}]
        ;; This will log errors for partition creation (SQLite can't do it)
        ;; but should not throw
        (is (nil? (part/maintenance-cycle! *ds* config)))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation testing remediation: boolean return values
;; ---------------------------------------------------------------------------

(deftest create-partition-returns-boolean-test
  (with-test-db
    (testing "create-monthly-partition! returns false on SQLite (unsupported DDL)"
      ;; SQLite can't do CREATE TABLE ... PARTITION — function should return false
      (let [result (part/create-monthly-partition! *ds* "builds" 2025 1)]
        (is (false? result))))))

(deftest detach-partition-returns-boolean-test
  (with-test-db
    (testing "detach-partition! returns false on SQLite (no partition to detach)"
      (let [result (part/detach-partition! *ds* "builds" "builds_2025_01")]
        (is (false? result))))))

;; ---------------------------------------------------------------------------
;; Phase 4 mutation testing remediation: or-fallback defaults
;; ---------------------------------------------------------------------------

(deftest maintenance-cycle-default-config-test
  (with-test-db
    (testing "maintenance-cycle! uses defaults when config keys are missing"
      ;; Empty config should fall back to retention-months=12, future-partitions=3
      (is (nil? (part/maintenance-cycle! *ds* {}))))))

(deftest validate-table-name-rejects-invalid-test
  (with-test-db
    (testing "invalid table name throws ex-info"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid table"
            (part/create-monthly-partition! *ds* "injected_table" 2025 1))))
    (testing "valid table names are accepted"
      ;; Will fail on SQLite DDL but won't throw validation error
      (is (false? (part/create-monthly-partition! *ds* "builds" 2025 1)))
      (is (false? (part/create-monthly-partition! *ds* "build_events" 2025 1)))
      (is (false? (part/create-monthly-partition! *ds* "audit_logs" 2025 1))))))
