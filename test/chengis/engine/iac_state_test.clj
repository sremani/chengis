(ns chengis.engine.iac-state-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.engine.iac-state :as iac-state]))

(def test-db-path "/tmp/chengis-iac-state-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

;; Private fn access for testing compression/hashing internals
(def ^:private compress-fn  #'iac-state/compress)
(def ^:private decompress-fn #'iac-state/decompress)
(def ^:private sha256-fn     #'iac-state/sha256)

;; ---------------------------------------------------------------------------
;; Compression round-trip
;; ---------------------------------------------------------------------------

(deftest compress-decompress-round-trip-test
  (testing "data survives gzip compression and decompression"
    (let [original "{\"resources\": [{\"type\": \"aws_instance\", \"name\": \"web\"}]}"
          compressed (compress-fn original)
          decompressed (decompress-fn compressed)]
      (is (string? compressed))
      (is (not= original compressed))
      (is (= original decompressed))))

  (testing "empty string round-trips"
    (let [original ""
          compressed (compress-fn original)
          decompressed (decompress-fn compressed)]
      (is (= original decompressed))))

  (testing "large content round-trips"
    (let [original (apply str (repeat 10000 "x"))
          compressed (compress-fn original)
          decompressed (decompress-fn compressed)]
      (is (= original decompressed))
      ;; Compressed should be smaller for repetitive data
      (is (< (count compressed) (count original))))))

;; ---------------------------------------------------------------------------
;; SHA-256 hashing
;; ---------------------------------------------------------------------------

(deftest sha256-consistency-test
  (testing "same content produces same hash"
    (let [data "terraform state content"
          hash1 (sha256-fn data)
          hash2 (sha256-fn data)]
      (is (= 64 (count hash1)))
      (is (= hash1 hash2))))

  (testing "different content produces different hash"
    (let [hash1 (sha256-fn "content-a")
          hash2 (sha256-fn "content-b")]
      (is (not= hash1 hash2)))))

;; ---------------------------------------------------------------------------
;; State CRUD
;; ---------------------------------------------------------------------------

(deftest save-state-test
  (let [ds (test-ds)]
    (testing "save-state! creates state with correct metadata"
      (let [state (iac-state/save-state! ds
                    {:org-id "org-1"
                     :project-id "proj-1"
                     :state-content "{\"resources\": []}"
                     :tool-type "terraform"
                     :created-by "user-1"})]
        (is (some? (:id state)))
        (is (= 1 (:version state)))
        (is (some? (:state-hash state)))
        (is (pos? (:state-size state)))
        (is (= "user-1" (:created-by state)))))))

(deftest auto-increment-version-test
  (let [ds (test-ds)]
    (testing "second save gets version 2"
      (let [s1 (iac-state/save-state! ds
                 {:org-id "org-1" :project-id "proj-2"
                  :state-content "{\"v\": 1}" :tool-type "terraform"})
            s2 (iac-state/save-state! ds
                 {:org-id "org-1" :project-id "proj-2"
                  :state-content "{\"v\": 2}" :tool-type "terraform"})]
        (is (= 1 (:version s1)))
        (is (= 2 (:version s2)))))))

(deftest get-latest-state-test
  (let [ds (test-ds)]
    (testing "get-latest-state returns highest version with decompressed content"
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-3"
         :state-content "{\"first\": true}" :tool-type "terraform"})
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-3"
         :state-content "{\"second\": true}" :tool-type "terraform"})
      (let [latest (iac-state/get-latest-state ds "proj-3")]
        (is (some? latest))
        (is (= 2 (:version latest)))
        (is (some? (:state-content latest)))
        (let [parsed (json/read-str (:state-content latest) :key-fn keyword)]
          (is (true? (:second parsed))))))

    (testing "get-latest-state returns nil for non-existent project"
      (is (nil? (iac-state/get-latest-state ds "non-existent"))))))

(deftest get-state-version-test
  (let [ds (test-ds)]
    (testing "get-state-version returns specific version"
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-4"
         :state-content "{\"v\": 1}" :tool-type "terraform"})
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-4"
         :state-content "{\"v\": 2}" :tool-type "terraform"})
      (let [v1 (iac-state/get-state-version ds "proj-4" 1)]
        (is (some? v1))
        (is (= 1 (:version v1)))
        (let [parsed (json/read-str (:state-content v1) :key-fn keyword)]
          (is (= 1 (:v parsed))))))

    (testing "get-state-version returns nil for non-existent version"
      (is (nil? (iac-state/get-state-version ds "proj-4" 999))))))

