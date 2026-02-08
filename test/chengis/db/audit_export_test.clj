(ns chengis.db.audit-export-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.audit-export :as audit-export]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io StringWriter]))

(def test-db-path "/tmp/chengis-audit-export-test.db")

(defn setup [f]
  (let [db-file (clojure.java.io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (clojure.java.io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup)

(defn- insert-test-audit! [ds {:keys [username action resource-type resource-id detail ip-address]
                               :or {username "testuser" action "test-action"
                                    resource-type "build" resource-id "123"
                                    ip-address "127.0.0.1"}}]
  (audit-store/insert-audit! ds
    {:user-id "user-1"
     :username username
     :action action
     :resource-type resource-type
     :resource-id resource-id
     :detail detail
     :ip-address ip-address
     :user-agent "test-agent"}))

(deftest csv-export-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "CSV export with no records produces header only"
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {} writer)
        (let [output (str writer)
              lines (str/split-lines output)]
          (is (= 1 (count lines)))
          (is (= "timestamp,username,action,resource_type,resource_id,ip_address,detail"
                 (first lines))))))

    (testing "CSV export with records produces header + data rows"
      (insert-test-audit! ds {:username "alice" :action "login"
                              :resource-type "session" :resource-id "s1"})
      (insert-test-audit! ds {:username "bob" :action "trigger-build"
                              :resource-type "build" :resource-id "b1"
                              :detail {:job "my-job"}})
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {} writer)
        (let [output (str writer)
              lines (str/split-lines output)]
          (is (= 3 (count lines)))  ;; header + 2 rows
          ;; Header is correct
          (is (str/starts-with? (first lines) "timestamp,"))
          ;; Data rows contain expected values
          (is (some #(str/includes? % "alice") lines))
          (is (some #(str/includes? % "bob") lines))
          (is (some #(str/includes? % "login") lines))
          (is (some #(str/includes? % "trigger-build") lines)))))

    (testing "CSV escapes fields with commas and quotes"
      (insert-test-audit! ds {:username "carol" :action "update"
                              :detail {:message "hello, world" :note "with \"quotes\""}})
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {:action "update"} writer)
        (let [output (str writer)]
          ;; Detail field should be quoted due to commas/quotes in EDN representation
          (is (str/includes? output "carol")))))))

(deftest json-export-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "JSON export with no records produces empty array"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [output (str writer)
              parsed (json/read-str output :key-fn keyword)]
          (is (= [] parsed)))))

    (testing "JSON export with records produces valid JSON array"
      (insert-test-audit! ds {:username "alice" :action "login"
                              :resource-type "session" :resource-id "s1"})
      (insert-test-audit! ds {:username "bob" :action "build"
                              :resource-type "build" :resource-id "b1"
                              :detail {:status "success"}})
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {} writer)
        (let [output (str writer)
              parsed (json/read-str output :key-fn keyword)]
          (is (vector? parsed))
          (is (= 2 (count parsed)))
          ;; Each record has expected keys
          (is (every? :timestamp parsed))
          (is (every? :username parsed))
          (is (every? :action parsed))
          ;; Values are correct
          (is (some #(= "alice" (:username %)) parsed))
          (is (some #(= "bob" (:username %)) parsed)))))))

(deftest filter-passthrough-test
  (let [ds (conn/create-datasource test-db-path)]
    (insert-test-audit! ds {:username "alice" :action "login"})
    (insert-test-audit! ds {:username "bob" :action "trigger-build"})
    (insert-test-audit! ds {:username "alice" :action "logout"})

    (testing "CSV export with action filter"
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {:action "login"} writer)
        (let [lines (str/split-lines (str writer))]
          (is (= 2 (count lines)))  ;; header + 1 matching row
          (is (str/includes? (second lines) "alice"))
          (is (str/includes? (second lines) "login")))))

    (testing "JSON export with action filter"
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {:action "trigger-build"} writer)
        (let [parsed (json/read-str (str writer) :key-fn keyword)]
          (is (= 1 (count parsed)))
          (is (= "bob" (:username (first parsed)))))))))

(deftest export-count-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count returns zero for empty table"
      (is (= 0 (audit-export/export-count ds {}))))

    (testing "count returns correct number"
      (insert-test-audit! ds {:action "a"})
      (insert-test-audit! ds {:action "b"})
      (insert-test-audit! ds {:action "a"})
      (is (= 3 (audit-export/export-count ds {})))
      (is (= 2 (audit-export/export-count ds {:action "a"})))
      (is (= 1 (audit-export/export-count ds {:action "b"}))))))

(deftest large-batch-export-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "export handles more records than batch size"
      ;; Insert 520 records (more than the 500 batch size)
      (dotimes [i 520]
        (insert-test-audit! ds {:username (str "user-" i)
                                :action "batch-test"}))
      ;; CSV export should include all 520 + header
      (let [writer (StringWriter.)]
        (audit-export/export-csv ds {:action "batch-test"} writer)
        (let [lines (str/split-lines (str writer))]
          (is (= 521 (count lines)))))  ;; header + 520 data rows

      ;; JSON export should include all 520
      (let [writer (StringWriter.)]
        (audit-export/export-json ds {:action "batch-test"} writer)
        (let [parsed (json/read-str (str writer) :key-fn keyword)]
          (is (= 520 (count parsed))))))))
