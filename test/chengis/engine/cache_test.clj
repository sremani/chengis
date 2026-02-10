(ns chengis.engine.cache-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.engine.cache :as cache])
  (:import [java.io File]))

;; Test fixture: create temp dirs for workspace and cache
(def ^:dynamic *workspace* nil)
(def ^:dynamic *cache-root* nil)

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

(defn temp-dirs-fixture [f]
  (let [ws (create-temp-dir "cache-test-ws")
        cr (create-temp-dir "cache-test-cache")]
    (binding [*workspace* (.getAbsolutePath ws)
              *cache-root* (.getAbsolutePath cr)]
      (try (f)
           (finally
             (delete-recursive ws)
             (delete-recursive cr))))))

(use-fixtures :each temp-dirs-fixture)

;; ---------------------------------------------------------------------------
;; Cache key resolution
;; ---------------------------------------------------------------------------

(deftest resolve-cache-key-literal-test
  (testing "literal key passes through unchanged"
    (is (= "my-cache-key" (cache/resolve-cache-key *workspace* "my-cache-key")))))

(deftest resolve-cache-key-hashfiles-test
  (testing "hashFiles expression computes SHA-256 of file"
    ;; Create a lock file in the workspace
    (spit (io/file *workspace* "package-lock.json") "{\"lockfileVersion\": 2}")
    (let [key (cache/resolve-cache-key *workspace*
                "npm-{{ hashFiles('package-lock.json') }}")]
      ;; Should start with npm- and have a hex hash suffix
      (is (clojure.string/starts-with? key "npm-"))
      (is (>= (count key) 20))))

  (testing "hashFiles with missing file produces 'missing' placeholder"
    (let [key (cache/resolve-cache-key *workspace*
                "go-{{ hashFiles('go.sum') }}")]
      (is (= "go-missing" key)))))

(deftest resolve-cache-key-deterministic-test
  (testing "same file content produces same hash"
    (spit (io/file *workspace* "lock.json") "content-A")
    (let [k1 (cache/resolve-cache-key *workspace* "x-{{ hashFiles('lock.json') }}")
          k2 (cache/resolve-cache-key *workspace* "x-{{ hashFiles('lock.json') }}")]
      (is (= k1 k2)))))

;; ---------------------------------------------------------------------------
;; Cache path
;; ---------------------------------------------------------------------------

(deftest cache-path-test
  (testing "cache-path constructs correct path"
    (is (= "/data/cache/my-job/abc123"
           (cache/cache-path "/data/cache" "my-job" "abc123")))))

(deftest cache-root-test
  (testing "cache-root reads from config"
    (is (= "/custom/cache" (cache/cache-root {:cache {:root "/custom/cache"}}))))
  (testing "cache-root defaults to 'cache'"
    (is (= "cache" (cache/cache-root {})))))

;; ---------------------------------------------------------------------------
;; Save and restore roundtrip (filesystem-only, no DB)
;; ---------------------------------------------------------------------------

(deftest save-restore-roundtrip-test
  (testing "save-cache! creates cache dir and restore-cache! restores files"
    (let [config {:cache {:root *cache-root*}
                  :feature-flags {:artifact-cache true}}
          job-id "test-job"
          ;; Create some files in workspace
          _ (spit (io/file *workspace* "package-lock.json") "lockfile-content")
          _ (.mkdirs (io/file *workspace* "node_modules"))
          _ (spit (io/file *workspace* "node_modules" "dep.js") "module code")
          cache-decls [{:key "npm-static-key"
                        :paths ["node_modules"]}]]
      ;; Save cache
      (cache/save-cache! *workspace* config nil job-id cache-decls)
      ;; Verify cache dir was created
      (let [cached (io/file *cache-root* job-id "npm-static-key" "node_modules" "dep.js")]
        (is (.exists cached)))
      ;; Delete from workspace
      (delete-recursive (io/file *workspace* "node_modules"))
      (is (not (.exists (io/file *workspace* "node_modules" "dep.js"))))
      ;; Restore cache
      (let [restored (cache/restore-cache! *workspace* config nil job-id cache-decls)]
        (is (seq restored))
        ;; File should be restored
        (is (.exists (io/file *workspace* "node_modules" "dep.js")))))))

(deftest save-cache-immutable-test
  (testing "save-cache! skips if cache already exists"
    (let [config {:cache {:root *cache-root*}
                  :feature-flags {:artifact-cache true}}
          job-id "test-job"
          cache-decls [{:key "immutable-key" :paths ["data"]}]
          ;; Create workspace data
          _ (.mkdirs (io/file *workspace* "data"))
          _ (spit (io/file *workspace* "data" "v1.txt") "version-1")]
      ;; First save
      (cache/save-cache! *workspace* config nil job-id cache-decls)
      ;; Modify workspace
      (spit (io/file *workspace* "data" "v1.txt") "version-2")
      ;; Second save should skip (immutable)
      (cache/save-cache! *workspace* config nil job-id cache-decls)
      ;; Cache should still have v1
      (is (= "version-1"
             (slurp (io/file *cache-root* job-id "immutable-key" "data" "v1.txt")))))))

(deftest restore-cache-miss-test
  (testing "restore-cache! returns empty on cache miss without error"
    (let [config {:cache {:root *cache-root*}
                  :feature-flags {:artifact-cache true}}
          result (cache/restore-cache! *workspace* config nil "no-job"
                   [{:key "nonexistent" :paths ["stuff"]}])]
      (is (empty? result)))))

(deftest feature-flag-disabled-test
  (testing "cache operations are no-ops when feature flag is disabled"
    (let [config {:cache {:root *cache-root*}
                  :feature-flags {:artifact-cache false}}
          _ (.mkdirs (io/file *workspace* "data"))
          _ (spit (io/file *workspace* "data" "file.txt") "hello")]
      ;; Save should do nothing
      (cache/save-cache! *workspace* config nil "test-job"
        [{:key "test" :paths ["data"]}])
      ;; Cache dir should NOT exist
      (is (not (.exists (io/file *cache-root* "test-job" "test"))))
      ;; Restore should return nil
      (is (nil? (cache/restore-cache! *workspace* config nil "test-job"
                  [{:key "test" :paths ["data"]}]))))))

(deftest cache-stats-no-db-test
  (testing "cache-stats returns nil when no DB"
    (is (nil? (cache/cache-stats {} nil "test-job")))))
