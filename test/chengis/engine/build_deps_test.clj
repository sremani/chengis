(ns chengis.engine.build-deps-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.dependency-store :as dep-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.engine.build-deps :as build-deps]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-build-deps-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Trigger evaluation
;; ---------------------------------------------------------------------------

(deftest should-trigger-success
  (testing "trigger-on success"
    (is (true? (build-deps/should-trigger-dependent? "success" :success)))
    (is (false? (build-deps/should-trigger-dependent? "success" :failure)))))

(deftest should-trigger-failure
  (testing "trigger-on failure"
    (is (false? (build-deps/should-trigger-dependent? "failure" :success)))
    (is (true? (build-deps/should-trigger-dependent? "failure" :failure)))))

(deftest should-trigger-any
  (testing "trigger-on any"
    (is (true? (build-deps/should-trigger-dependent? "any" :success)))
    (is (true? (build-deps/should-trigger-dependent? "any" :failure)))
    (is (false? (build-deps/should-trigger-dependent? "any" :aborted)))))

;; ---------------------------------------------------------------------------
;; Add dependency with cycle detection
;; ---------------------------------------------------------------------------

(deftest add-dependency-self-reference
  (let [ds (conn/create-datasource test-db-path)]
    (testing "cannot add self-dependency"
      (let [result (build-deps/add-dependency! ds
                     {:job-id "job-A" :depends-on-job-id "job-A" :org-id "org-1"})]
        (is (some? (:error result)))
        (is (= "A job cannot depend on itself" (:error result)))))))

(deftest add-dependency-with-cycle
  (let [ds (conn/create-datasource test-db-path)]
    (testing "rejects dependency that would create cycle"
      (dep-store/create-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (let [result (build-deps/add-dependency! ds
                     {:job-id "job-A" :depends-on-job-id "job-B" :org-id "org-1"})]
        (is (some? (:error result)))
        (is (= "Adding this dependency would create a cycle" (:error result)))))))

(deftest add-dependency-success
  (let [ds (conn/create-datasource test-db-path)]
    (testing "adds valid dependency"
      (let [result (build-deps/add-dependency! ds
                     {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})]
        (is (some? (:ok result)))
        (is (= "job-B" (:job-id (:ok result))))))))

(deftest add-dependency-duplicate
  (let [ds (conn/create-datasource test-db-path)]
    (testing "rejects duplicate dependency"
      (build-deps/add-dependency! ds
        {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})
      (let [result (build-deps/add-dependency! ds
                     {:job-id "job-B" :depends-on-job-id "job-A" :org-id "org-1"})]
        (is (some? (:error result)))))))

;; ---------------------------------------------------------------------------
;; Downstream triggering
;; ---------------------------------------------------------------------------

(deftest trigger-downstream-flag-disabled
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:build-dependencies false}}}]
    (testing "no-op when flag disabled"
      (is (nil? (build-deps/trigger-downstream-jobs! system
                  {:build-id "b1" :job-id "j1" :org-id "org-1" :status :success}
                  nil))))))

(deftest trigger-downstream-no-dependents
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:build-dependencies true}}}]
    (testing "no-op when no dependents"
      (let [result (build-deps/trigger-downstream-jobs! system
                     {:build-id "b1" :job-id "j1" :org-id "org-1" :status :success}
                     nil)]
        (is (or (nil? result) (empty? result)))))))

(deftest trigger-downstream-on-success
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:build-dependencies true}}}]
    (testing "triggers downstream job on success"
      ;; Create upstream and downstream jobs
      (let [upstream (job-store/create-job! ds
                       {:pipeline-name "upstream"
                        :stages [{:stage-name "Build" :steps []}]}
                       :org-id "org-1")
            downstream (job-store/create-job! ds
                         {:pipeline-name "downstream"
                          :stages [{:stage-name "Deploy" :steps []}]}
                         :org-id "org-1")]
        ;; Add dependency
        (dep-store/create-dependency! ds
          {:job-id (:id downstream)
           :depends-on-job-id (:id upstream)
           :org-id "org-1"
           :trigger-on "success"})

        ;; Create upstream build
        (let [upstream-build (build-store/create-build! ds
                               {:job-id (:id upstream)
                                :trigger-type :manual
                                :org-id "org-1"})]
          ;; Trigger downstream
          (let [triggered (build-deps/trigger-downstream-jobs! system
                            {:build-id (:id upstream-build)
                             :job-id (:id upstream)
                             :org-id "org-1"
                             :status :success}
                            nil)]
            (is (= 1 (count (remove nil? triggered))))
            ;; Verify trigger was recorded
            (let [triggers (dep-store/get-triggered-builds ds (:id upstream-build))]
              (is (= 1 (count triggers)))
              (is (= (:id downstream) (:target-job-id (first triggers)))))))))))

(deftest trigger-downstream-skipped-on-wrong-status
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:build-dependencies true}}}]
    (testing "does not trigger when status doesn't match trigger-on"
      (let [upstream (job-store/create-job! ds
                       {:pipeline-name "upstream2"
                        :stages [{:stage-name "Build" :steps []}]}
                       :org-id "org-1")
            downstream (job-store/create-job! ds
                         {:pipeline-name "downstream2"
                          :stages [{:stage-name "Deploy" :steps []}]}
                         :org-id "org-1")]
        (dep-store/create-dependency! ds
          {:job-id (:id downstream)
           :depends-on-job-id (:id upstream)
           :org-id "org-1"
           :trigger-on "success"})
        (let [upstream-build (build-store/create-build! ds
                               {:job-id (:id upstream)
                                :trigger-type :manual
                                :org-id "org-1"})]
          ;; Build failed — should NOT trigger downstream
          (let [triggered (build-deps/trigger-downstream-jobs! system
                            {:build-id (:id upstream-build)
                             :job-id (:id upstream)
                             :org-id "org-1"
                             :status :failure}
                            nil)]
            (is (empty? (remove nil? triggered)))))))))
