(ns chengis.engine.policy-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.policy-store :as policy-store]
            [chengis.engine.policy :as policy]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-policy-engine-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Branch restriction tests
;; ---------------------------------------------------------------------------

(deftest branch-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction allows matching branch"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow main only"
         :policy-type "branch-restriction"
         :rules {:branches ["main" "release/*"] :action "allow"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "main"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

(deftest branch-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction denies non-matching branch"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow main only"
         :policy-type "branch-restriction"
         :rules {:branches ["main"] :action "allow"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "feature/test"}
                     {:stage-name "build"})]
        (is (false? (:proceed result)))
        (is (clojure.string/includes? (:reason result) "does not match"))))))

(deftest branch-restriction-glob-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "branch restriction supports glob patterns"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow release branches"
         :policy-type "branch-restriction"
         :rules {:branches ["release/*"] :action "allow"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "release/1.0"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Author restriction tests
;; ---------------------------------------------------------------------------

(deftest author-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "author restriction denies matching author"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Deny bots"
         :policy-type "author-restriction"
         :rules {:authors ["bot-*"] :action "deny"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :author "bot-ci"}
                     {:stage-name "build"})]
        (is (false? (:proceed result)))))))

(deftest author-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "author restriction allows non-matching author"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Deny bots"
         :policy-type "author-restriction"
         :rules {:authors ["bot-*"] :action "deny"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :author "alice"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Time window tests
;; ---------------------------------------------------------------------------

(deftest time-window-allow-only-test
  (testing "time window with unrestricted hours passes"
    (let [ds (conn/create-datasource test-db-path)
          system {:db ds :config {:feature-flags {:policy-engine true}}}]
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow anytime"
         :policy-type "time-window"
         :rules {:timezone "UTC" :days [] :start_hour 0 :end_hour 24 :action "allow-only"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Parameter restriction tests
;; ---------------------------------------------------------------------------

(deftest parameter-restriction-deny-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "parameter restriction denies matching parameter"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "No force"
         :policy-type "parameter-restriction"
         :rules {:parameter "force" :operator "equals" :value "true" :action "deny"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :parameters {:force "true"}}
                     {:stage-name "deploy"})]
        (is (false? (:proceed result)))))))

(deftest parameter-restriction-allow-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "parameter restriction allows when parameter doesn't match"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "No force"
         :policy-type "parameter-restriction"
         :rules {:parameter "force" :operator "equals" :value "true" :action "deny"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :parameters {:force "false"}}
                     {:stage-name "deploy"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Required approval override tests
;; ---------------------------------------------------------------------------

(deftest required-approval-override-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "required-approval returns override for matching stage"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Deploy needs 2 approvals"
         :policy-type "required-approval"
         :rules {:stages ["deploy-*"] :min_approvals 2 :approver_group ["admin1" "admin2"]}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1"}
                     {:stage-name "deploy-prod"})]
        (is (true? (:proceed result)))
        (is (seq (:approval-overrides result)))))))

;; ---------------------------------------------------------------------------
;; Feature flag bypass
;; ---------------------------------------------------------------------------

(deftest feature-flag-disabled-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine false}}}]
    (testing "policies are skipped when feature flag is disabled"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Should be ignored"
         :policy-type "branch-restriction"
         :rules {:branches ["main"] :action "allow"}
         :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "develop"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Disabled policies skipped
;; ---------------------------------------------------------------------------

(deftest disabled-policy-skipped-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "disabled policies are skipped"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Disabled deny all"
         :policy-type "branch-restriction"
         :rules {:branches [] :action "allow"}
         :enabled false})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "anything"}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Priority ordering
;; ---------------------------------------------------------------------------

(deftest priority-ordering-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "higher priority (lower number) deny stops evaluation"
      ;; Priority 1: deny everything
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Deny all branches"
         :policy-type "branch-restriction"
         :rules {:branches ["forbidden-*"] :action "deny"}
         :priority 1 :enabled true})
      ;; Priority 100: allow (should not be reached for denied branches)
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow wide"
         :policy-type "branch-restriction"
         :rules {:branches ["*"] :action "allow"}
         :priority 100 :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "forbidden-test"}
                     {:stage-name "build"})]
        (is (false? (:proceed result)))))))

;; ---------------------------------------------------------------------------
;; Approval override application
;; ---------------------------------------------------------------------------

(deftest apply-approval-overrides-test
  (testing "apply-approval-overrides increases min-approvals"
    (let [stage {:stage-name "deploy" :approval {:min-approvals 1}}
          overrides [{:min-approvals 3 :approver-group ["admin1" "admin2"]}]
          result (policy/apply-approval-overrides stage overrides)]
      (is (= 3 (get-in result [:approval :min-approvals])))
      (is (= ["admin1" "admin2"] (get-in result [:approval :approver-group]))))))

(deftest apply-empty-overrides-test
  (testing "empty overrides return stage unchanged"
    (let [stage {:stage-name "build"}
          result (policy/apply-approval-overrides stage [])]
      (is (= stage result)))))
