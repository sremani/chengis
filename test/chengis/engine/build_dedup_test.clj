(ns chengis.engine.build-dedup-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.build-store :as build-store]))

(def test-db-path "/tmp/chengis-build-dedup-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; find-recent-build-by-commit tests
;; ---------------------------------------------------------------------------

(deftest find-recent-build-no-match-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns nil when no matching build exists"
      (is (nil? (build-store/find-recent-build-by-commit
                  ds "job-1" "abc123" 10))))))

(deftest find-recent-build-nil-commit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns nil when git-commit is nil"
      (is (nil? (build-store/find-recent-build-by-commit
                  ds "job-1" nil 10))))))

(deftest find-recent-build-empty-commit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns nil when git-commit is empty"
      (is (nil? (build-store/find-recent-build-by-commit
                  ds "job-1" "" 10))))))

(deftest find-recent-build-matching-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "returns matching successful build within window"
      ;; Create a build and mark it successful with a git commit
      (let [build (build-store/create-build! ds
                    {:job-id "job-dedup"
                     :trigger-type :manual
                     :parameters {}})
            build-id (:id build)]
        ;; Update to success
        (build-store/update-build-status! ds build-id :success)
        ;; Save git info with a commit
        (build-store/save-git-info! ds build-id
          {:branch "main" :commit "deadbeef123" :commit-short "deadbee"
           :author "dev" :message "fix bug"})
        ;; Search should find it
        (let [found (build-store/find-recent-build-by-commit
                      ds "job-dedup" "deadbeef123" 60)]
          (is (some? found))
          (is (= build-id (:id found))))))))

(deftest find-recent-build-failed-not-returned-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "failed build is NOT returned for dedup"
      (let [build (build-store/create-build! ds
                    {:job-id "job-fail"
                     :trigger-type :manual
                     :parameters {}})
            build-id (:id build)]
        ;; Mark as failure
        (build-store/update-build-status! ds build-id :failure)
        (build-store/save-git-info! ds build-id
          {:branch "main" :commit "failcommit" :commit-short "failcom"
           :author "dev" :message "broken"})
        ;; Should NOT find it
        (is (nil? (build-store/find-recent-build-by-commit
                    ds "job-fail" "failcommit" 60)))))))

(deftest find-recent-build-different-commit-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "different commit returns nil"
      (let [build (build-store/create-build! ds
                    {:job-id "job-diff"
                     :trigger-type :manual
                     :parameters {}})
            build-id (:id build)]
        (build-store/update-build-status! ds build-id :success)
        (build-store/save-git-info! ds build-id
          {:branch "main" :commit "commit-A" :commit-short "comA"
           :author "dev" :message "feature"})
        ;; Search for different commit
        (is (nil? (build-store/find-recent-build-by-commit
                    ds "job-diff" "commit-B" 60)))))))
