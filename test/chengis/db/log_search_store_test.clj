(ns chengis.db.log-search-store-test
  "Tests for build log search functionality."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.org-store :as org-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.log-search-store :as log-search]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def test-db-path "/tmp/chengis-log-search-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- setup-test-data
  "Create org, job, build, and steps with known stdout/stderr content.
   Returns {:ds ... :org-id ... :job ... :build ... :build2 ...}."
  []
  (let [ds (conn/create-datasource test-db-path)
        org-id (:id (org-store/create-org! ds {:name "Test Org" :slug "test-org"}))
        job (job-store/create-job! ds
              {:pipeline-name "my-app"
               :stages [{:stage-name "Build"
                          :steps [{:step-name "Compile" :type :shell :command "make"}]}]}
              :org-id org-id)
        build (build-store/create-build! ds
                {:job-id (:id job)
                 :trigger-type :manual
                 :org-id org-id})
        _ (build-store/update-build-status! ds (:id build) :success
            :started-at "2025-01-01T10:00:00Z"
            :completed-at "2025-01-01T10:05:00Z")
        ;; Save step with stdout containing searchable content
        _ (build-store/save-step-result! ds (:id build) "Build"
            {:step-name "Compile"
             :step-status :success
             :exit-code 0
             :stdout (str "Starting compilation...\n"
                          "Compiling src/main.clj\n"
                          "WARNING: unused variable foo\n"
                          "Compiling src/util.clj\n"
                          "Build successful in 3.2s")
             :stderr "Some deprecation warning here\nAnother stderr line"
             :started-at "2025-01-01T10:00:00Z"
             :completed-at "2025-01-01T10:02:00Z"})
        ;; Create a second build (failure) with different content
        build2 (build-store/create-build! ds
                 {:job-id (:id job)
                  :trigger-type :manual
                  :org-id org-id})
        _ (build-store/update-build-status! ds (:id build2) :failure
            :started-at "2025-01-01T11:00:00Z"
            :completed-at "2025-01-01T11:02:00Z")
        _ (build-store/save-step-result! ds (:id build2) "Build"
            {:step-name "Compile"
             :step-status :failure
             :exit-code 1
             :stdout "Starting compilation...\nERROR: cannot find symbol\nBuild failed"
             :stderr "FATAL: compilation error in main.clj\nProcess exited with code 1"
             :started-at "2025-01-01T11:00:00Z"
             :completed-at "2025-01-01T11:01:00Z"})]
    {:ds ds :org-id org-id :job job :build build :build2 build2}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest search-finds-matching-stdout-test
  (let [{:keys [ds org-id]} (setup-test-data)
        results (log-search/search-build-logs ds "compilation"
                  :org-id org-id)]
    (testing "search finds matching stdout content"
      (is (pos? (count results)))
      (is (some #(str/includes? (:text %) "compilation")
                (mapcat :matching-lines results))))))

(deftest search-finds-matching-stderr-test
  (let [{:keys [ds org-id]} (setup-test-data)
        results (log-search/search-build-logs ds "deprecation"
                  :org-id org-id)]
    (testing "search finds matching stderr content"
      (is (= 1 (count results)))
      (is (some #(= "stderr" (:source %))
                (mapcat :matching-lines results))))))

(deftest case-insensitive-search-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "case-insensitive search works"
      (let [lower-results (log-search/search-build-logs ds "warning" :org-id org-id)
            upper-results (log-search/search-build-logs ds "WARNING" :org-id org-id)
            mixed-results (log-search/search-build-logs ds "Warning" :org-id org-id)]
        (is (pos? (count lower-results)))
        (is (= (count lower-results) (count upper-results)))
        (is (= (count lower-results) (count mixed-results)))))))

(deftest org-id-scoping-test
  (let [{:keys [ds org-id]} (setup-test-data)
        other-org-id (:id (org-store/create-org! ds {:name "Other Org" :slug "other-org"}))]
    (testing "org-id scoping works - no cross-org results"
      (let [own-results (log-search/search-build-logs ds "compilation"
                          :org-id org-id)
            other-results (log-search/search-build-logs ds "compilation"
                            :org-id other-org-id)]
        (is (pos? (count own-results)))
        (is (zero? (count other-results)))))))

