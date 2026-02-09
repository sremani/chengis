(ns chengis.db.policy-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.policy-store :as policy-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-policy-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest create-and-get-policy-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and retrieve policy"
      (let [p (policy-store/create-policy! ds
                {:org-id "org-1"
                 :name "Branch Restriction"
                 :description "Only allow main"
                 :policy-type "branch-restriction"
                 :rules {:branches ["main"] :action "allow"}
                 :priority 10
                 :enabled true
                 :created-by "admin"})]
        (is (some? (:id p)))
        (is (= "Branch Restriction" (:name p)))
        (let [retrieved (policy-store/get-policy ds (:id p))]
          (is (= "branch-restriction" (:policy-type retrieved)))
          (is (= {:branches ["main"] :action "allow"} (:rules retrieved))))))))

(deftest list-policies-priority-order-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "policies are ordered by priority ASC"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Low Priority" :policy-type "time-window"
         :rules {} :priority 100})
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "High Priority" :policy-type "branch-restriction"
         :rules {} :priority 1})
      (let [policies (policy-store/list-policies ds :org-id "org-1")]
        (is (= 2 (count policies)))
        (is (= "High Priority" (:name (first policies))))
        (is (= "Low Priority" (:name (second policies))))))))

(deftest org-isolation-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "policies are scoped by org"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Org1 Policy" :policy-type "branch-restriction" :rules {}})
      (policy-store/create-policy! ds
        {:org-id "org-2" :name "Org2 Policy" :policy-type "time-window" :rules {}})
      (is (= 1 (count (policy-store/list-policies ds :org-id "org-1"))))
      (is (= 1 (count (policy-store/list-policies ds :org-id "org-2"))))
      (is (= 2 (count (policy-store/list-policies ds)))))))

(deftest unique-name-constraint-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "duplicate name in same org throws"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Unique Policy" :policy-type "branch-restriction" :rules {}})
      (is (thrown? Exception
            (policy-store/create-policy! ds
              {:org-id "org-1" :name "Unique Policy" :policy-type "time-window" :rules {}}))))))

(deftest toggle-policy-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "toggle policy enabled/disabled"
      (let [p (policy-store/create-policy! ds
                {:org-id "org-1" :name "Toggle Me" :policy-type "branch-restriction"
                 :rules {} :enabled true})]
        (policy-store/toggle-policy! ds (:id p) false)
        (let [disabled (policy-store/get-policy ds (:id p))]
          (is (not (:enabled disabled))))
        (policy-store/toggle-policy! ds (:id p) true)
        (let [enabled (policy-store/get-policy ds (:id p))]
          (is (:enabled enabled)))))))

(deftest enabled-only-filter-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "enabled-only filter works"
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Active" :policy-type "branch-restriction"
         :rules {} :enabled true})
      (policy-store/create-policy! ds
        {:org-id "org-1" :name "Inactive" :policy-type "time-window"
         :rules {} :enabled false})
      (is (= 2 (count (policy-store/list-policies ds :org-id "org-1"))))
      (is (= 1 (count (policy-store/list-policies ds :org-id "org-1" :enabled-only true)))))))

(deftest evaluation-logging-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "log and list policy evaluations"
      (let [p (policy-store/create-policy! ds
                {:org-id "org-1" :name "Test Policy" :policy-type "branch-restriction" :rules {}})
            eval-id (policy-store/log-evaluation! ds
                      {:policy-id (:id p)
                       :build-id "build-123"
                       :stage-name "deploy"
                       :result :allow
                       :reason "Branch check passed"
                       :context {:branch "main"}})]
        (is (some? eval-id))
        (let [evals (policy-store/list-evaluations ds :build-id "build-123")]
          (is (= 1 (count evals)))
          (is (= "allow" (:result (first evals)))))
        (let [evals (policy-store/list-evaluations ds :policy-id (:id p))]
          (is (= 1 (count evals))))))))
