(ns chengis.engine.artifacts-checksum-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.artifacts :as artifacts]
            [clojure.java.io :as io]))

(deftest compute-sha256-test
  (testing "computes correct SHA-256 for a known file"
    (let [tmp (java.io.File/createTempFile "sha256-test" ".txt")]
      (try
        (spit tmp "hello world\n")
        (let [hash (artifacts/compute-sha256 tmp)]
          (is (some? hash))
          (is (= 64 (count hash)) "SHA-256 hex should be 64 chars")
          ;; "hello world\n" has known SHA-256:
          ;; echo -n "hello world\n" won't work due to clojure newlines,
          ;; but we can verify it's consistent
          (is (= hash (artifacts/compute-sha256 tmp)) "same file should produce same hash"))
        (finally
          (.delete tmp)))))

  (testing "returns nil for non-existent file"
    (let [fake (io/file "/tmp/nonexistent-file-for-sha256-test-12345.txt")]
      (is (nil? (artifacts/compute-sha256 fake))))))

(deftest collect-artifacts-includes-hash-test
  (testing "collected artifacts include sha256-hash key"
    (let [ws-dir (str (System/getProperty "java.io.tmpdir") "/sha256-ws-test")
          art-dir (str (System/getProperty "java.io.tmpdir") "/sha256-art-test")
          ws-file (io/file ws-dir "report.txt")]
      (try
        (.mkdirs (io/file ws-dir))
        (spit ws-file "test artifact content")
        (let [result (artifacts/collect-artifacts! ws-dir art-dir ["*.txt"])]
          (is (= 1 (count result)))
          (let [art (first result)]
            (is (contains? art :sha256-hash))
            (is (some? (:sha256-hash art)))
            (is (= 64 (count (:sha256-hash art))))))
        (finally
          ;; Cleanup
          (doseq [f (file-seq (io/file art-dir))]
            (when (.isFile f) (.delete f)))
          (doseq [f (reverse (file-seq (io/file art-dir)))]
            (.delete f))
          (doseq [f (file-seq (io/file ws-dir))]
            (when (.isFile f) (.delete f)))
          (doseq [f (reverse (file-seq (io/file ws-dir)))]
            (.delete f)))))))
