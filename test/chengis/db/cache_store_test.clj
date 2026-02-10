(ns chengis.db.cache-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.cache-store :as cache-store]))

(def test-db-path "/tmp/chengis-cache-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest save-cache-entry-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save-cache-entry! inserts a new entry"
      (cache-store/save-cache-entry! ds
        {:job-id "job-1"
         :cache-key "npm-abc123"
         :paths "node_modules"
         :size-bytes 1024})
      (let [entry (cache-store/get-cache-entry ds "job-1" "npm-abc123")]
        (is (some? entry))
        (is (= "job-1" (:job-id entry)))
        (is (= "npm-abc123" (:cache-key entry)))
        (is (= 1024 (:size-bytes entry)))))))

(deftest get-cache-entry-miss-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "get-cache-entry returns nil on miss"
      (is (nil? (cache-store/get-cache-entry ds "no-job" "no-key"))))))

(deftest save-duplicate-key-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save-cache-entry! skips duplicate (unique constraint)"
      (cache-store/save-cache-entry! ds
        {:job-id "job-2"
         :cache-key "go-xyz"
         :paths "vendor"
         :size-bytes 2048})
      ;; Second insert with same key should not throw
      (cache-store/save-cache-entry! ds
        {:job-id "job-2"
         :cache-key "go-xyz"
         :paths "vendor"
         :size-bytes 9999})
      ;; Original entry preserved
      (let [entry (cache-store/get-cache-entry ds "job-2" "go-xyz")]
        (is (= 2048 (:size-bytes entry)))))))

(deftest find-by-prefix-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "find-by-prefix matches key prefix"
      (cache-store/save-cache-entry! ds
        {:job-id "job-3" :cache-key "npm-aaa111" :paths "nm" :size-bytes 100})
      (cache-store/save-cache-entry! ds
        {:job-id "job-3" :cache-key "npm-bbb222" :paths "nm" :size-bytes 200})
      (cache-store/save-cache-entry! ds
        {:job-id "job-3" :cache-key "go-ccc333" :paths "vendor" :size-bytes 300})
      (let [npm-entries (cache-store/find-by-prefix ds "job-3" "npm-")]
        (is (= 2 (count npm-entries)))
        (is (every? #(clojure.string/starts-with? (:cache-key %) "npm-") npm-entries)))
      (let [go-entries (cache-store/find-by-prefix ds "job-3" "go-")]
        (is (= 1 (count go-entries)))))))

(deftest record-cache-hit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "record-cache-hit! increments hit count"
      (cache-store/save-cache-entry! ds
        {:job-id "job-4" :cache-key "hit-test" :paths "data" :size-bytes 50})
      (cache-store/record-cache-hit! ds "job-4" "hit-test")
      (cache-store/record-cache-hit! ds "job-4" "hit-test")
      (let [entry (cache-store/get-cache-entry ds "job-4" "hit-test")]
        (is (= 2 (:hit-count entry)))
        (is (some? (:last-hit-at entry)))))))

(deftest list-cache-entries-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-cache-entries returns all entries for a job"
      (cache-store/save-cache-entry! ds
        {:job-id "job-5" :cache-key "k1" :paths "a" :size-bytes 10})
      (cache-store/save-cache-entry! ds
        {:job-id "job-5" :cache-key "k2" :paths "b" :size-bytes 20})
      (cache-store/save-cache-entry! ds
        {:job-id "other-job" :cache-key "k3" :paths "c" :size-bytes 30})
      (let [entries (cache-store/list-cache-entries ds "job-5")]
        (is (= 2 (count entries)))
        (is (every? #(= "job-5" (:job-id %)) entries))))))
