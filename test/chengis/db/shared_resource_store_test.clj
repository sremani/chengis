(ns chengis.db.shared-resource-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.shared-resource-store :as shared-store]))

(def test-db-path "/tmp/chengis-shared-resource-test.db")

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
;; create-grant!
;; ---------------------------------------------------------------------------

(deftest create-grant-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create-grant! creates a grant and returns it"
      (let [grant (shared-store/create-grant! ds
                    {:source-org-id "org-a"
                     :target-org-id "org-b"
                     :resource-type "agent-label"
                     :resource-id "docker"
                     :granted-by "user-1"})]
        (is (some? (:id grant)))
        (is (= "org-a" (:source-org-id grant)))
        (is (= "org-b" (:target-org-id grant)))
        (is (= "agent-label" (:resource-type grant)))
        (is (= "docker" (:resource-id grant)))
        (is (= "user-1" (:granted-by grant)))))

    (testing "create-grant! is idempotent (duplicate = no error)"
      (let [grant1 (shared-store/create-grant! ds
                     {:source-org-id "org-a"
                      :target-org-id "org-b"
                      :resource-type "agent-label"
                      :resource-id "docker"
                      :granted-by "user-1"})
            ;; Same grant again — should not throw
            grant2 (shared-store/create-grant! ds
                     {:source-org-id "org-a"
                      :target-org-id "org-b"
                      :resource-type "agent-label"
                      :resource-id "docker"
                      :granted-by "user-2"})]
        ;; Both return a map (second has its own generated id but row was not inserted)
        (is (some? (:id grant1)))
        (is (some? (:id grant2)))))))

;; ---------------------------------------------------------------------------
;; list-grants-from / list-grants-to
;; ---------------------------------------------------------------------------

(deftest list-grants-from-test
  (let [ds (conn/create-datasource test-db-path)]
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "docker" :granted-by "u1"})
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-c"
       :resource-type "template" :resource-id "tmpl-1" :granted-by "u1"})
    (shared-store/create-grant! ds
      {:source-org-id "org-x" :target-org-id "org-a"
       :resource-type "agent-label" :resource-id "linux" :granted-by "u2"})

    (testing "list-grants-from returns grants from source org"
      (let [grants (shared-store/list-grants-from ds "org-a")]
        (is (= 2 (count grants)))
        (is (every? #(= "org-a" (:source-org-id %)) grants))))

    (testing "list-grants-from filters by resource-type"
      (let [grants (shared-store/list-grants-from ds "org-a" :resource-type "template")]
        (is (= 1 (count grants)))
        (is (= "template" (:resource-type (first grants))))))))

(deftest list-grants-to-test
  (let [ds (conn/create-datasource test-db-path)]
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "docker" :granted-by "u1"})
    (shared-store/create-grant! ds
      {:source-org-id "org-c" :target-org-id "org-b"
       :resource-type "template" :resource-id "tmpl-2" :granted-by "u2"})
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-x"
       :resource-type "agent-label" :resource-id "linux" :granted-by "u1"})

    (testing "list-grants-to returns grants to target org"
      (let [grants (shared-store/list-grants-to ds "org-b")]
        (is (= 2 (count grants)))
        (is (every? #(= "org-b" (:target-org-id %)) grants))))

    (testing "list-grants-to filters by resource-type"
      (let [grants (shared-store/list-grants-to ds "org-b" :resource-type "agent-label")]
        (is (= 1 (count grants)))
        (is (= "agent-label" (:resource-type (first grants))))))))

;; ---------------------------------------------------------------------------
;; list-shared-resource-ids
;; ---------------------------------------------------------------------------

(deftest list-shared-resource-ids-test
  (let [ds (conn/create-datasource test-db-path)]
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "docker" :granted-by "u1"})
    (shared-store/create-grant! ds
      {:source-org-id "org-c" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "linux" :granted-by "u2"})
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "template" :resource-id "tmpl-1" :granted-by "u1"})

    (testing "list-shared-resource-ids returns correct IDs for type"
      (let [ids (shared-store/list-shared-resource-ids ds "org-b" "agent-label")]
        (is (= 2 (count ids)))
        (is (contains? (set ids) "docker"))
        (is (contains? (set ids) "linux"))))

    (testing "list-shared-resource-ids returns only matching type"
      (let [ids (shared-store/list-shared-resource-ids ds "org-b" "template")]
        (is (= 1 (count ids)))
        (is (= "tmpl-1" (first ids)))))

    (testing "list-shared-resource-ids returns empty for unknown target"
      (let [ids (shared-store/list-shared-resource-ids ds "org-z" "agent-label")]
        (is (= 0 (count ids)))))))

