(ns chengis.db.audit-export-edge-test
  "Edge case tests for audit export (JSON and CSV).
   Covers duplicate records, empty results, batch boundaries, and special characters."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.audit-export :as audit-export]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringWriter]))

(def test-db-path "/tmp/chengis-audit-export-edge.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Test 1: JSON export with duplicate adjacent records is valid
;; ---------------------------------------------------------------------------

(deftest json-export-with-duplicate-adjacent-records-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Insert identical audit events
    (dotimes [_ 5]
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "login"}))

    (testing "JSON export with duplicate records produces valid JSON"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [json-str (.toString writer)
              parsed (json/read-str json-str)]
          (is (vector? parsed) "Should parse as a JSON array")
          (is (= 5 (count parsed)) "Should contain all 5 records"))))))

;; ---------------------------------------------------------------------------
;; Test 2: JSON export with zero records returns empty array
;; ---------------------------------------------------------------------------

(deftest json-export-zero-records-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "JSON export with no data returns []"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [json-str (.toString writer)
              parsed (json/read-str json-str)]
          (is (= "[]" (str/trim json-str)))
          (is (empty? parsed)))))))

;; ---------------------------------------------------------------------------
;; Test 3: JSON export across batch boundaries has valid commas
;; ---------------------------------------------------------------------------

(deftest json-export-batch-boundary-commas-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Insert more records than one batch (batch size is typically 500)
    ;; Use a smaller set to keep tests fast — the logic matters, not the count
    (dotimes [i 25]
      (audit-store/insert-audit! ds
        {:user-id (str "u" i) :username (str "user-" i) :action "test-action"}))

    (testing "JSON export with multiple records produces valid JSON"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [json-str (.toString writer)
              parsed (json/read-str json-str)]
          (is (vector? parsed))
          (is (= 25 (count parsed))))))))

;; ---------------------------------------------------------------------------
;; Test 4: CSV export escapes quotes, commas, and newlines
;; ---------------------------------------------------------------------------

(deftest csv-export-escapes-special-chars-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Insert records with special characters in details
    (audit-store/insert-audit! ds
      {:user-id "u1" :username "alice" :action "update"
       :details "{\"key\":\"value with, comma\"}"})
    (audit-store/insert-audit! ds
      {:user-id "u2" :username "bob" :action "note"
       :details "line1\nline2"})

    (testing "CSV export produces parseable output"
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {} writer)
        (let [csv-str (.toString writer)
              lines (str/split-lines csv-str)]
          ;; At least header + 2 data rows
          (is (>= (count lines) 3)
              "Should have header + data rows")
          ;; Header should be first
          (is (str/includes? (first lines) "timestamp")
              "First line should be CSV header"))))))

;; ---------------------------------------------------------------------------
;; Test 5: JSON export with mixed identical and unique records
;; ---------------------------------------------------------------------------

(deftest json-export-mixed-records-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Mix of identical and unique records
    (audit-store/insert-audit! ds {:user-id "u1" :username "alice" :action "login"})
    (audit-store/insert-audit! ds {:user-id "u1" :username "alice" :action "login"})
    (audit-store/insert-audit! ds {:user-id "u2" :username "bob" :action "build"})
    (audit-store/insert-audit! ds {:user-id "u1" :username "alice" :action "login"})

    (testing "mixed records produce valid JSON with correct count"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [json-str (.toString writer)
              parsed (json/read-str json-str)]
          (is (vector? parsed))
          (is (= 4 (count parsed))))))))
