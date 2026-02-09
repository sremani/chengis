(ns chengis.plugin.loader-trust-test
  "Tests for plugin loader trust enforcement: allowed, blocked,
   and backward-compatible (no DB) loading."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.plugin-policy-store :as plugin-policy-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-loader-trust-test.db")
(def test-plugin-dir "/tmp/chengis-loader-trust-plugins")

(defn setup [f]
  ;; Setup DB
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Setup plugin directory with test plugins
  (let [dir (io/file test-plugin-dir)]
    (.mkdirs dir)
    ;; Create test plugin files
    (spit (io/file dir "allowed-test.clj")
          "(ns allowed-test) (def loaded? true)")
    (spit (io/file dir "blocked-test.clj")
          "(ns blocked-test) (def loaded? true)"))
  (f)
  ;; Cleanup
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (doseq [f (.listFiles (io/file test-plugin-dir))]
    (.delete f))
  (.delete (io/file test-plugin-dir)))

(use-fixtures :each setup)

(deftest allowed-plugin-loads-test
  (testing "plugin with allowed=true policy loads successfully"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Allow the test plugin
      (plugin-policy-store/set-plugin-policy! ds
        {:org-id nil :plugin-name "allowed-test"
         :trust-level "trusted" :allowed true :created-by "test"})
      ;; Load external plugins with trust enforcement
      ;; We need to call the private function, but we can test via the public load-plugins! API
      ;; Instead, we directly invoke load-external-plugins! via its private var
      (let [load-fn (var-get #'chengis.plugin.loader/load-external-plugins!)]
        (load-fn test-plugin-dir :ds ds :org-id nil))
      ;; The allowed plugin should have been loaded
      (is (some? (find-ns 'allowed-test))
          "allowed-test namespace should be loaded"))))

(deftest blocked-plugin-skipped-test
  (testing "plugin without allowed policy is skipped"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Only allow 'allowed-test', NOT 'blocked-test'
      (plugin-policy-store/set-plugin-policy! ds
        {:org-id nil :plugin-name "allowed-test"
         :trust-level "trusted" :allowed true :created-by "test"})
      ;; Remove blocked-test ns if it was loaded by a previous test
      (when (find-ns 'blocked-test)
        (remove-ns 'blocked-test))
      ;; Load external plugins
      (let [load-fn (var-get #'chengis.plugin.loader/load-external-plugins!)]
        (load-fn test-plugin-dir :ds ds :org-id nil))
      ;; blocked-test should NOT have been loaded
      (is (nil? (find-ns 'blocked-test))
          "blocked-test namespace should NOT be loaded"))))

(deftest no-db-loads-all-plugins-test
  (testing "when no DB is provided, all external plugins load (backward compat)"
    ;; Remove both namespaces if they exist
    (when (find-ns 'allowed-test) (remove-ns 'allowed-test))
    (when (find-ns 'blocked-test) (remove-ns 'blocked-test))
    ;; Load without ds — should load everything
    (let [load-fn (var-get #'chengis.plugin.loader/load-external-plugins!)]
      (load-fn test-plugin-dir))
    (is (some? (find-ns 'allowed-test))
        "allowed-test should be loaded without DB enforcement")
    (is (some? (find-ns 'blocked-test))
        "blocked-test should also be loaded without DB enforcement")))
