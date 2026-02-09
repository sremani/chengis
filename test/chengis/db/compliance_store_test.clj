(ns chengis.db.compliance-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.compliance-store :as compliance-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-compliance-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest create-and-get-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and retrieve report template"
      (let [tmpl (compliance-store/create-report-template! ds
                   {:org-id "org-1"
                    :report-type "soc2-access-control"
                    :title "SOC2 Access Control"
                    :description "Access control review"
                    :filters {:actions ["login" "logout"]}
                    :created-by "admin"})]
        (is (some? (:id tmpl)))
        (is (= "SOC2 Access Control" (:title tmpl)))
        (let [retrieved (compliance-store/get-report-template ds (:id tmpl))]
          (is (= "soc2-access-control" (:report-type retrieved)))
          (is (= {:actions ["login" "logout"]} (:filters retrieved))))))))

(deftest list-templates-by-org-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "templates are scoped by org"
      (compliance-store/create-report-template! ds
        {:org-id "org-1" :report-type "soc2" :title "Org1 Report"})
      (compliance-store/create-report-template! ds
        {:org-id "org-2" :report-type "iso" :title "Org2 Report"})
      (let [org1-templates (compliance-store/list-report-templates ds :org-id "org-1")
            org2-templates (compliance-store/list-report-templates ds :org-id "org-2")
            all-templates (compliance-store/list-report-templates ds)]
        (is (= 1 (count org1-templates)))
        (is (= 1 (count org2-templates)))
        (is (= 2 (count all-templates)))))))

(deftest update-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "update report template"
      (let [tmpl (compliance-store/create-report-template! ds
                   {:org-id "org-1" :report-type "soc2"
                    :title "Original" :description "Original desc"})
            _ (compliance-store/update-report-template! ds (:id tmpl)
                {:title "Updated" :description "New desc"})
            updated (compliance-store/get-report-template ds (:id tmpl))]
        (is (= "Updated" (:title updated)))
        (is (= "New desc" (:description updated)))))))

(deftest delete-template-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete report template"
      (let [tmpl (compliance-store/create-report-template! ds
                   {:org-id "org-1" :report-type "soc2" :title "Delete Me"})]
        (compliance-store/delete-report-template! ds (:id tmpl))
        (is (nil? (compliance-store/get-report-template ds (:id tmpl))))))))

(deftest create-and-list-runs-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and list report runs"
      (let [tmpl (compliance-store/create-report-template! ds
                   {:org-id "org-1" :report-type "soc2" :title "Test Report"})
            run (compliance-store/create-report-run! ds
                  {:report-id (:id tmpl)
                   :org-id "org-1"
                   :generated-by "admin"
                   :period-start "2026-01-01"
                   :period-end "2026-01-31"})]
        (is (some? (:id run)))
        (is (= "pending" (:status run)))
        (let [runs (compliance-store/list-report-runs ds :org-id "org-1")]
          (is (= 1 (count runs))))))))

(deftest update-run-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "update report run status and summary"
      (let [tmpl (compliance-store/create-report-template! ds
                   {:org-id "org-1" :report-type "soc2" :title "Test Report"})
            run (compliance-store/create-report-run! ds
                  {:report-id (:id tmpl) :org-id "org-1"
                   :generated-by "admin"})
            _ (compliance-store/update-report-run! ds (:id run)
                {:status "completed"
                 :summary "{\"test\":true}"
                 :report-hash "abc123"
                 :completed-at "2026-01-15T12:00:00Z"})
            updated (compliance-store/get-report-run ds (:id run))]
        (is (= "completed" (:status updated)))
        (is (= "{\"test\":true}" (:summary updated)))
        (is (= "abc123" (:report-hash updated)))))))
