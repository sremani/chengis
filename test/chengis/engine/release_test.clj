(ns chengis.engine.release-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.release-store :as release-store]
            [chengis.engine.release :as release-engine]))

(def test-db-path "/tmp/chengis-release-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(defn- create-test-build! [ds org-id job-id status]
  (job-store/create-job! ds {:pipeline-name job-id} :org-id org-id)
  (let [build (build-store/create-build! ds {:job-id job-id :org-id org-id})]
    (build-store/update-build-status! ds (:id build) status)
    (build-store/get-build ds (:id build) :org-id org-id)))

(deftest create-release-from-build-test
  (let [ds (test-ds)]
    (testing "creates release from successful build"
      (let [build (create-test-build! ds "org-1" "my-app" :success)
            result (release-engine/create-release-from-build! ds
                     {:org-id "org-1" :build-id (:id build) :version "1.0.0"
                      :title "First release" :created-by "user-1"})]
        (is (true? (:success result)))
        (is (some? (:release result)))
        (is (= "1.0.0" (get-in result [:release :version])))))

    (testing "rejects non-existent build"
      (let [result (release-engine/create-release-from-build! ds
                     {:org-id "org-1" :build-id "non-existent"})]
        (is (false? (:success result)))
        (is (= "Build not found" (:reason result)))))

    (testing "rejects failed build"
      (let [build (create-test-build! ds "org-1" "fail-app" :failure)
            result (release-engine/create-release-from-build! ds
                     {:org-id "org-1" :build-id (:id build)})]
        (is (false? (:success result)))
        (is (clojure.string/includes? (:reason result) "failure"))))))

(deftest auto-version-release-test
  (let [ds (test-ds)]
    (testing "auto-version creates and publishes"
      (let [build (create-test-build! ds "org-1" "auto-app" :success)
            result (release-engine/auto-version-release! ds
                     {:org-id "org-1" :build-id (:id build) :created-by "user-1"})]
        (is (true? (:success result)))
        (let [r (release-store/get-release ds (get-in result [:release :id]))]
          (is (= "published" (:status r))))))))
