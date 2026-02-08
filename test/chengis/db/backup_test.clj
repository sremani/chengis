(ns chengis.db.backup-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.backup :as backup]
            [next.jdbc :as jdbc]
            [clojure.java.io :as io])
  (:import [java.io File]))

(def test-db-path "/tmp/chengis-backup-test.db")
(def test-backup-dir "/tmp/chengis-backup-test-dir")

(defn setup [f]
  ;; Clean up any previous test artifacts
  (let [db-file (io/file test-db-path)
        backup-dir (io/file test-backup-dir)]
    (when (.exists db-file) (.delete db-file))
    (when (.exists backup-dir)
      (doseq [^File child (.listFiles backup-dir)]
        (.delete child))
      (.delete backup-dir))
    (.mkdirs backup-dir))
  ;; Create and migrate the test DB
  (migrate/migrate! test-db-path)
  (f)
  ;; Cleanup
  (let [db-file (io/file test-db-path)
        backup-dir (io/file test-backup-dir)]
    (when (.exists db-file) (.delete db-file))
    (when (.exists backup-dir)
      (doseq [^File child (.listFiles backup-dir)]
        (.delete child))
      (.delete backup-dir))))

(use-fixtures :each setup)

(deftest backup-creates-valid-file-test
  (let [ds (conn/create-datasource test-db-path)
        output-path (str test-backup-dir "/test-backup.db")]
    (testing "backup creates a file that exists and has content"
      (let [result (backup/backup! ds output-path)]
        (is (string? (:path result)))
        (is (pos? (:size-bytes result)))
        (is (string? (:timestamp result)))
        (is (.exists (io/file output-path)))))

    (testing "backup file is a valid SQLite database"
      (let [backup-ds (conn/create-datasource output-path)
            test-result (conn/test-connection backup-ds)]
        (is (= 1 (:result test-result)))))))

(deftest generate-backup-path-test
  (testing "generates path with correct prefix and extension"
    (let [path (backup/generate-backup-path "/tmp")]
      (is (.startsWith (.getName (io/file path)) "chengis-backup-"))
      (is (.endsWith path ".db"))
      (is (.startsWith path "/tmp/")))))

(deftest restore-test
  (let [ds (conn/create-datasource test-db-path)
        backup-path (str test-backup-dir "/restore-test.db")
        restore-target (str test-backup-dir "/restored.db")]
    ;; Create a backup first
    (backup/backup! ds backup-path)

    (testing "restore copies backup to target"
      (let [result (backup/restore! backup-path restore-target)]
        (is (= (.getAbsolutePath (io/file backup-path)) (:restored-from result)))
        (is (.exists (io/file restore-target)))))

    (testing "restore refuses to overwrite without force"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Target database already exists"
                            (backup/restore! backup-path restore-target))))

    (testing "restore overwrites with force flag"
      (let [result (backup/restore! backup-path restore-target :force? true)]
        (is (some? (:target result)))))

    (testing "restore fails on missing backup file"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Backup file not found"
                            (backup/restore! "/nonexistent/file.db" restore-target))))))

(deftest list-backups-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "empty directory returns empty list"
      (let [result (backup/list-backups test-backup-dir)]
        (is (empty? result))))

    (testing "lists backup files sorted newest first"
      ;; Create a couple of backups
      (backup/backup! ds (str test-backup-dir "/chengis-backup-20260101-120000.db"))
      (Thread/sleep 10)
      (backup/backup! ds (str test-backup-dir "/chengis-backup-20260102-120000.db"))

      (let [backups (backup/list-backups test-backup-dir)]
        (is (= 2 (count backups)))
        ;; Each has expected keys
        (is (every? :path backups))
        (is (every? :filename backups))
        (is (every? :size-bytes backups))
        (is (every? :modified backups))
        ;; Sorted newest first
        (is (.contains (:filename (first backups)) "20260102"))))

    (testing "non-backup files are filtered out"
      (spit (str test-backup-dir "/not-a-backup.txt") "hello")
      (let [backups (backup/list-backups test-backup-dir)]
        (is (= 2 (count backups)))  ;; still just the 2 backups
        ))

    (testing "non-existent directory returns empty list"
      (is (empty? (backup/list-backups "/nonexistent/dir"))))))
