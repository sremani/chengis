(ns chengis.security-review-regression-test
  "Regression tests for the security review findings (P1/P2 fixes).
   Covers: auth bypass, cross-tenant leaks, cross-org deletion,
   audit hash-chain portability, and hash content integrity."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.policy-store :as policy-store]
            [chengis.db.audit-store :as audit-store]
            [chengis.engine.compliance :as compliance]
            [chengis.web.auth :as auth]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-security-regression-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Issue 1: /api/builds/:id/events/replay auth bypass
;; ---------------------------------------------------------------------------

(deftest replay-endpoint-not-exempt-from-auth
  (testing "distributed-api-path? does NOT exempt /events/replay"
    ;; The private fn is not directly testable, so we test the auth middleware behavior
    ;; by checking what paths the distributed exemption covers
    (is (not (#'auth/distributed-api-path? "/api/builds/123/events"))
        "/events endpoint should NOT bypass auth")
    (is (not (#'auth/distributed-api-path? "/api/builds/123/events/replay"))
        "/events/replay endpoint should NOT bypass auth")
    (is (not (#'auth/distributed-api-path? "/api/agents/register"))
        "/agents/register should NOT bypass auth (uses RBAC)")))

(deftest agent-write-endpoints-still-exempt
  (testing "Agent write endpoints still bypass global auth (use handler-level check-auth)"
    (is (#'auth/distributed-api-path? "/api/builds/123/agent-events")
        "/agent-events should bypass global auth")
    (is (#'auth/distributed-api-path? "/api/builds/123/result")
        "/result should bypass global auth")
    (is (#'auth/distributed-api-path? "/api/builds/123/artifacts")
        "/artifacts should bypass global auth")
    (is (#'auth/distributed-api-path? "/api/agents/abc/heartbeat")
        "/heartbeat should bypass global auth")))

(deftest startup-path-is-public
  (testing "/startup is a public path (needed for K8s startup probes)"
    (is (#'auth/public-path? "/startup" {})
        "/startup should be public for K8s probes")))

;; ---------------------------------------------------------------------------
;; Issue 2: Policy evaluations not org-scoped
;; ---------------------------------------------------------------------------

(deftest policy-evaluations-org-scoped
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-evaluations with org-id filters by org"
      ;; Create policies in two different orgs
      (let [p1 (policy-store/create-policy! ds
                 {:org-id "org-A" :name "Policy A"
                  :policy-type "branch-restriction" :rules {}})
            p2 (policy-store/create-policy! ds
                 {:org-id "org-B" :name "Policy B"
                  :policy-type "branch-restriction" :rules {}})]
        ;; Log evaluations for both
        (policy-store/log-evaluation! ds
          {:policy-id (:id p1) :build-id "build-1" :stage-name "deploy"
           :result :allow :reason "OK"})
        (policy-store/log-evaluation! ds
          {:policy-id (:id p2) :build-id "build-2" :stage-name "deploy"
           :result :deny :reason "Blocked"})

        ;; Without org-id: sees all (backward compat)
        (is (= 2 (count (policy-store/list-evaluations ds))))

        ;; With org-A: sees only org-A's evaluations
        (let [org-a-evals (policy-store/list-evaluations ds :org-id "org-A")]
          (is (= 1 (count org-a-evals)))
          (is (= "build-1" (:build-id (first org-a-evals)))))

        ;; With org-B: sees only org-B's evaluations
        (let [org-b-evals (policy-store/list-evaluations ds :org-id "org-B")]
          (is (= 1 (count org-b-evals)))
          (is (= "build-2" (:build-id (first org-b-evals)))))

        ;; With non-existent org: sees nothing
        (is (= 0 (count (policy-store/list-evaluations ds :org-id "org-Z"))))))))

;; ---------------------------------------------------------------------------
;; Issue 3: Cross-org policy delete
;; ---------------------------------------------------------------------------

(deftest cross-org-policy-delete-blocked
  (let [ds (conn/create-datasource test-db-path)]
    (testing "cannot delete another org's evaluation logs via policy delete"
      ;; Create policy in org-A with evaluations
      (let [p (policy-store/create-policy! ds
                {:org-id "org-A" :name "Victim Policy"
                 :policy-type "branch-restriction" :rules {}})
            _ (policy-store/log-evaluation! ds
                {:policy-id (:id p) :build-id "build-1" :stage-name "test"
                 :result :allow :reason "OK"})]

        ;; Attempt to delete from org-B (wrong org) — should throw
        (is (thrown? clojure.lang.ExceptionInfo
              (policy-store/delete-policy! ds (:id p) :org-id "org-B"))
            "Deleting a policy owned by another org should throw")

        ;; Verify evaluations are NOT deleted
        (let [evals (policy-store/list-evaluations ds :policy-id (:id p))]
          (is (= 1 (count evals))
              "Evaluations should be intact after failed cross-org delete"))

        ;; Verify policy still exists
        (is (some? (policy-store/get-policy ds (:id p) :org-id "org-A"))
            "Policy should still exist after failed cross-org delete")

        ;; Correct org can delete
        (policy-store/delete-policy! ds (:id p) :org-id "org-A")
        (is (nil? (policy-store/get-policy ds (:id p)))
            "Policy should be gone after correct org delete")
        (is (= 0 (count (policy-store/list-evaluations ds :policy-id (:id p))))
            "Evaluations should be gone after correct org delete")))))

;; ---------------------------------------------------------------------------
;; Issue 4: Audit hash-chain seq_num ordering (no rowid dependency)
;; ---------------------------------------------------------------------------

(deftest audit-chain-uses-seq-num-ordering
  (let [ds (conn/create-datasource test-db-path)]
    (testing "audit entries maintain correct chain order via seq_num"
      ;; Insert 5 entries rapidly (same-second timestamps possible)
      (dotimes [i 5]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action (str "action-" i) :resource-type "test"
           :resource-id (str "r" i)}))
      ;; Read back in insertion order
      (let [rows (jdbc/execute! ds
                   ["SELECT seq_num, entry_hash, prev_hash FROM audit_logs ORDER BY seq_num ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})]
        (is (= 5 (count rows)))
        ;; seq_num should be monotonically increasing
        (is (apply < (map :seq-num rows))
            "seq_num should be strictly increasing")
        ;; Chain should link correctly
        (is (nil? (:prev-hash (first rows)))
            "Genesis entry should have nil prev_hash")
        (doseq [i (range 1 5)]
          (is (= (:entry-hash (nth rows (dec i)))
                 (:prev-hash (nth rows i)))
              (str "Entry " i " prev_hash should match entry " (dec i) " entry_hash")))))))

;; ---------------------------------------------------------------------------
;; Issue 5: Hash-chain verification with content integrity
;; ---------------------------------------------------------------------------

(deftest hash-chain-detects-content-tampering
  (let [ds (conn/create-datasource test-db-path)]
    (testing "verify-hash-chain detects tampered entry content"
      ;; Insert 3 clean entries
      (dotimes [i 3]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action "login" :resource-type "session"
           :resource-id (str "s" i)}))

      ;; Verify clean chain passes
      (let [clean-result (compliance/verify-hash-chain ds {})]
        (is (true? (:valid clean-result))
            "Clean chain should be valid")
        (is (= 3 (:entries-checked clean-result))))

      ;; Tamper with the second entry's action (content change)
      (let [rows (jdbc/execute! ds
                   ["SELECT id FROM audit_logs ORDER BY seq_num ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})
            tamper-id (:id (second rows))]
        (jdbc/execute-one! ds
          ["UPDATE audit_logs SET action = 'TAMPERED' WHERE id = ?" tamper-id])

        ;; Verify chain now detects the tampering
        (let [tampered-result (compliance/verify-hash-chain ds {})]
          (is (false? (:valid tampered-result))
              "Tampered chain should be invalid")
          (is (= tamper-id (:first-invalid-id tampered-result))
              "Should identify the tampered entry")
          (is (= "entry_hash mismatch (content tampered)" (:reason tampered-result))
              "Should report content tampering"))))))

(deftest hash-chain-detects-prev-hash-tampering
  (let [ds (conn/create-datasource test-db-path)]
    (testing "verify-hash-chain detects broken prev_hash linkage"
      ;; Insert 3 clean entries
      (dotimes [i 3]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action "login" :resource-type "session"
           :resource-id (str "s" i)}))

      ;; Tamper with the third entry's prev_hash
      (let [rows (jdbc/execute! ds
                   ["SELECT id FROM audit_logs ORDER BY seq_num ASC"]
                   {:builder-fn rs/as-unqualified-kebab-maps})
            tamper-id (:id (nth rows 2))]
        (jdbc/execute-one! ds
          ["UPDATE audit_logs SET prev_hash = 'broken' WHERE id = ?" tamper-id])

        ;; Verify chain detects the broken link
        (let [result (compliance/verify-hash-chain ds {})]
          (is (false? (:valid result))
              "Chain with broken prev_hash should be invalid")
          (is (= tamper-id (:first-invalid-id result))
              "Should identify the entry with broken prev_hash")
          (is (= "prev_hash mismatch" (:reason result))))))))

(deftest hash-chain-valid-for-org-scoped-entries
  (let [ds (conn/create-datasource test-db-path)]
    (testing "verify-hash-chain works correctly with org-scoped entries"
      ;; Insert entries with org-id
      (dotimes [i 3]
        (audit-store/insert-audit! ds
          {:user-id (str "u" i) :username (str "user" i)
           :action "login" :resource-type "session"
           :resource-id (str "s" i) :org-id "org-1"}))
      ;; Verify passes
      (let [result (compliance/verify-hash-chain ds {})]
        (is (true? (:valid result)))
        (is (= 3 (:entries-checked result)))))))
