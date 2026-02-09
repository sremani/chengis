(ns chengis.db.multi-approver-test
  "Tests for multi-approver approval workflows."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.approval-store :as approval-store]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-multi-approver-test.db")

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
;; Multi-approver gate creation
;; ---------------------------------------------------------------------------

(deftest create-multi-approver-gate-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "create gate with approver group and min-approvals"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-multi-1"
                       :stage-name "Deploy"
                       :required-role "developer"
                       :message "Requires 2 of 3 approvers"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})]
        (is (some? gate-id))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "pending" (:status gate)))
          (is (= 2 (:min-approvals gate)))
          (is (some? (:approver-group gate))))))

    (testing "create gate with default min-approvals=1"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-single-1"
                       :stage-name "Test"})
            gate (approval-store/get-gate ds gate-id)]
        (is (= 1 (:min-approvals gate)))
        (is (nil? (:approver-group gate)))))))

;; ---------------------------------------------------------------------------
;; Multi-approver approval flow
;; ---------------------------------------------------------------------------

(deftest multi-approver-approval-flow-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "single approval not enough for min-approvals=2"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-flow-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})
            result (approval-store/approve-gate! ds gate-id "alice")]
        ;; Gate should still be pending (0 = not resolved)
        (is (= 0 result))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "pending" (:status gate))))))

    (testing "second approval meets threshold"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-flow-2"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})]
        (approval-store/approve-gate! ds gate-id "alice")
        (let [result (approval-store/approve-gate! ds gate-id "bob")]
          ;; Gate should now be approved (1 = resolved)
          (is (= 1 result))
          (let [gate (approval-store/get-gate ds gate-id)]
            (is (= "approved" (:status gate)))
            (is (= "bob" (:approved-by gate)))))))))

(deftest multi-approver-duplicate-response-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "same user cannot approve twice"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-dup-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob"]
                       :min-approvals 2})]
        ;; First approval succeeds
        (is (= 0 (approval-store/approve-gate! ds gate-id "alice")))
        ;; Second from same user returns 0 (duplicate, no new response)
        (is (= 0 (approval-store/approve-gate! ds gate-id "alice")))
        ;; Gate still pending (need bob)
        (is (= "pending" (:status (approval-store/get-gate ds gate-id))))
        ;; Verify only 1 response row exists (duplicate was truly prevented)
        (is (= 1 (count (approval-store/get-gate-responses ds gate-id))))))))

;; ---------------------------------------------------------------------------
;; Multi-approver rejection flow
;; ---------------------------------------------------------------------------

(deftest multi-approver-rejection-flow-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "single rejection with remaining hope doesn't reject gate"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-rej-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})]
        ;; alice rejects — bob and charlie can still reach 2
        (let [result (approval-store/reject-gate! ds gate-id "alice")]
          (is (= 0 result))
          (is (= "pending" (:status (approval-store/get-gate ds gate-id)))))))

    (testing "enough rejections to make approval impossible → reject gate"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-rej-2"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})]
        ;; alice rejects — 2 remaining, need 2 approvals, still possible
        (approval-store/reject-gate! ds gate-id "alice")
        ;; bob rejects — 1 remaining, need 2 approvals, impossible!
        (let [result (approval-store/reject-gate! ds gate-id "bob")]
          (is (= 1 result))
          (let [gate (approval-store/get-gate ds gate-id)]
            (is (= "rejected" (:status gate)))))))))

(deftest multi-approver-mixed-responses-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "mix of approvals and rejections"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-mix-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie" "dave"]
                       :min-approvals 2})]
        ;; alice approves (1/2)
        (approval-store/approve-gate! ds gate-id "alice")
        (is (= "pending" (:status (approval-store/get-gate ds gate-id))))

        ;; bob rejects — still possible (charlie and dave could approve)
        (approval-store/reject-gate! ds gate-id "bob")
        (is (= "pending" (:status (approval-store/get-gate ds gate-id))))

        ;; charlie approves (2/2) — threshold met!
        (let [result (approval-store/approve-gate! ds gate-id "charlie")]
          (is (= 1 result))
          (is (= "approved" (:status (approval-store/get-gate ds gate-id)))))))))

;; ---------------------------------------------------------------------------
;; Response tracking
;; ---------------------------------------------------------------------------

