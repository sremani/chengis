(ns chengis.engine.stage-cache-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.engine.stage-cache :as stage-cache]))

(def test-db-path "/tmp/chengis-stage-cache-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Fingerprint tests
;; ---------------------------------------------------------------------------

(deftest stage-fingerprint-consistent-test
  (testing "same inputs produce same fingerprint"
    (let [stage-def {:stage-name "Build"
                     :steps [{:command "mvn compile"} {:command "mvn test"}]}
          env {"JAVA_HOME" "/usr/lib/jvm" "PATH" "/usr/bin"}
          fp1 (stage-cache/stage-fingerprint "abc123" stage-def env)
          fp2 (stage-cache/stage-fingerprint "abc123" stage-def env)]
      (is (= fp1 fp2))
      (is (= 64 (count fp1))))))  ;; SHA-256 = 64 hex chars

(deftest stage-fingerprint-differs-on-command-change-test
  (testing "different commands produce different fingerprint"
    (let [stage1 {:stage-name "Build" :steps [{:command "mvn compile"}]}
          stage2 {:stage-name "Build" :steps [{:command "mvn package"}]}
          env {}]
      (is (not= (stage-cache/stage-fingerprint "abc" stage1 env)
                (stage-cache/stage-fingerprint "abc" stage2 env))))))

(deftest stage-fingerprint-differs-on-commit-change-test
  (testing "different git commits produce different fingerprint"
    (let [stage {:stage-name "Build" :steps [{:command "make"}]}
          env {}]
      (is (not= (stage-cache/stage-fingerprint "commit-1" stage env)
                (stage-cache/stage-fingerprint "commit-2" stage env))))))

(deftest stage-fingerprint-differs-on-env-change-test
  (testing "different env produces different fingerprint"
    (let [stage {:stage-name "Build" :steps [{:command "make"}]}]
      (is (not= (stage-cache/stage-fingerprint "abc" stage {"A" "1"})
                (stage-cache/stage-fingerprint "abc" stage {"A" "2"}))))))

(deftest stage-fingerprint-excludes-build-specific-vars-test
  (testing "build-specific env vars are excluded from fingerprint"
    (let [stage {:stage-name "Build" :steps [{:command "make"}]}
          ;; Same stable env, different build-specific vars
          env1 {"JAVA_HOME" "/usr/lib/jvm" "BUILD_ID" "build-1"
                "BUILD_NUMBER" "1" "WORKSPACE" "/ws/1" "JOB_NAME" "j1"}
          env2 {"JAVA_HOME" "/usr/lib/jvm" "BUILD_ID" "build-2"
                "BUILD_NUMBER" "2" "WORKSPACE" "/ws/2" "JOB_NAME" "j2"}]
      (is (= (stage-cache/stage-fingerprint "abc" stage env1)
             (stage-cache/stage-fingerprint "abc" stage env2))
          "build-specific vars should not affect fingerprint"))))

;; ---------------------------------------------------------------------------
;; Database operations
;; ---------------------------------------------------------------------------

(deftest check-stage-cache-miss-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "check-stage-cache returns nil on miss"
      (is (nil? (stage-cache/check-stage-cache ds "job-1" "nonexistent-fp"))))))

(deftest save-and-check-roundtrip-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save + check roundtrip returns cached result"
      (let [result {:stage-name "Build"
                    :stage-status :success
                    :step-results [{:step-name "Compile" :step-status :success}]}
            fp "abc123def456"]
        (stage-cache/save-stage-result! ds
          {:job-id "job-1"
           :fingerprint fp
           :stage-name "Build"
           :stage-result result
           :git-commit "sha123"})
        (let [cached (stage-cache/check-stage-cache ds "job-1" fp)]
          (is (some? cached))
          (is (= "Build" (:stage-name cached))))))))

(deftest save-duplicate-fingerprint-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "duplicate fingerprint save is a no-op (no error)"
      (let [fp "dup-fp-test"]
        (stage-cache/save-stage-result! ds
          {:job-id "job-2" :fingerprint fp :stage-name "Test"
           :stage-result {:stage-name "Test" :stage-status :success}
           :git-commit "sha1"})
        ;; Second save should not throw
        (stage-cache/save-stage-result! ds
          {:job-id "job-2" :fingerprint fp :stage-name "Test"
           :stage-result {:stage-name "Test" :stage-status :success :extra true}
           :git-commit "sha2"})
        ;; Original result preserved
        (let [cached (stage-cache/check-stage-cache ds "job-2" fp)]
          (is (some? cached)))))))

;; ---------------------------------------------------------------------------
;; Integration (should-skip-stage?)
;; ---------------------------------------------------------------------------

(deftest should-skip-stage-no-db-test
  (testing "should-skip-stage? returns skip?=false when no DB"
    (let [system {:config {:feature-flags {:build-result-cache true}}}
          build-ctx {:env {"GIT_COMMIT" "abc123"} :job-id "j1"}
          stage {:stage-name "Build" :steps [{:command "make"}]}]
      (is (false? (:skip? (stage-cache/should-skip-stage? system build-ctx stage)))))))

(deftest should-skip-stage-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "should-skip-stage? returns skip?=false when flag disabled"
      (let [system {:config {:feature-flags {:build-result-cache false}}
                    :db ds}
            build-ctx {:env {"GIT_COMMIT" "abc123"} :job-id "j1"}
            stage {:stage-name "Build" :steps [{:command "make"}]}]
        (is (false? (:skip? (stage-cache/should-skip-stage? system build-ctx stage))))))))

(deftest should-skip-stage-no-commit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "should-skip-stage? returns skip?=false when no git commit"
      (let [system {:config {:feature-flags {:build-result-cache true}}
                    :db ds}
            build-ctx {:env {} :job-id "j1"}
            stage {:stage-name "Build" :steps [{:command "make"}]}]
        (is (false? (:skip? (stage-cache/should-skip-stage? system build-ctx stage))))))))
