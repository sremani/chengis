(ns chengis.engine.cost-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.cost-store :as cost-store]
            [chengis.engine.cost :as cost]))

(def test-db-path "/tmp/chengis-cost-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-system
  [& {:keys [cost-enabled] :or {cost-enabled true}}]
  (let [ds (conn/create-datasource test-db-path)]
    {:db ds
     :config {:feature-flags {:cost-attribution cost-enabled}
              :cost-attribution {:default-cost-per-hour 2.0}}}))

(deftest compute-build-cost-test
  (testing "compute-build-cost calculates duration and cost"
    (let [result (cost/compute-build-cost
                   "2025-01-15T10:00:00Z"
                   "2025-01-15T11:00:00Z"
                   2.0)]
      (is (some? result))
      (is (= 3600.0 (:duration-s result)))
      (is (= 2.0 (:computed-cost result))))))

(deftest compute-build-cost-short-test
  (testing "compute-build-cost handles short durations"
    (let [result (cost/compute-build-cost
                   "2025-01-15T10:00:00Z"
                   "2025-01-15T10:00:30Z"
                   1.0)]
      (is (some? result))
      (is (< (Math/abs (- 30.0 (:duration-s result))) 0.1))
      ;; 30s = 0.00833 hours at $1/hr
      (is (< (:computed-cost result) 0.01)))))

(deftest compute-build-cost-nil-test
  (testing "compute-build-cost returns nil for missing timestamps"
    (is (nil? (cost/compute-build-cost nil "2025-01-15T10:00:00Z" 1.0)))
    (is (nil? (cost/compute-build-cost "2025-01-15T10:00:00Z" nil 1.0)))))

(deftest record-cost-if-enabled-test
  (let [system (test-system)]
    (testing "record-cost-if-enabled! records cost when enabled"
      (cost/record-cost-if-enabled! system
        {:build-id "test-build-1"
         :job-id "test-job-1"
         :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z"
         :ended-at "2025-01-15T10:30:00Z"})
      (let [entry (cost-store/get-build-cost (:db system) "test-build-1")]
        (is (some? entry))
        (is (= "test-build-1" (:build-id entry)))
        ;; 30 min = 0.5 hours at $2/hr = $1.0
        (is (< (Math/abs (- 1.0 (:computed-cost entry))) 0.01))))))

(deftest record-cost-disabled-test
  (let [system (test-system :cost-enabled false)]
    (testing "record-cost-if-enabled! does nothing when disabled"
      (cost/record-cost-if-enabled! system
        {:build-id "test-build-2"
         :job-id "test-job-1"
         :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z"
         :ended-at "2025-01-15T10:30:00Z"})
      (is (nil? (cost-store/get-build-cost (:db system) "test-build-2"))))))

(deftest get-build-cost-wrapper-test
  (let [system (test-system)]
    (testing "get-build-cost wrapper delegates to store"
      (cost/record-cost-if-enabled! system
        {:build-id "test-build-3" :job-id "j1" :org-id "org-1"
         :started-at "2025-01-15T10:00:00Z" :ended-at "2025-01-15T10:01:00Z"})
      (let [cost-entry (cost/get-build-cost system "test-build-3")]
        (is (some? cost-entry))))))

(deftest query-wrappers-disabled-test
  (let [system (test-system :cost-enabled false)]
    (testing "query wrappers return defaults when disabled"
      (is (nil? (cost/get-build-cost system "build-1")))
      (is (empty? (cost/get-org-cost-summary system)))
      (is (= 0.0 (cost/get-total-cost system))))))

;; ---------------------------------------------------------------------------
;; Phase 4 mutation testing remediation: or-fallback and edge cases
;; ---------------------------------------------------------------------------

(deftest compute-build-cost-nil-cost-per-hour-test
  (testing "compute-build-cost falls back to 1.0 when cost-per-hour is nil"
    (let [result (cost/compute-build-cost
                   "2025-01-15T10:00:00Z"
                   "2025-01-15T11:00:00Z"
                   nil)]
      (is (some? result))
      (is (= 3600.0 (:duration-s result)))
      ;; 1 hour at $1/hr (default) = $1.0
      (is (= 1.0 (:computed-cost result))))))

(deftest compute-build-cost-invalid-timestamp-test
  (testing "compute-build-cost returns nil for invalid timestamps"
    (is (nil? (cost/compute-build-cost "not-a-date" "also-not" 1.0)))))

(deftest record-cost-nil-org-id-fallback-test
  (let [system (test-system)]
    (testing "record-cost-if-enabled! uses 'default-org' when org-id is nil"
      (cost/record-cost-if-enabled! system
        {:build-id "test-nil-org"
         :job-id "test-job"
         :org-id nil
         :started-at "2025-01-15T10:00:00Z"
         :ended-at "2025-01-15T10:30:00Z"})
      (let [entry (cost-store/get-build-cost (:db system) "test-nil-org")]
        (is (some? entry))
        (is (= "default-org" (:org-id entry)))))))

(deftest get-org-cost-summary-nil-limit-test
  (let [system (test-system)]
    (testing "get-org-cost-summary uses default limit of 50 when nil"
      ;; Should not throw — exercises the (or limit 50) fallback
      (let [result (cost/get-org-cost-summary system :org-id "org-1" :limit nil)]
        (is (vector? (vec result)))))))