(deftest gate-responses-tracking-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "responses are tracked individually"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-track-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob" "charlie"]
                       :min-approvals 2})]
        (approval-store/approve-gate! ds gate-id "alice")
        (approval-store/reject-gate! ds gate-id "bob")

        (let [responses (approval-store/get-gate-responses ds gate-id)]
          (is (= 2 (count responses)))
          (is (some #(and (= "alice" (:user-id %)) (= "approved" (:decision %))) responses))
          (is (some #(and (= "bob" (:user-id %)) (= "rejected" (:decision %))) responses)))))

    (testing "count-approvals and count-rejections"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-count-1"
                       :stage-name "Deploy"
                       :approver-group ["a" "b" "c" "d"]
                       :min-approvals 3})]
        (approval-store/approve-gate! ds gate-id "a")
        (approval-store/approve-gate! ds gate-id "b")
        (approval-store/reject-gate! ds gate-id "c")

        (is (= 2 (approval-store/count-approvals ds gate-id)))
        (is (= 1 (approval-store/count-rejections ds gate-id)))))))

;; ---------------------------------------------------------------------------
;; can-user-respond?
;; ---------------------------------------------------------------------------

(deftest can-user-respond-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "user in approver group can respond"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-can-1"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob"]
                       :min-approvals 1})]
        (is (true? (approval-store/can-user-respond? ds gate-id "alice")))
        (is (true? (approval-store/can-user-respond? ds gate-id "bob")))
        (is (not (approval-store/can-user-respond? ds gate-id "eve")))))

    (testing "user cannot respond twice"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-can-2"
                       :stage-name "Deploy"
                       :approver-group ["alice" "bob"]
                       :min-approvals 2})]
        (is (true? (approval-store/can-user-respond? ds gate-id "alice")))
        (approval-store/approve-gate! ds gate-id "alice")
        (is (not (approval-store/can-user-respond? ds gate-id "alice")))))

    (testing "no approver group means anyone can respond"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-can-3"
                       :stage-name "Deploy"})]
        (is (true? (approval-store/can-user-respond? ds gate-id "anyone")))))))

;; ---------------------------------------------------------------------------
;; Legacy single-approver backward compatibility
;; ---------------------------------------------------------------------------

(deftest legacy-single-approver-compat-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "single-approver approve still works"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-legacy-1"
                       :stage-name "Deploy"
                       :required-role "admin"})
            result (approval-store/approve-gate! ds gate-id "admin-user")]
        (is (= 1 result))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate)))
          (is (= "admin-user" (:approved-by gate))))))

    (testing "single-approver reject still works"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-legacy-2"
                       :stage-name "Deploy"})
            result (approval-store/reject-gate! ds gate-id "admin-user")]
        (is (= 1 result))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "rejected" (:status gate))))))

    (testing "approving already-approved gate returns 0"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-legacy-3"
                       :stage-name "Deploy"})]
        (approval-store/approve-gate! ds gate-id "user-1")
        (is (= 0 (approval-store/approve-gate! ds gate-id "user-2")))))

    (testing "rejecting already-approved gate returns 0"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-legacy-4"
                       :stage-name "Deploy"})]
        (approval-store/approve-gate! ds gate-id "user-1")
        (is (= 0 (approval-store/reject-gate! ds gate-id "user-2")))))))

;; ---------------------------------------------------------------------------
;; Cleanup includes responses
;; ---------------------------------------------------------------------------

(deftest cleanup-with-responses-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "cleanup removes gates and their responses"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-clean-1"
                       :stage-name "Deploy"
                       :approver-group ["a" "b"]
                       :min-approvals 2})]
        (approval-store/approve-gate! ds gate-id "a")
        ;; Responses exist
        (is (= 1 (count (approval-store/get-gate-responses ds gate-id))))
        ;; Backdate the gate's created_at to make it eligible for cleanup
        (jdbc/execute-one! ds
          (sql/format {:update :approval-gates
                       :set {:created-at [:datetime "now" "-30 days"]}
                       :where [:= :id gate-id]}))
        ;; Cleanup with 7 day retention deletes gates older than 7 days
        (let [deleted (approval-store/cleanup-old-gates! ds 7)]
          (is (pos? deleted))
          ;; Gate and responses gone
          (is (nil? (approval-store/get-gate ds gate-id)))
          (is (empty? (approval-store/get-gate-responses ds gate-id))))))))
