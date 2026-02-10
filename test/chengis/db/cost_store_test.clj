(ns chengis.db.cost-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.cost-store :as cost-store]))

(def test-db-path "/tmp/chengis-cost-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

(deftest record-build-cost-test
  (let [ds (test-ds)]
    (testing "record-build-cost! creates an entry"
      (let [row (cost-store/record-build-cost! ds
                  {:build-id "build-1"
                   :job-id "job-1"
                   :org-id "org-1"
                   :agent-id "agent-1"
                   :started-at "2025-01-15T10:00:00Z"
                   :ended-at "2025-01-15T10:01:00Z"
                   :duration-s 60.0
                   :cost-per-hour 2.0
                   :computed-cost 0.0333})]
        (is (some? (:id row)))
        (is (= "build-1" (:build-id row)))
        (is (= 60.0 (:duration-s row)))))))

(deftest get-build-cost-test
  (let [ds (test-ds)]
    (testing "get-build-cost retrieves by build-id"
      (cost-store/record-build-cost! ds
        {:build-id "build-2" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:05:00Z"
         :duration-s 300.0 :cost-per-hour 1.0 :computed-cost 0.0833})
      (let [cost (cost-store/get-build-cost ds "build-2")]
        (is (some? cost))
        (is (= "build-2" (:build-id cost)))))))

(deftest get-org-cost-summary-test
  (let [ds (test-ds)]
    (testing "get-org-cost-summary groups by job"
      (cost-store/record-build-cost! ds
        {:build-id "b1" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 0.0167})
      (cost-store/record-build-cost! ds
        {:build-id "b2" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T11:00:00Z" :ended-at "2025-01-15T11:02:00Z"
         :duration-s 120.0 :cost-per-hour 1.0 :computed-cost 0.0333})
      (cost-store/record-build-cost! ds
        {:build-id "b3" :job-id "job-2" :org-id "org-1"
         :started-at "2025-01-15T12:00:00Z" :ended-at "2025-01-15T12:10:00Z"
         :duration-s 600.0 :cost-per-hour 1.0 :computed-cost 0.1667})
      (let [summary (cost-store/get-org-cost-summary ds :org-id "org-1")]
        (is (= 2 (count summary)))
        ;; job-2 should be first (higher cost)
        (is (= "job-2" (:job-id (first summary))))))))

(deftest get-org-cost-summary-org-scoped-test
  (let [ds (test-ds)]
    (testing "get-org-cost-summary is org-scoped"
      (cost-store/record-build-cost! ds
        {:build-id "b1" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 0.05})
      (cost-store/record-build-cost! ds
        {:build-id "b2" :job-id "job-1" :org-id "org-2"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 0.05})
      (is (= 1 (count (cost-store/get-org-cost-summary ds :org-id "org-1"))))
      (is (= 1 (count (cost-store/get-org-cost-summary ds :org-id "org-2")))))))

(deftest get-total-cost-test
  (let [ds (test-ds)]
    (testing "get-total-cost returns sum"
      (cost-store/record-build-cost! ds
        {:build-id "b1" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 1.5})
      (cost-store/record-build-cost! ds
        {:build-id "b2" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T11:00:00Z" :ended-at "2025-01-15T11:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 2.5})
      (let [total (cost-store/get-total-cost ds :org-id "org-1")]
        (is (= 4.0 total))))))

(deftest cleanup-old-costs-test
  (let [ds (test-ds)]
    (testing "cleanup-old-costs! removes old records"
      (cost-store/record-build-cost! ds
        {:build-id "b1" :job-id "job-1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 1.0})
      (is (= 1 (cost-store/count-cost-entries ds)))
      (let [deleted (cost-store/cleanup-old-costs! ds 0)]
        (is (pos? deleted))
        (is (= 0 (cost-store/count-cost-entries ds)))))))

(deftest count-cost-entries-test
  (let [ds (test-ds)]
    (testing "count helpers work"
      (is (= 0 (cost-store/count-cost-entries ds)))
      (cost-store/record-build-cost! ds
        {:build-id "b1" :job-id "j1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"
         :duration-s 60.0 :cost-per-hour 1.0 :computed-cost 1.0})
      (is (= 1 (cost-store/count-cost-entries ds)))
      (is (= 1 (cost-store/count-cost-entries ds :org-id "org-1")))
      (is (= 0 (cost-store/count-cost-entries ds :org-id "other"))))))
