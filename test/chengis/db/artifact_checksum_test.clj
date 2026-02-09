(ns chengis.db.artifact-checksum-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.artifact-store :as artifact-store]
            [chengis.engine.artifacts :as artifacts]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-artifact-checksum-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest save-artifact-with-hash-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save and retrieve artifact with SHA-256 hash"
      (let [hash "a948904f2f0f479b8f8564e9d7c1e2e29abcdef0123456789abcdef012345678"
            saved (artifact-store/save-artifact! ds
                    {:build-id "build-1"
                     :filename "app.jar"
                     :path "/tmp/app.jar"
                     :size-bytes 1024
                     :content-type "application/java-archive"
                     :sha256-hash hash})]
        (is (= hash (:sha256-hash saved)))
        (let [retrieved (artifact-store/get-artifact ds "build-1" "app.jar")]
          (is (= hash (:sha256-hash retrieved))))))

    (testing "save artifact without hash (backward compatibility)"
      (let [saved (artifact-store/save-artifact! ds
                    {:build-id "build-2"
                     :filename "report.txt"
                     :path "/tmp/report.txt"
                     :size-bytes 256
                     :content-type "text/plain"})]
        (is (nil? (:sha256-hash saved)))
        (let [retrieved (artifact-store/get-artifact ds "build-2" "report.txt")]
          (is (nil? (:sha256-hash retrieved))))))))

(deftest verify-artifact-hash-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "verify matching hash"
      ;; Create a real file and compute its hash
      (let [tmp (java.io.File/createTempFile "verify-test" ".txt")]
        (try
          (spit tmp "verification test content")
          (let [hash (artifacts/compute-sha256 tmp)]
            (artifact-store/save-artifact! ds
              {:build-id "build-v1"
               :filename "verify.txt"
               :path (.getAbsolutePath tmp)
               :size-bytes (.length tmp)
               :sha256-hash hash})
            (let [result (artifact-store/verify-artifact-hash ds "build-v1" "verify.txt")]
              (is (true? (:valid result)))
              (is (= hash (:expected result)))
              (is (= hash (:computed result)))))
          (finally
            (.delete tmp)))))

    (testing "verify mismatched hash (tampered file)"
      (let [tmp (java.io.File/createTempFile "tamper-test" ".txt")]
        (try
          (spit tmp "original content")
          (artifact-store/save-artifact! ds
            {:build-id "build-v2"
             :filename "tampered.txt"
             :path (.getAbsolutePath tmp)
             :size-bytes (.length tmp)
             :sha256-hash "0000000000000000000000000000000000000000000000000000000000000000"})
          ;; File content doesn't match the fake hash
          (let [result (artifact-store/verify-artifact-hash ds "build-v2" "tampered.txt")]
            (is (false? (:valid result)))
            (is (= "0000000000000000000000000000000000000000000000000000000000000000"
                   (:expected result))))
          (finally
            (.delete tmp)))))

    (testing "verify artifact with no hash stored"
      (artifact-store/save-artifact! ds
        {:build-id "build-v3"
         :filename "nohash.txt"
         :path "/tmp/nohash.txt"
         :size-bytes 100})
      (let [result (artifact-store/verify-artifact-hash ds "build-v3" "nohash.txt")]
        (is (nil? (:valid result)))
        (is (= "No hash stored (pre-checksum artifact)" (:reason result)))))

    (testing "verify nonexistent artifact"
      (let [result (artifact-store/verify-artifact-hash ds "build-v3" "nonexistent.txt")]
        (is (nil? (:valid result)))
        (is (= "Artifact not found" (:reason result)))))))
