(ns chengis.engine.approval-concurrency-test
  "Concurrency tests for approval gate resolution.
   Verifies single-winner semantics under concurrent approve/reject races."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.approval-store :as approval-store]
            [chengis.web.auth :as auth]
            [chengis.web.handlers :as handlers]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-approval-concurrency.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Test 1: Concurrent approve and reject — only one wins
;; ---------------------------------------------------------------------------

(deftest concurrent-approve-and-reject-only-one-wins-test
  (let [ds (conn/create-datasource test-db-path)
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-1" :stage-name "deploy"
                   :required-role "developer" :message "Approve?" :timeout-minutes 60})]

    (testing "exactly one of approve or reject wins under concurrency"
      ;; Launch concurrent approve + reject
      (let [approve-f (future (approval-store/approve-gate! ds gate-id "user-a"))
            reject-f  (future (approval-store/reject-gate! ds gate-id "user-b"))
            approve-count @approve-f
            reject-count  @reject-f]
        ;; Exactly one should succeed (update-count = 1), the other should noop (0)
        (is (= 1 (+ approve-count reject-count))
            "Exactly one of approve/reject should succeed")
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (#{"approved" "rejected"} (:status gate))
              "Final status should be approved or rejected"))))))

;; ---------------------------------------------------------------------------
;; Test 2: Double approve — second is noop
;; ---------------------------------------------------------------------------

(deftest double-approve-second-noop-test
  (let [ds (conn/create-datasource test-db-path)
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-2" :stage-name "deploy"
                   :required-role "developer" :message "Approve?" :timeout-minutes 60})]

    (testing "first approve succeeds, second is noop"
      (let [first-count  (approval-store/approve-gate! ds gate-id "user-a")
            second-count (approval-store/approve-gate! ds gate-id "user-b")]
        (is (= 1 first-count))
        (is (= 0 second-count))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate)))
          (is (= "user-a" (:approved-by gate))))))))

;; ---------------------------------------------------------------------------
;; Test 3: Double reject — second is noop
;; ---------------------------------------------------------------------------

(deftest double-reject-second-noop-test
  (let [ds (conn/create-datasource test-db-path)
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-3" :stage-name "deploy"
                   :required-role "developer" :message "Approve?" :timeout-minutes 60})]

    (testing "first reject succeeds, second is noop"
      (let [first-count  (approval-store/reject-gate! ds gate-id "user-a")
            second-count (approval-store/reject-gate! ds gate-id "user-b")]
        (is (= 1 first-count))
        (is (= 0 second-count))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "rejected" (:status gate)))
          (is (= "user-a" (:rejected-by gate))))))))

;; ---------------------------------------------------------------------------
;; Test 4: Reject respects required-role via handler
;; ---------------------------------------------------------------------------

(deftest reject-respects-required-role-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {}}
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-4" :stage-name "deploy"
                   :required-role "admin" :message "Admin only" :timeout-minutes 60})
        reject-handler (handlers/reject-gate-handler system)]

    (testing "developer cannot reject admin-required gate"
      (let [resp (reject-handler {:path-params {:id gate-id}
                                  :auth/user {:username "dev" :role :developer}})]
        (is (= 403 (:status resp)))
        ;; Gate should still be pending
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "pending" (:status gate))))))

    (testing "admin can reject admin-required gate"
      (let [resp (reject-handler {:path-params {:id gate-id}
                                  :auth/user {:username "admin" :role :admin}})]
        (is (= 303 (:status resp)))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "rejected" (:status gate))))))))

;; ---------------------------------------------------------------------------
;; Test 5: Approve respects required-role via handler
;; ---------------------------------------------------------------------------

(deftest approve-respects-required-role-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {}}
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-5" :stage-name "deploy"
                   :required-role "admin" :message "Admin only" :timeout-minutes 60})
        approve-handler (handlers/approve-gate-handler system)]

    (testing "developer cannot approve admin-required gate"
      (let [resp (approve-handler {:path-params {:id gate-id}
                                   :auth/user {:username "dev" :role :developer}})]
        (is (= 403 (:status resp)))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "pending" (:status gate))))))

    (testing "admin can approve admin-required gate"
      (let [resp (approve-handler {:path-params {:id gate-id}
                                   :auth/user {:username "admin" :role :admin}})]
        (is (= 303 (:status resp)))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate))))))))

;; ---------------------------------------------------------------------------
;; Test 6: Many concurrent approves — exactly one wins
;; ---------------------------------------------------------------------------

(deftest many-concurrent-approves-one-wins-test
  (let [ds (conn/create-datasource test-db-path)
        gate-id (approval-store/create-gate! ds
                  {:build-id "build-6" :stage-name "deploy"
                   :required-role "developer" :message "Race!" :timeout-minutes 60})]

    (testing "10 concurrent approves — exactly one succeeds"
      (let [futures (mapv #(future (approval-store/approve-gate! ds gate-id (str "user-" %)))
                          (range 10))
            results (mapv deref futures)
            winners (count (filter #(= 1 %) results))]
        (is (= 1 winners) "Exactly one approve should succeed")
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate))))))))
