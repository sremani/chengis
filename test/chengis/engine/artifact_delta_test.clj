(ns chengis.engine.artifact-delta-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.engine.artifact-delta :as delta])
  (:import [java.io File]))

(def ^:dynamic *temp-dir* nil)

(defn- create-temp-dir [prefix]
  (let [d (File/createTempFile prefix "")]
    (.delete d)
    (.mkdirs d)
    d))

(defn- delete-recursive [^File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-recursive child)))
  (.delete f))

(defn- write-bytes [^File f ^bytes data]
  (io/copy data f))

(defn- create-file-with-size
  "Create a file filled with byte pattern of given size."
  [^File f size pattern-byte]
  (.mkdirs (.getParentFile f))
  (let [buf (byte-array size)]
    (java.util.Arrays/fill buf (byte pattern-byte))
    (write-bytes f buf)))

(defn temp-dir-fixture [f]
  (let [d (create-temp-dir "delta-test")]
    (binding [*temp-dir* (.getAbsolutePath d)]
      (try (f)
           (finally (delete-recursive d))))))

(use-fixtures :each temp-dir-fixture)

;; ---------------------------------------------------------------------------
;; should-use-delta? tests
;; ---------------------------------------------------------------------------

(deftest should-use-delta-false-for-small-files-test
  (testing "should-use-delta? returns false for files smaller than 1MB"
    (let [base (io/file *temp-dir* "small-base.bin")
          new-f (io/file *temp-dir* "small-new.bin")]
      (create-file-with-size base 1000 42)
      (create-file-with-size new-f 1000 43)
      (is (false? (delta/should-use-delta? base new-f))))))

(deftest should-use-delta-true-for-large-files-test
  (testing "should-use-delta? returns true for large files"
    (let [base (io/file *temp-dir* "large-base.bin")
          new-f (io/file *temp-dir* "large-new.bin")
          size (* 2 1024 1024)] ;; 2MB
      (create-file-with-size base size 42)
      (create-file-with-size new-f size 43)
      (is (true? (delta/should-use-delta? base new-f))))))

(deftest should-use-delta-false-when-base-missing-test
  (testing "should-use-delta? returns false when base file doesn't exist"
    (let [new-f (io/file *temp-dir* "only-new.bin")]
      (create-file-with-size new-f (* 2 1024 1024) 42)
      (is (false? (delta/should-use-delta? (io/file *temp-dir* "nofile") new-f))))))

;; ---------------------------------------------------------------------------
;; compute-delta tests
;; ---------------------------------------------------------------------------

(deftest compute-delta-identical-files-test
  (testing "identical files produce zero changed blocks"
    (let [base (io/file *temp-dir* "id-base.bin")
          new-f (io/file *temp-dir* "id-new.bin")
          size (* 2 1024 1024)]
      (create-file-with-size base size 42)
      (create-file-with-size new-f size 42)
      (let [d (delta/compute-delta base new-f)]
        (is (zero? (:changed-blocks d)))
        (is (> (:savings-pct d) 99.0))))))

(deftest compute-delta-different-files-test
  (testing "completely different files show all blocks changed"
    (let [base (io/file *temp-dir* "diff-base.bin")
          new-f (io/file *temp-dir* "diff-new.bin")
          size (* 2 1024 1024)]
      (create-file-with-size base size 42)
      (create-file-with-size new-f size 99)
      (let [d (delta/compute-delta base new-f)]
        (is (pos? (:changed-blocks d)))
        (is (= (:changed-blocks d) (:new-blocks d)))
        (is (< (:savings-pct d) 1.0))))))

;; ---------------------------------------------------------------------------
;; apply-delta roundtrip
;; ---------------------------------------------------------------------------

(deftest apply-delta-reconstruction-test
  (testing "apply-delta reconstructs original file from base + delta"
    (let [base (io/file *temp-dir* "rt-base.bin")
          new-f (io/file *temp-dir* "rt-new.bin")
          output (io/file *temp-dir* "rt-output.bin")
          ;; Create base: all zeros
          size (* 2 1024 1024)
          base-data (byte-array size)]
      (java.util.Arrays/fill base-data (byte 0))
      ;; Create new: mostly zeros but some blocks changed
      (let [new-data (byte-array size)]
        (java.util.Arrays/fill new-data (byte 0))
        ;; Change a few blocks (write different bytes at offsets)
        (java.util.Arrays/fill new-data 0 4096 (byte 77))     ;; block 0
        (java.util.Arrays/fill new-data 8192 12288 (byte 88)) ;; block 2
        (write-bytes base base-data)
        (write-bytes new-f new-data)
        (let [d (delta/compute-delta base new-f)]
          ;; Only 2 blocks should be changed
          (is (= 2 (:changed-blocks d)))
          ;; Reconstruct
          (delta/apply-delta base (:delta-blocks d) output)
          ;; Verify output matches new-file (read and compare)
          (let [new-bytes (java.nio.file.Files/readAllBytes (.toPath new-f))
                out-bytes (java.nio.file.Files/readAllBytes (.toPath output))]
            (is (java.util.Arrays/equals new-bytes out-bytes))))))))

;; ---------------------------------------------------------------------------
;; save-with-delta! tests
;; ---------------------------------------------------------------------------

(deftest save-with-delta-no-base-test
  (testing "save-with-delta! returns non-delta when no base exists"
    (let [artifact {:filename "app.jar" :path (.getAbsolutePath (io/file *temp-dir* "app.jar"))}
          _ (create-file-with-size (io/file *temp-dir* "app.jar") 1000 42)
          result (delta/save-with-delta! *temp-dir* "job" 2 artifact 1)]
      (is (false? (:delta? result))))))