(deftest list-state-versions-test
  (let [ds (test-ds)]
    (testing "list-state-versions returns metadata without state-data"
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-5"
         :state-content "{}" :tool-type "terraform"})
      (iac-state/save-state! ds
        {:org-id "org-1" :project-id "proj-5"
         :state-content "{}" :tool-type "terraform"})
      (let [versions (iac-state/list-state-versions ds "proj-5")]
        (is (= 2 (count versions)))
        ;; Should be ordered desc
        (is (= 2 (:version (first versions))))
        (is (= 1 (:version (second versions))))
        ;; Should contain some metadata fields
        (is (some? (:version (first versions))))
        (is (some? (:project-id (first versions))))))))

(deftest workspace-scoping-test
  (let [ds (test-ds)]
    (testing "different workspaces have independent versions"
      (let [s1 (iac-state/save-state! ds
                 {:org-id "org-1" :project-id "proj-6"
                  :state-content "{\"ws\": \"default\"}" :tool-type "terraform"
                  :workspace-name "default"})
            s2 (iac-state/save-state! ds
                 {:org-id "org-1" :project-id "proj-6"
                  :state-content "{\"ws\": \"staging\"}" :tool-type "terraform"
                  :workspace-name "staging"})]
        (is (= 1 (:version s1)))
        (is (= 1 (:version s2)))
        (let [default-latest (iac-state/get-latest-state ds "proj-6" :workspace-name "default")
              staging-latest (iac-state/get-latest-state ds "proj-6" :workspace-name "staging")]
          (is (= 1 (:version default-latest)))
          (is (= 1 (:version staging-latest))))))))

;; ---------------------------------------------------------------------------
;; State Diffing
;; ---------------------------------------------------------------------------

(deftest diff-states-test
  (testing "detect added, removed, and changed resources"
    (let [old-state (json/write-str {:resources [{:type "aws_instance" :name "web"}
                                                  {:type "aws_s3_bucket" :name "logs"}]})
          new-state (json/write-str {:resources [{:type "aws_instance" :name "web-updated"}
                                                  {:type "aws_rds_instance" :name "db"}]})
          diff (iac-state/diff-states old-state new-state)]
      (is (map? diff))
      (is (vector? (:added diff)))
      (is (vector? (:removed diff)))
      (is (vector? (:changed diff)))))

  (testing "identical states produce no diff"
    (let [state (json/write-str {:resources [{:type "aws_instance" :name "web"}]})
          diff (iac-state/diff-states state state)]
      (is (empty? (:added diff)))
      (is (empty? (:removed diff)))
      (is (empty? (:changed diff))))))

;; ---------------------------------------------------------------------------
;; Locking
;; ---------------------------------------------------------------------------

(deftest acquire-lock-test
  (let [ds (test-ds)]
    (testing "acquire-lock! returns true"
      (is (true? (iac-state/acquire-lock! ds "proj-lock-1" "user-1"))))))

(deftest acquire-lock-already-locked-test
  (let [ds (test-ds)]
    (testing "second acquire by different user returns false"
      (iac-state/acquire-lock! ds "proj-lock-2" "user-1")
      (is (false? (iac-state/acquire-lock! ds "proj-lock-2" "user-2"))))))

(deftest release-lock-test
  (let [ds (test-ds)]
    (testing "release then re-acquire succeeds"
      (iac-state/acquire-lock! ds "proj-lock-3" "user-1")
      (is (true? (iac-state/release-lock! ds "proj-lock-3")))
      (is (true? (iac-state/acquire-lock! ds "proj-lock-3" "user-2"))))))

(deftest get-lock-test
  (let [ds (test-ds)]
    (testing "get-lock returns lock info when locked"
      (iac-state/acquire-lock! ds "proj-lock-4" "user-1")
      (let [lock (iac-state/get-lock ds "proj-lock-4")]
        (is (some? lock))
        (is (= "proj-lock-4" (:project-id lock)))
        (is (= "user-1" (:locked-by lock)))))

    (testing "get-lock returns nil when unlocked"
      (is (nil? (iac-state/get-lock ds "non-existent-proj"))))))

(deftest force-unlock-test
  (let [ds (test-ds)]
    (testing "force-unlock! works regardless of owner"
      (iac-state/acquire-lock! ds "proj-lock-5" "user-1")
      (is (true? (iac-state/force-unlock! ds "proj-lock-5")))
      (is (nil? (iac-state/get-lock ds "proj-lock-5")))
      ;; Can re-acquire after force unlock
      (is (true? (iac-state/acquire-lock! ds "proj-lock-5" "user-2"))))))

(deftest size-limit-test
  (let [ds (test-ds)]
    (testing "exceeding max size throws"
      (let [large-content (apply str (repeat 200 "x"))]
        (is (thrown? clojure.lang.ExceptionInfo
              (iac-state/save-state! ds
                {:org-id "org-1" :project-id "proj-limit"
                 :state-content large-content :tool-type "terraform"
                 :max-size 100})))))))
