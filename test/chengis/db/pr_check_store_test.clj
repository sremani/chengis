(ns chengis.db.pr-check-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.pr-check-store :as pr-store]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-pr-check-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Check configuration tests
;; ---------------------------------------------------------------------------

(deftest create-and-list-checks
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can create and list checks for a job"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "chengis/build"
                                  :description "Main build check"
                                  :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "chengis/test"
                                  :description "Test suite"
                                  :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "chengis/lint"
                                  :description "Code linting"
                                  :required false})

      (let [checks (pr-store/list-checks ds "job-1")]
        (is (= 3 (count checks)))
        (is (= #{"chengis/build" "chengis/test" "chengis/lint"}
               (set (map :check-name checks))))))))

(deftest list-checks-scoped-by-org
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-checks with org-id scopes correctly"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "job-2" :org-id "org-B"
                                  :check-name "build" :required true})

      (is (= 1 (count (pr-store/list-checks ds "job-1" :org-id "org-A"))))
      (is (= 0 (count (pr-store/list-checks ds "job-1" :org-id "org-B")))))))

(deftest delete-check
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can delete a check"
      (let [check (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                              :check-name "build" :required true})]
        (is (= 1 (count (pr-store/list-checks ds "job-1"))))
        (is (true? (pr-store/delete-check! ds (:id check))))
        (is (= 0 (count (pr-store/list-checks ds "job-1"))))))))

(deftest delete-check-respects-org
  (let [ds (conn/create-datasource test-db-path)]
    (testing "delete-check! with wrong org-id fails"
      (let [check (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                              :check-name "build" :required true})]
        (is (false? (pr-store/delete-check! ds (:id check) :org-id "org-B")))
        (is (= 1 (count (pr-store/list-checks ds "job-1"))))))))

(deftest update-check
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can update check description and required status"
      (let [check (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                              :check-name "build"
                                              :description "Old desc"
                                              :required true})]
        (pr-store/update-check! ds (:id check) {:description "New desc" :required false})
        (let [updated (pr-store/get-check ds (:id check))]
          (is (= "New desc" (:description updated)))
          (is (false? (:required updated))))))))

;; ---------------------------------------------------------------------------
;; Check result tests
;; ---------------------------------------------------------------------------

(deftest record-and-get-check-results
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can record and retrieve check results"
      (pr-store/record-check-result! ds
        {:build-id "build-1" :job-id "job-1" :org-id "org-A"
         :check-name "chengis/build" :status :pending
         :commit-sha "abc123" :repo-url "https://github.com/owner/repo"})

      (let [results (pr-store/get-build-check-results ds "build-1")]
        (is (= 1 (count results)))
        (is (= :pending (:status (first results))))
        (is (= "abc123" (:commit-sha (first results))))))))

(deftest check-result-upsert
  (let [ds (conn/create-datasource test-db-path)]
    (testing "recording a result for existing (build-id, check-name) updates status"
      (pr-store/record-check-result! ds
        {:build-id "build-1" :job-id "job-1" :org-id "org-A"
         :check-name "chengis/build" :status :pending
         :commit-sha "abc123"})

      ;; Update to success
      (pr-store/record-check-result! ds
        {:build-id "build-1" :job-id "job-1" :org-id "org-A"
         :check-name "chengis/build" :status :success
         :commit-sha "abc123"})

      (let [results (pr-store/get-build-check-results ds "build-1")]
        (is (= 1 (count results)))
        (is (= :success (:status (first results))))))))

(deftest get-commit-check-results
  (let [ds (conn/create-datasource test-db-path)]
    (testing "can get check results by commit SHA"
      (pr-store/record-check-result! ds
        {:build-id "build-1" :job-id "job-1" :org-id "org-A"
         :check-name "build" :status :success :commit-sha "sha1"})
      (pr-store/record-check-result! ds
        {:build-id "build-2" :job-id "job-2" :org-id "org-A"
         :check-name "test" :status :failure :commit-sha "sha1"})
      (pr-store/record-check-result! ds
        {:build-id "build-3" :job-id "job-1" :org-id "org-B"
         :check-name "build" :status :success :commit-sha "sha2"})

      (is (= 2 (count (pr-store/get-commit-check-results ds "sha1"))))
      (is (= 2 (count (pr-store/get-commit-check-results ds "sha1" :org-id "org-A"))))
      (is (= 0 (count (pr-store/get-commit-check-results ds "sha1" :org-id "org-B"))))
      (is (= 1 (count (pr-store/get-commit-check-results ds "sha2")))))))

;; ---------------------------------------------------------------------------
;; Required checks evaluation
;; ---------------------------------------------------------------------------

(deftest all-required-checks-passing
  (let [ds (conn/create-datasource test-db-path)]
    (testing "evaluates required checks correctly"
      ;; Set up required checks
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "test" :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "lint" :required false})

      ;; All required passing
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "test" :status :success})

      (let [result (pr-store/all-required-checks-passing? ds "job-1" "b1")]
        (is (true? (:passing? result)))
        (is (= 2 (:total result)))
        (is (= 2 (:passed result)))))))

(deftest required-checks-not-passing-when-one-fails
  (let [ds (conn/create-datasource test-db-path)]
    (testing "fails when any required check fails"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "test" :required true})

      ;; build passes but test fails
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "test" :status :failure})

      (let [result (pr-store/all-required-checks-passing? ds "job-1" "b1")]
        (is (false? (:passing? result)))
        (is (= 1 (:passed result)))))))

(deftest required-checks-not-passing-when-missing
  (let [ds (conn/create-datasource test-db-path)]
    (testing "fails when required check has no result"
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "job-1" :org-id "org-A"
                                  :check-name "test" :required true})

      ;; Only build reported
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})

      (let [result (pr-store/all-required-checks-passing? ds "job-1" "b1")]
        (is (false? (:passing? result)))
        (is (= 1 (:passed result)))
        (is (= :missing (:status (second (:checks result)))))))))

(deftest cleanup-old-results
  (let [ds (conn/create-datasource test-db-path)]
    (testing "cleanup removes old results"
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "job-1" :check-name "build" :status :success})
      (is (= 1 (count (pr-store/get-build-check-results ds "b1"))))
      ;; Clean up with 0 days retention (deletes everything)
      (pr-store/cleanup-old-results! ds 0)
      (is (= 0 (count (pr-store/get-build-check-results ds "b1")))))))
