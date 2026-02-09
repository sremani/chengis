(ns chengis.engine.executor-policy-test
  "Integration tests for policy engine + executor interaction.
   Tests policy check flow without running full builds."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.policy-store :as policy-store]
            [chengis.engine.policy :as policy]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-executor-policy-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest policy-deny-stops-stage-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "policy denial produces correct result for executor"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Block feature branches"
         :policy-type "branch-restriction"
         :rules {:branches ["main" "release/*"] :action "allow"}
         :priority 1 :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1" :branch "feature/experiment"}
                     {:stage-name "deploy-prod"})]
        (is (false? (:proceed result)))
        (is (some? (:reason result)))
        ;; Verify evaluation was logged
        (let [evals (policy-store/list-evaluations ds :build-id "b1")]
          (is (pos? (count evals))))))))

(deftest policy-approval-override-integration-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "policy approval override integrates with stage definition"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Deploy requires 2 approvals"
         :policy-type "required-approval"
         :rules {:stages ["deploy-*"] :min_approvals 2
                 :approver_group ["lead1" "lead2"]}
         :priority 1 :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1"}
                     {:stage-name "deploy-staging"})
            overrides (:approval-overrides result)
            stage {:stage-name "deploy-staging" :approval {:min-approvals 1}}
            effective (policy/apply-approval-overrides stage overrides)]
        (is (true? (:proceed result)))
        (is (= 1 (count overrides)))
        (is (= 2 (get-in effective [:approval :min-approvals])))
        (is (= ["lead1" "lead2"] (get-in effective [:approval :approver-group])))))))

(deftest passing-policies-proceed-test
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:policy-engine true}}}]
    (testing "all-passing policies allow proceed"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Allow main"
         :policy-type "branch-restriction"
         :rules {:branches ["main"] :action "allow"}
         :priority 1 :enabled true})
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "No force deploys"
         :policy-type "parameter-restriction"
         :rules {:parameter "force" :operator "equals" :value "true" :action "deny"}
         :priority 2 :enabled true})
      (let [result (policy/check-stage-policies! system
                     {:build-id "b1" :org-id "org-1"
                      :branch "main" :parameters {:force "false"}}
                     {:stage-name "build"})]
        (is (true? (:proceed result)))))))
