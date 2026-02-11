(ns chengis.db.paginated-stores-test
  "Integration tests for cursor-based pagination across stores."
  (:require [clojure.test :refer :all]
            [chengis.db.connection :as conn]
            [chengis.db.build-store :as build-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.pagination :as pagination]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]))

(def ^:dynamic *ds* nil)
(def test-db-path (str "/tmp/chengis-pagination-test-" (System/currentTimeMillis) ".db"))

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (binding [*ds* (conn/create-datasource test-db-path)]
    (f))
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

;; ---- Build pagination tests ----

(deftest build-pagination-test
  (testing "paginate through builds"
    ;; Create a job first
    (let [pipeline {:pipeline-name "test-job" :stages []}]
      (job-store/create-job! *ds* pipeline)
      ;; Create 25 builds
      (doseq [i (range 25)]
        (build-store/create-build! *ds* {:job-id (str "test-job")
                                         :job-name "test-job"
                                         :trigger-type "manual"
                                         :parameters {}}))
      ;; First page
      (let [page1 (build-store/list-builds *ds* {:limit 10 :cursor-mode true})]
        (is (= 10 (count (:items page1))))
        (is (true? (:has-more page1)))
        (is (some? (:next-cursor page1)))

        ;; Second page
        (let [page2 (build-store/list-builds *ds* {:limit 10
                                                    :cursor-mode true
                                                    :cursor (:next-cursor page1)})]
          (is (= 10 (count (:items page2))))
          (is (true? (:has-more page2)))

          ;; Third page
          (let [page3 (build-store/list-builds *ds* {:limit 10
                                                      :cursor-mode true
                                                      :cursor (:next-cursor page2)})]
            (is (= 5 (count (:items page3))))
            (is (false? (:has-more page3)))

            ;; Verify no duplicates across pages
            (let [all-ids (concat (map :id (:items page1))
                                 (map :id (:items page2))
                                 (map :id (:items page3)))]
              (is (= 25 (count all-ids)))
              (is (= 25 (count (set all-ids)))))))))))

(deftest build-pagination-backward-compat-test
  (testing "list-builds without cursor returns vector as before"
    (let [pipeline {:pipeline-name "compat-job" :stages []}]
      (job-store/create-job! *ds* pipeline)
      (build-store/create-build! *ds* {:job-id "compat-job"
                                        :job-name "compat-job"
                                        :trigger-type "manual"
                                        :parameters {}})
      (let [result (build-store/list-builds *ds*)]
        (is (vector? result))
        (is (= 1 (count result)))))))

(deftest build-pagination-with-job-filter-test
  (testing "cursor pagination with job-id filter"
    (let [pipeline1 {:pipeline-name "job-a" :stages []}
          pipeline2 {:pipeline-name "job-b" :stages []}]
      (job-store/create-job! *ds* pipeline1)
      (job-store/create-job! *ds* pipeline2)
      (doseq [_ (range 5)]
        (build-store/create-build! *ds* {:job-id "job-a" :job-name "job-a"
                                          :trigger-type "manual" :parameters {}}))
      (doseq [_ (range 3)]
        (build-store/create-build! *ds* {:job-id "job-b" :job-name "job-b"
                                          :trigger-type "manual" :parameters {}}))
      (let [page (build-store/list-builds *ds* {:job-id "job-a" :limit 10 :cursor-mode true})]
        (is (= 5 (count (:items page))))
        (is (false? (:has-more page)))))))

;; ---- Job pagination tests ----

(deftest job-pagination-test
  (testing "paginate through jobs"
    (doseq [i (range 15)]
      (job-store/create-job! *ds* {:pipeline-name (str "job-" (format "%03d" i)) :stages []}))
    (let [page1 (job-store/list-jobs *ds* :limit 5 :cursor-mode true)]
      (is (= 5 (count (:items page1))))
      (is (true? (:has-more page1)))
      (let [page2 (job-store/list-jobs *ds* :limit 5 :cursor-mode true
                                        :cursor (:next-cursor page1))]
        (is (= 5 (count (:items page2))))
        (is (true? (:has-more page2)))
        (let [page3 (job-store/list-jobs *ds* :limit 5 :cursor-mode true
                                          :cursor (:next-cursor page2))]
          (is (= 5 (count (:items page3))))
          (is (false? (:has-more page3))))))))

(deftest job-pagination-backward-compat-test
  (testing "list-jobs without cursor returns vector"
    (job-store/create-job! *ds* {:pipeline-name "compat" :stages []})
    (let [result (job-store/list-jobs *ds*)]
      (is (vector? result)))))

;; ---- Audit pagination tests ----

(deftest audit-cursor-pagination-test
  (testing "cursor pagination through audit logs"
    (doseq [i (range 12)]
      (audit-store/insert-audit! *ds*
        {:user-id "user1" :username "testuser"
         :action "test-action" :resource-type "build"
         :resource-id (str "build-" i)}))
    (let [page1 (audit-store/query-audits *ds* {:limit 5 :cursor-mode true})]
      (is (= 5 (count (:items page1))))
      (is (true? (:has-more page1)))
      (let [page2 (audit-store/query-audits *ds* {:limit 5 :cursor-mode true
                                                   :cursor (:next-cursor page1)})]
        (is (= 5 (count (:items page2))))
        (is (true? (:has-more page2)))
        (let [page3 (audit-store/query-audits *ds* {:limit 5 :cursor-mode true
                                                     :cursor (:next-cursor page2)})]
          (is (= 2 (count (:items page3))))
          (is (false? (:has-more page3)))
          ;; Verify all unique
          (let [all-ids (concat (map :id (:items page1))
                               (map :id (:items page2))
                               (map :id (:items page3)))]
            (is (= 12 (count (set all-ids))))))))))
