(ns chengis.db.audit-hash-chain-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-audit-hash-chain-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest genesis-entry-has-nil-prev-hash
  (let [ds (conn/create-datasource test-db-path)]
    (testing "first audit entry has nil prev_hash"
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "login"
         :resource-type "session" :resource-id "s1"})
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM audit_logs ORDER BY timestamp ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})
            first-row (first rows)]
        (is (= 1 (count rows)))
        (is (nil? (:prev-hash first-row)) "Genesis entry should have nil prev_hash")
        (is (some? (:entry-hash first-row)) "Genesis entry should have an entry_hash")
        (is (= 64 (count (:entry-hash first-row))) "Hash should be 64 hex chars")))))

(deftest hash-chain-links-correctly
  (let [ds (conn/create-datasource test-db-path)]
    (testing "second entry links to first entry's hash"
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "login"
         :resource-type "session" :resource-id "s1"})
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "logout"
         :resource-type "session" :resource-id "s1"})
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM audit_logs ORDER BY timestamp ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})
            [first-row second-row] rows]
        (is (= 2 (count rows)))
        ;; Second entry's prev_hash should be first entry's entry_hash
        (is (= (:entry-hash first-row) (:prev-hash second-row)))
        ;; All hashes should be different
        (is (not= (:entry-hash first-row) (:entry-hash second-row)))))))

(deftest three-entry-chain-integrity
  (let [ds (conn/create-datasource test-db-path)]
    (testing "chain of three entries maintains integrity"
      (dotimes [i 3]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action (str "action-" i)
           :resource-type "test" :resource-id (str "r" i)}))
      (let [rows (jdbc/execute! ds
                   ["SELECT * FROM audit_logs ORDER BY timestamp ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})]
        (is (= 3 (count rows)))
        ;; Genesis entry
        (is (nil? (:prev-hash (first rows))))
        ;; Chain links
        (is (= (:entry-hash (nth rows 0)) (:prev-hash (nth rows 1))))
        (is (= (:entry-hash (nth rows 1)) (:prev-hash (nth rows 2))))
        ;; All entry_hashes should be unique
        (is (= 3 (count (set (map :entry-hash rows)))))))))