(deftest job-filter-test
  (let [{:keys [ds org-id]} (setup-test-data)
        ;; Create another job with a build
        job2 (job-store/create-job! ds
               {:pipeline-name "other-app"
                :stages [{:stage-name "Test"
                           :steps [{:step-name "Run" :type :shell :command "test"}]}]}
               :org-id org-id)
        build3 (build-store/create-build! ds
                 {:job-id (:id job2)
                  :trigger-type :manual
                  :org-id org-id})
        _ (build-store/save-step-result! ds (:id build3) "Test"
            {:step-name "Run"
             :step-status :success
             :exit-code 0
             :stdout "Starting compilation test\nAll tests passed"
             :stderr ""
             :started-at "2025-01-01T12:00:00Z"
             :completed-at "2025-01-01T12:01:00Z"})]
    (testing "job filter works"
      (let [all-results (log-search/search-build-logs ds "compilation"
                          :org-id org-id)
            filtered (log-search/search-build-logs ds "compilation"
                       :org-id org-id :job-name "my-app")]
        ;; "compilation" appears in both jobs
        (is (> (count all-results) (count filtered)))
        (is (every? #(= "my-app" (:job-name %)) filtered))))))

(deftest build-id-filter-test
  (let [{:keys [ds org-id build]} (setup-test-data)]
    (testing "build-id filter works"
      (let [results (log-search/search-build-logs ds "compilation"
                      :org-id org-id :build-id (:id build))]
        (is (= 1 (count results)))
        (is (= (:id build) (:build-id (first results))))))))

(deftest status-filter-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "status filter works"
      (let [success-results (log-search/search-build-logs ds "compilation"
                              :org-id org-id :status :success)
            failure-results (log-search/search-build-logs ds "compilation"
                              :org-id org-id :status :failure)]
        (is (pos? (count success-results)))
        (is (pos? (count failure-results)))
        (is (every? #(= :success (:status %)) success-results))
        (is (every? #(= :failure (:status %)) failure-results))))))

(deftest empty-query-returns-empty-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "empty query returns empty results"
      (is (empty? (log-search/search-build-logs ds "" :org-id org-id)))
      (is (empty? (log-search/search-build-logs ds nil :org-id org-id)))
      (is (empty? (log-search/search-build-logs ds "  " :org-id org-id))))))

(deftest pagination-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "pagination works with limit and offset"
      (let [all-results (log-search/search-build-logs ds "compilation"
                          :org-id org-id :limit 100)
            page1 (log-search/search-build-logs ds "compilation"
                    :org-id org-id :limit 1 :offset 0)
            page2 (log-search/search-build-logs ds "compilation"
                    :org-id org-id :limit 1 :offset 1)]
        (when (> (count all-results) 1)
          (is (= 1 (count page1)))
          (is (= 1 (count page2)))
          (is (not= (:build-id (first page1)) (:build-id (first page2)))))))))

(deftest count-search-results-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "count function returns correct total"
      (let [cnt (log-search/count-search-results ds "compilation" :org-id org-id)]
        (is (pos? cnt))
        (is (number? cnt))))
    (testing "count with empty query returns 0"
      (is (zero? (log-search/count-search-results ds "" :org-id org-id)))
      (is (zero? (log-search/count-search-results ds nil :org-id org-id))))))

(deftest no-results-for-nonmatching-query-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "no results for non-matching query"
      (let [results (log-search/search-build-logs ds "zzz_no_match_xyz_12345"
                      :org-id org-id)]
        (is (empty? results))))))

(deftest context-lines-included-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "context lines included around matches"
      (let [results (log-search/search-build-logs ds "WARNING"
                      :org-id org-id :context-lines 2)
            lines (mapcat :matching-lines results)
            match-lines (filter :is-match lines)
            context-lines (remove :is-match lines)]
        ;; Should have at least one match line
        (is (pos? (count match-lines)))
        ;; Should have context lines around the match
        (is (pos? (count context-lines)))))))

(deftest highlighting-applied-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "highlighting applied correctly"
      (let [results (log-search/search-build-logs ds "WARNING"
                      :org-id org-id)
            match-lines (filter :is-match (mapcat :matching-lines results))]
        (is (pos? (count match-lines)))
        ;; Match lines should have <mark> tags in highlighted text
        (is (every? #(str/includes? (:highlighted %) "<mark>") match-lines))
        (is (every? #(str/includes? (:highlighted %) "</mark>") match-lines))))))

(deftest multiple-matches-in-same-step-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "multiple matches in same step"
      ;; "Compiling" appears twice in the first build's stdout
      (let [results (log-search/search-build-logs ds "Compiling"
                      :org-id org-id :status :success)
            lines (filter :is-match (mapcat :matching-lines results))]
        ;; Should find multiple matching lines within one step
        (is (>= (count lines) 2))))))

(deftest special-characters-in-query-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "special characters in query handled safely - SQL injection prevention"
      ;; These should not cause SQL errors
      (is (empty? (log-search/search-build-logs ds "'; DROP TABLE builds; --"
                    :org-id org-id)))
      (is (empty? (log-search/search-build-logs ds "100%"
                    :org-id org-id)))
      (is (empty? (log-search/search-build-logs ds "test_value"
                    :org-id org-id)))
      ;; Should not throw
      (is (zero? (log-search/count-search-results ds "'; DROP TABLE builds; --"
                   :org-id org-id))))))

(deftest nil-org-id-returns-empty-test
  (let [{:keys [ds]} (setup-test-data)]
    (testing "nil org-id returns empty results"
      (is (empty? (log-search/search-build-logs ds "compilation" :org-id nil)))
      (is (zero? (log-search/count-search-results ds "compilation" :org-id nil))))))

(deftest result-structure-test
  (let [{:keys [ds org-id]} (setup-test-data)]
    (testing "result maps have expected keys"
      (let [results (log-search/search-build-logs ds "compilation"
                      :org-id org-id)
            result (first results)]
        (is (some? result))
        (is (contains? result :build-id))
        (is (contains? result :build-number))
        (is (contains? result :job-name))
        (is (contains? result :stage-name))
        (is (contains? result :step-name))
        (is (contains? result :status))
        (is (contains? result :matching-lines))
        ;; Check matching line structure
        (let [line (first (:matching-lines result))]
          (is (contains? line :line-number))
          (is (contains? line :text))
          (is (contains? line :highlighted))
          (is (contains? line :is-match))
          (is (contains? line :source)))))))
