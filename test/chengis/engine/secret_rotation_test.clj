(ns chengis.engine.secret-rotation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.rotation-store :as rotation-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.engine.secret-rotation :as secret-rotation]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-secret-rotation-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-system
  "Build a test system map with secret-rotation enabled."
  [& {:keys [rotation-enabled] :or {rotation-enabled true}}]
  (let [ds (conn/create-datasource test-db-path)]
    {:db ds
     :config {:feature-flags {:secret-rotation rotation-enabled}
              :secret-rotation {:check-interval-hours 6
                                :default-interval-days 90}
              :secrets {:master-key "test-rotation-key-32chars!!!!"}}}))

;; ---------------------------------------------------------------------------
;; hash-value
;; ---------------------------------------------------------------------------

(deftest hash-value-test
  (testing "hash-value produces consistent SHA-256 hex string"
    (let [hash1 (secret-rotation/hash-value "hello")
          hash2 (secret-rotation/hash-value "hello")
          hash3 (secret-rotation/hash-value "world")]
      ;; Consistent
      (is (= hash1 hash2))
      ;; Different inputs produce different hashes
      (is (not= hash1 hash3))
      ;; SHA-256 hex = 64 chars
      (is (= 64 (count hash1)))
      ;; All hex characters
      (is (re-matches #"[0-9a-f]{64}" hash1))))

  (testing "hash-value returns nil for nil input"
    (is (nil? (secret-rotation/hash-value nil)))))

;; ---------------------------------------------------------------------------
;; rotate-secret!
;; ---------------------------------------------------------------------------

(deftest rotate-secret-test
  (let [system (test-system)
        ds (:db system)
        config (:config system)]
    (testing "rotate-secret! with existing secret"
      ;; Set up an existing secret
      (secret-store/set-secret! ds config "ROTATE_KEY" "old-value"
        :scope "global" :org-id "org-1")

      ;; Create a rotation policy
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1"
                      :secret-name "ROTATE_KEY"
                      :secret-scope "global"
                      :rotation-interval-days 30
                      :max-versions 5})
            result (secret-rotation/rotate-secret! system policy)]
        ;; Should succeed
        (is (= :success (:status result)))
        (is (= "ROTATE_KEY" (:secret-name result)))
        (is (= 1 (:version result)))

        ;; Secret value should be different from the original
        (let [new-value (secret-store/get-secret ds config "ROTATE_KEY"
                          :scope "global" :org-id "org-1")]
          (is (some? new-value))
          (is (not= "old-value" new-value)))

        ;; Version should be recorded
        (let [versions (rotation-store/list-versions ds "org-1" "ROTATE_KEY")]
          (is (= 1 (count versions)))
          (is (= 1 (:version (first versions))))
          (is (= "system/rotation-scheduler" (:rotated-by (first versions))))
          (is (some? (:previous-value-hash (first versions)))))))))

(deftest rotate-secret-no-existing-value-test
  (let [system (test-system)
        ds (:db system)]
    (testing "rotate-secret! with no existing secret"
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1"
                      :secret-name "NEW_KEY"
                      :secret-scope "global"
                      :rotation-interval-days 30
                      :max-versions 3})
            result (secret-rotation/rotate-secret! system policy)]
        ;; Should still succeed
        (is (= :success (:status result)))
        (is (= 1 (:version result)))

        ;; A new secret should now exist
        (let [config (:config system)
              value (secret-store/get-secret ds config "NEW_KEY"
                      :scope "global" :org-id "org-1")]
          (is (some? value)))

        ;; Version hash should be nil (no previous value)
        (let [versions (rotation-store/list-versions ds "org-1" "NEW_KEY")]
          (is (nil? (:previous-value-hash (first versions)))))))))

;; ---------------------------------------------------------------------------
;; check-and-rotate!
;; ---------------------------------------------------------------------------

(deftest check-and-rotate-processes-due-test
  (let [system (test-system)
        ds (:db system)
        config (:config system)]
    (testing "check-and-rotate! processes due policies"
      ;; Set up a secret and policy
      (secret-store/set-secret! ds config "DUE_SECRET" "value-1"
        :scope "global" :org-id "org-1")
      (let [policy (rotation-store/create-policy! ds
                     {:org-id "org-1"
                      :secret-name "DUE_SECRET"
                      :secret-scope "global"
                      :rotation-interval-days 1
                      :max-versions 3})]
        ;; Force next_rotation_at into the past
        (next.jdbc/execute-one! ds
          ["UPDATE secret_rotation_policies SET next_rotation_at = '2020-01-01T00:00:00' WHERE id = ?"
           (:id policy)])

        (let [result (secret-rotation/check-and-rotate! system)]
          (is (= :success (:status result)))
          (is (= 1 (:policies-checked result)))
          (is (= 1 (count (:results result))))
          (is (= :success (:status (first (:results result))))))))))

(deftest check-and-rotate-skips-non-due-test
  (let [system (test-system)
        ds (:db system)]
    (testing "check-and-rotate! skips non-due policies"
      ;; Create a policy with far-future rotation (9999 days)
      (rotation-store/create-policy! ds
        {:org-id "org-1"
         :secret-name "NOT_DUE_SECRET"
         :secret-scope "global"
         :rotation-interval-days 9999})

      (let [result (secret-rotation/check-and-rotate! system)]
        (is (= :success (:status result)))
        (is (= 0 (:policies-checked result)))
        (is (empty? (:results result)))))))

;; ---------------------------------------------------------------------------
;; Feature flag gating
;; ---------------------------------------------------------------------------

(deftest feature-flag-disabled-test
  (let [system (test-system :rotation-enabled false)]
    (testing "check-and-rotate! returns disabled status when feature flag is off"
      (let [result (secret-rotation/check-and-rotate! system)]
        (is (= :disabled (:status result)))))))

;; ---------------------------------------------------------------------------
;; Scheduler lifecycle
;; ---------------------------------------------------------------------------

(deftest scheduler-lifecycle-test
  (testing "scheduler starts and stops cleanly"
    ;; Initially not running
    (is (not (secret-rotation/running?*)))

    ;; Start it
    (let [system (test-system)]
      (secret-rotation/start-rotation-scheduler! system)
      (is (true? (secret-rotation/running?*)))

      ;; Stop it
      (secret-rotation/stop-rotation-scheduler!)
      (is (not (secret-rotation/running?*))))))
