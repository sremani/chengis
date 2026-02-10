(ns chengis.engine.pr-checks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.pr-check-store :as pr-store]
            [chengis.engine.pr-checks :as pr-checks]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-pr-checks-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Merge readiness evaluation
;; ---------------------------------------------------------------------------

(deftest evaluate-merge-readiness-all-passing
  (let [ds (conn/create-datasource test-db-path)]
    (testing "merge readiness returns true when all required checks pass"
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "test" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "build" :status :success})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "test" :status :success})

      (let [result (pr-checks/evaluate-merge-readiness ds "j1" "b1")]
        (is (true? (:ready? result)))
        (is (= 2 (:total result)))
        (is (= 2 (:passed result)))
        (is (= "All 2 required checks passed" (:summary result)))))))

(deftest evaluate-merge-readiness-partial
  (let [ds (conn/create-datasource test-db-path)]
    (testing "merge readiness returns false when some checks fail"
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "test" :required true})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "build" :status :success})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "test" :status :failure})

      (let [result (pr-checks/evaluate-merge-readiness ds "j1" "b1")]
        (is (false? (:ready? result)))
        (is (= 1 (:passed result)))
        (is (= "1/2 required checks passed" (:summary result)))))))

(deftest evaluate-merge-readiness-no-checks
  (let [ds (conn/create-datasource test-db-path)]
    (testing "merge readiness with no required checks returns false"
      (let [result (pr-checks/evaluate-merge-readiness ds "j1" "b1")]
        (is (false? (:ready? result)))
        (is (= 0 (:total result)))))))

;; ---------------------------------------------------------------------------
;; Commit status summary
;; ---------------------------------------------------------------------------

(deftest commit-status-summary-basic
  (let [ds (conn/create-datasource test-db-path)]
    (testing "commit status summary aggregates check results"
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "build"
         :status :success :commit-sha "abc123"})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "test"
         :status :success :commit-sha "abc123"})

      (let [summary (pr-checks/get-commit-status-summary ds "abc123")]
        (is (= "abc123" (:commit-sha summary)))
        (is (= 2 (count (:checks summary))))
        (is (true? (:all-passing? summary)))))))

(deftest commit-status-summary-mixed-results
  (let [ds (conn/create-datasource test-db-path)]
    (testing "commit status summary with mixed results"
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "build"
         :status :success :commit-sha "abc123"})
      (pr-store/record-check-result! ds
        {:build-id "b1" :job-id "j1" :check-name "test"
         :status :failure :commit-sha "abc123"})

      (let [summary (pr-checks/get-commit-status-summary ds "abc123")]
        (is (false? (:all-passing? summary)))))))

;; ---------------------------------------------------------------------------
;; Feature flag gating
;; ---------------------------------------------------------------------------

(deftest report-check-noop-when-flag-disabled
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:pr-status-checks false}}}]
    (testing "report-check-started! is a no-op when flag is disabled"
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-checks/report-check-started! system
        {:build-id "b1" :job-id "j1" :org-id "org-A"
         :commit-sha "abc123" :repo-url "https://github.com/o/r"})

      ;; No results should be recorded
      (is (= 0 (count (pr-store/get-build-check-results ds "b1")))))))

(deftest report-check-noop-when-no-commit
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:pr-status-checks true}}}]
    (testing "report-check-started! is a no-op when no commit-sha"
      (pr-store/create-check! ds {:job-id "j1" :org-id "org-A"
                                  :check-name "build" :required true})
      (pr-checks/report-check-started! system
        {:build-id "b1" :job-id "j1" :org-id "org-A"
         :commit-sha nil :repo-url "https://github.com/o/r"})

      (is (= 0 (count (pr-store/get-build-check-results ds "b1")))))))
