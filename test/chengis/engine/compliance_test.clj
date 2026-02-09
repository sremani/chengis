(ns chengis.engine.compliance-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.compliance-store :as compliance-store]
            [chengis.engine.compliance :as compliance]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-compliance-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest verify-hash-chain-valid-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "hash chain is valid for consecutive entries"
      (dotimes [i 5]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action "login" :resource-type "session"
           :resource-id (str "s" i)}))
      (let [result (compliance/verify-hash-chain ds {})]
        (is (true? (:valid result)))
        (is (= 5 (:entries-checked result)))
        (is (nil? (:first-invalid-id result)))))))

(deftest verify-hash-chain-empty-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "hash chain verification on empty audit log"
      (let [result (compliance/verify-hash-chain ds {})]
        (is (true? (:valid result)))
        (is (= 0 (:entries-checked result)))))))

(deftest generate-report-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "generate a full audit report"
      ;; Insert some audit events
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "login"
         :resource-type "session" :resource-id "s1"})
      (audit-store/insert-audit! ds
        {:user-id "u1" :username "alice" :action "trigger-build"
         :resource-type "build" :resource-id "b1"})
      (audit-store/insert-audit! ds
        {:user-id "u2" :username "bob" :action "login"
         :resource-type "session" :resource-id "s2"})
      ;; Create template and generate report
      (let [tmpl (compliance-store/create-report-template! ds
                   {:report-type "full-audit"
                    :title "Full Audit Report"
                    :description "Test report"})
            run (compliance-store/create-report-run! ds
                  {:report-id (:id tmpl) :generated-by "admin"})
            result (compliance/generate-report! ds tmpl (:id run) {})]
        (is (= "completed" (:status result)))
        (is (some? (:summary result)))
        ;; Verify run was updated in DB
        (let [db-run (compliance-store/get-report-run ds (:id run))]
          (is (= "completed" (:status db-run)))
          (is (some? (:report-hash db-run)))
          (is (= 64 (count (:report-hash db-run)))))))))

(deftest builtin-templates-test
  (testing "builtin templates returns expected templates"
    (let [templates (compliance/builtin-templates)]
      (is (= 4 (count templates)))
      (is (every? :report-type templates))
      (is (every? :title templates))
      (is (some #(= "soc2-access-control" (:report-type %)) templates))
      (is (some #(= "iso27001-audit-trail" (:report-type %)) templates)))))
