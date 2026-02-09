(ns chengis.db.docker-policy-store-test
  "Tests for Docker image policy store: create, list, check-image-allowed,
   priority ordering, org isolation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.docker-policy-store :as store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-docker-policy-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest create-and-list-docker-policies-test
  (testing "create and list Docker policies"
    (let [ds (conn/create-datasource test-db-path)]
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "allowed-registry"
         :pattern "docker.io/*" :action "allow" :priority 10})
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "denied-image"
         :pattern "evil/malware:*" :action "deny" :priority 5})
      (let [policies (store/list-docker-policies ds :org-id "org-1")]
        (is (= 2 (count policies))
            "Should have 2 policies")
        ;; Should be ordered by priority ascending
        (is (= 5 (:priority (first policies)))
            "First policy should be priority 5 (deny)")
        (is (= 10 (:priority (second policies)))
            "Second policy should be priority 10 (allow)")))))

(deftest check-image-allowed-allows-matching-registry-test
  (testing "check-image-allowed allows images matching allowed-registry"
    (let [ds (conn/create-datasource test-db-path)]
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "allowed-registry"
         :pattern "docker.io/*" :action "allow" :priority 100})
      (let [result (store/check-image-allowed ds "docker.io/ubuntu:latest" :org-id "org-1")]
        (is (true? (:allowed result))
            "docker.io image should be allowed")
        (is (some? (:policy-id result))
            "Should reference the matching policy")))))

(deftest check-image-allowed-denies-blocked-image-test
  (testing "check-image-allowed denies images matching denied-image"
    (let [ds (conn/create-datasource test-db-path)]
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "denied-image"
         :pattern "evil/malware:*" :action "deny" :priority 50})
      (let [result (store/check-image-allowed ds "evil/malware:latest" :org-id "org-1")]
        (is (false? (:allowed result))
            "Denied image should be blocked")
        (is (some? (:reason result))
            "Should provide a reason")))))

(deftest priority-ordering-deny-overrides-allow-test
  (testing "deny policy with lower priority number overrides allow"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Allow all docker.io images at priority 100
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "allowed-registry"
         :pattern "docker.io/*" :action "allow" :priority 100})
      ;; But deny a specific image at priority 10 (higher precedence)
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "denied-image"
         :pattern "docker.io/banned-image:*" :action "deny" :priority 10})
      ;; Regular docker.io image should still be allowed
      ;; (deny doesn't match, so it falls through to the allow)
      (let [result (store/check-image-allowed ds "docker.io/ubuntu:latest" :org-id "org-1")]
        (is (true? (:allowed result))
            "Non-banned docker.io image should be allowed"))
      ;; But banned image should be denied (deny matches first at priority 10)
      (let [result (store/check-image-allowed ds "docker.io/banned-image:v1" :org-id "org-1")]
        (is (false? (:allowed result))
            "Banned image should be denied despite registry allow")))))

(deftest org-scoped-isolation-test
  (testing "Docker policies are scoped to org"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Deny evil image for org-1
      (store/create-docker-policy! ds
        {:org-id "org-1" :policy-type "denied-image"
         :pattern "evil/*" :action "deny" :priority 10})
      ;; org-1: evil image should be denied
      (is (false? (:allowed (store/check-image-allowed ds "evil/thing:v1" :org-id "org-1")))
          "Evil image should be denied for org-1")
      ;; org-2: no policies, so all images allowed by default
      (is (true? (:allowed (store/check-image-allowed ds "evil/thing:v1" :org-id "org-2")))
          "Evil image should be allowed for org-2 (no policies)"))))

(deftest no-policies-allows-all-test
  (testing "when no policies exist, all images are allowed"
    (let [ds (conn/create-datasource test-db-path)]
      (let [result (store/check-image-allowed ds "anything/goes:latest" :org-id "org-1")]
        (is (true? (:allowed result))
            "Should allow all images when no policies exist")))))

(deftest delete-docker-policy-test
  (testing "delete a Docker policy"
    (let [ds (conn/create-datasource test-db-path)]
      (let [policy (store/create-docker-policy! ds
                     {:org-id "org-1" :policy-type "denied-image"
                      :pattern "delete-me:*" :action "deny" :priority 50})]
        (is (= 1 (count (store/list-docker-policies ds :org-id "org-1")))
            "Should have 1 policy before delete")
        (store/delete-docker-policy! ds (:id policy) :org-id "org-1")
        (is (= 0 (count (store/list-docker-policies ds :org-id "org-1")))
            "Should have 0 policies after delete")))))
