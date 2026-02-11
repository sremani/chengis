(ns chengis.db.approval-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.approval-store :as approval-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-approval-store-test.db")

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

(deftest create-and-get-gate-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create a gate and retrieve it"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-123"
                       :stage-name "Deploy"
                       :required-role "admin"
                       :message "Approve deploy to production?"
                       :timeout-minutes 60})]
        (is (some? gate-id))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= gate-id (:id gate)))
          (is (= "build-123" (:build-id gate)))
          (is (= "Deploy" (:stage-name gate)))
          (is (= "pending" (:status gate)))
          (is (= "admin" (:required-role gate)))
          (is (= "Approve deploy to production?" (:message gate)))
          (is (= 60 (:timeout-minutes gate)))
          (is (some? (:created-at gate))))))

    (testing "create gate with defaults"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-456"
                       :stage-name "Test"})
            gate (approval-store/get-gate ds gate-id)]
        (is (= "developer" (:required-role gate)))
        (is (= 1440 (:timeout-minutes gate)))))))

(deftest get-gate-for-build-stage-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "find gate by build-id and stage-name"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-789"
                       :stage-name "Staging"
                       :required-role "developer"})
            gate (approval-store/get-gate-for-build-stage ds "build-789" "Staging")]
        (is (some? gate))
        (is (= gate-id (:id gate)))
        (is (= "Staging" (:stage-name gate)))))

    (testing "returns nil for non-existent gate"
      (is (nil? (approval-store/get-gate-for-build-stage ds "nope" "nope"))))))

(deftest list-pending-gates-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list all pending gates"
      ;; Create several gates
      (approval-store/create-gate! ds {:build-id "b1" :stage-name "S1"})
      (approval-store/create-gate! ds {:build-id "b2" :stage-name "S2"})
      (approval-store/create-gate! ds {:build-id "b3" :stage-name "S3"})
      (let [pending (approval-store/list-pending-gates ds)]
        (is (= 3 (count pending)))
        ;; All should be pending
        (is (every? #(= "pending" (:status %)) pending))))))

(deftest count-pending-gates-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "count pending gates"
      (is (= 0 (approval-store/count-pending-gates ds)))
      (approval-store/create-gate! ds {:build-id "b1" :stage-name "S1"})
      (approval-store/create-gate! ds {:build-id "b2" :stage-name "S2"})
      (is (= 2 (approval-store/count-pending-gates ds))))))

(deftest approve-gate-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "approve a pending gate"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-approve"
                       :stage-name "Deploy"
                       :required-role "admin"})
            result (approval-store/approve-gate! ds gate-id "alice")]
        (is (= 1 result))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate)))
          (is (= "alice" (:approved-by gate)))
          (is (some? (:approved-at gate))))))

    (testing "approve non-pending gate returns 0"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-double"
                       :stage-name "Test"})]
        (approval-store/approve-gate! ds gate-id "bob")
        ;; Try to approve again — should return 0
        (is (= 0 (approval-store/approve-gate! ds gate-id "charlie")))))))

(deftest reject-gate-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "reject a pending gate"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-reject"
                       :stage-name "Deploy"})
            result (approval-store/reject-gate! ds gate-id "eve")]
        (is (= 1 result))
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "rejected" (:status gate)))
          (is (= "eve" (:rejected-by gate)))
          (is (some? (:rejected-at gate))))))

    (testing "reject already-approved gate returns 0"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-rej2"
                       :stage-name "Test"})]
        (approval-store/approve-gate! ds gate-id "alice")
        (is (= 0 (approval-store/reject-gate! ds gate-id "bob")))))))

(deftest timeout-gate-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "timeout a pending gate"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "build-timeout"
                       :stage-name "Deploy"
                       :timeout-minutes 1})]
        (approval-store/timeout-gate! ds gate-id)
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "timed_out" (:status gate))))))))

(deftest list-gates-for-build-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list all gates for a specific build"
      (approval-store/create-gate! ds {:build-id "build-multi" :stage-name "Build"})
      (approval-store/create-gate! ds {:build-id "build-multi" :stage-name "Test"})
      (approval-store/create-gate! ds {:build-id "build-multi" :stage-name "Deploy"})
      (approval-store/create-gate! ds {:build-id "other-build" :stage-name "Build"})
      (let [gates (approval-store/list-gates-for-build ds "build-multi")]
        (is (= 3 (count gates)))
        (is (every? #(= "build-multi" (:build-id %)) gates))))))

(deftest cleanup-old-gates-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "cleanup does not remove recent gates"
      (approval-store/create-gate! ds {:build-id "b1" :stage-name "S1"})
      (approval-store/create-gate! ds {:build-id "b2" :stage-name "S2"})
      (let [cleaned (approval-store/cleanup-old-gates! ds 1)]
        (is (zero? cleaned))
        (is (= 2 (approval-store/count-pending-gates ds)))))))

(deftest unique-constraint-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "duplicate build-id + stage-name returns nil"
      (let [id1 (approval-store/create-gate! ds {:build-id "dup" :stage-name "S1"})
            id2 (approval-store/create-gate! ds {:build-id "dup" :stage-name "S1"})]
        (is (some? id1))
        ;; Second should fail due to UNIQUE constraint — returns nil
        (is (nil? id2))))))

(deftest approved-gate-not-in-pending-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "approved gates not in pending list"
      (let [gate-id (approval-store/create-gate! ds {:build-id "b1" :stage-name "S1"})]
        (is (= 1 (approval-store/count-pending-gates ds)))
        (approval-store/approve-gate! ds gate-id "admin")
        (is (= 0 (approval-store/count-pending-gates ds)))
        (is (empty? (approval-store/list-pending-gates ds)))))))

;; ---------------------------------------------------------------------------
;; Phase 2c: Boundary tests for approval threshold
;; Phase 2d: or-fallback defaults for required-role, timeout, min-approvals
;; ---------------------------------------------------------------------------

(deftest multi-approver-threshold-boundary-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "gate with min-approvals=2: one approval is not enough"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "b-multi" :stage-name "review"
                       :min-approvals 2
                       :approver-group ["alice" "bob" "carol"]})]
        ;; First approval — should not resolve gate
        (approval-store/approve-gate! ds gate-id "alice")
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "pending" (:status gate))
              "Gate should still be pending after 1 of 2 required approvals"))
        ;; Second approval — should resolve gate (>= 2 2)
        (approval-store/approve-gate! ds gate-id "bob")
        (let [gate (approval-store/get-gate ds gate-id)]
          (is (= "approved" (:status gate))
              "Gate should be approved after meeting min-approvals threshold"))))))

(deftest create-gate-or-fallback-defaults-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "required-role defaults to 'developer' when not specified"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "b-def" :stage-name "deploy"})
            gate (approval-store/get-gate ds gate-id)]
        (is (= "developer" (:required-role gate)))))

    (testing "min-approvals defaults to 1 when not specified"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "b-def2" :stage-name "deploy2"})
            gate (approval-store/get-gate ds gate-id)]
        (is (= 1 (:min-approvals gate)))))

    (testing "timeout-minutes defaults to 1440 when not specified"
      (let [gate-id (approval-store/create-gate! ds
                      {:build-id "b-def3" :stage-name "deploy3"})
            gate (approval-store/get-gate ds gate-id)]
        (is (= 1440 (:timeout-minutes gate)))))))