;; ---------------------------------------------------------------------------
;; revoke-grant!
;; ---------------------------------------------------------------------------

(deftest revoke-grant-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "revoke-grant! removes the grant"
      (let [grant (shared-store/create-grant! ds
                    {:source-org-id "org-a" :target-org-id "org-b"
                     :resource-type "agent-label" :resource-id "docker"
                     :granted-by "u1"})]
        ;; Grant exists
        (is (some? (shared-store/get-grant ds (:id grant))))
        ;; Revoke it
        (is (true? (shared-store/revoke-grant! ds (:id grant))))
        ;; Grant gone
        (is (nil? (shared-store/get-grant ds (:id grant))))))

    (testing "revoke-grant! with source-org-id ownership check"
      (let [grant (shared-store/create-grant! ds
                    {:source-org-id "org-a" :target-org-id "org-c"
                     :resource-type "template" :resource-id "tmpl-1"
                     :granted-by "u1"})]
        ;; Wrong org cannot revoke
        (is (false? (shared-store/revoke-grant! ds (:id grant) :source-org-id "org-x")))
        ;; Grant still exists
        (is (some? (shared-store/get-grant ds (:id grant))))
        ;; Correct org can revoke
        (is (true? (shared-store/revoke-grant! ds (:id grant) :source-org-id "org-a")))
        (is (nil? (shared-store/get-grant ds (:id grant))))))))

;; ---------------------------------------------------------------------------
;; has-grant?
;; ---------------------------------------------------------------------------

(deftest has-grant-test
  (let [ds (conn/create-datasource test-db-path)]
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "docker"
       :granted-by "u1"})

    (testing "has-grant? returns true for existing grant"
      (is (true? (shared-store/has-grant? ds "org-a" "org-b" "agent-label" "docker"))))

    (testing "has-grant? returns false for non-existing grant"
      (is (false? (shared-store/has-grant? ds "org-a" "org-b" "agent-label" "linux")))
      (is (false? (shared-store/has-grant? ds "org-x" "org-b" "agent-label" "docker"))))))

;; ---------------------------------------------------------------------------
;; cleanup-expired-grants!
;; ---------------------------------------------------------------------------

(deftest cleanup-expired-grants-test
  (let [ds (conn/create-datasource test-db-path)]
    ;; Create a grant with past expiry (already expired)
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "old-label"
       :granted-by "u1"
       :expires-at "2020-01-01T00:00:00"})
    ;; Create a grant with no expiry (never expires)
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "agent-label" :resource-id "permanent"
       :granted-by "u1"})
    ;; Create a grant with future expiry
    (shared-store/create-grant! ds
      {:source-org-id "org-a" :target-org-id "org-b"
       :resource-type "template" :resource-id "tmpl-future"
       :granted-by "u1"
       :expires-at "2099-12-31T23:59:59"})

    (testing "cleanup-expired-grants! removes expired grants"
      (let [cnt (shared-store/cleanup-expired-grants! ds)]
        (is (= 1 cnt))))

    (testing "non-expired and permanent grants remain"
      (let [grants (shared-store/list-grants-from ds "org-a")]
        (is (= 2 (count grants)))
        (is (contains? (set (map :resource-id grants)) "permanent"))
        (is (contains? (set (map :resource-id grants)) "tmpl-future"))))))
