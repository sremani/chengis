(ns chengis.db.secret-store-fallback-test
  "Tests for secret backend fallback behavior (CR-04).
   Verifies that Vault errors are handled based on :fallback-to-local config."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.secret-store :as secret-store]
            [chengis.plugin.registry :as registry]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-secret-fallback-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (registry/reset-registry!)
  (f)
  (registry/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Fallback Behavior Tests (CR-04)
;; ---------------------------------------------------------------------------

(deftest fallback-disabled-throws-on-missing-backend-test
  (let [ds (conn/create-datasource test-db-path)
        config {:secrets {:backend "vault"
                          :fallback-to-local false
                          :master-key "test-secret-key-32-chars-min!!"}}]

    (testing "missing backend throws when fallback disabled"
      (is (thrown? clojure.lang.ExceptionInfo
            (secret-store/get-secrets-for-build ds config "test-job"))))))

(deftest fallback-enabled-uses-local-on-missing-backend-test
  (let [ds (conn/create-datasource test-db-path)
        config {:secrets {:backend "vault"
                          :fallback-to-local true
                          :master-key "test-secret-key-32-chars-min!!"}}]

    (testing "missing backend falls back to local when enabled"
      ;; Should not throw — falls back to local store (returns empty map for no secrets)
      (let [result (secret-store/get-secrets-for-build ds config "test-job")]
        (is (map? result))
        (is (empty? result))))))

(deftest local-backend-works-directly-test
  (let [ds (conn/create-datasource test-db-path)
        config {:secrets {:backend "local"
                          :master-key "test-secret-key-32-chars-min!!"}}]

    ;; Set a secret via local store (scope keyword arg)
    (secret-store/set-secret! ds config "DB_PASS" "mypassword" :scope "test-job")

    (testing "local backend returns secrets directly"
      (let [result (secret-store/get-secrets-for-build ds config "test-job")]
        (is (= "mypassword" (get result "DB_PASS")))))))

(deftest fallback-default-is-false-test
  (let [ds (conn/create-datasource test-db-path)
        config {:secrets {:backend "nonexistent"
                          ;; No :fallback-to-local key at all — defaults to false
                          :master-key "test-secret-key-32-chars-min!!"}}]

    (testing "default fallback behavior is disabled (throws)"
      (is (thrown? clojure.lang.ExceptionInfo
            (secret-store/get-secrets-for-build ds config "test-job"))))))
