(ns chengis.db.iac-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.iac-store :as iac-store]))

(def test-db-path "/tmp/chengis-iac-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- test-ds [] (conn/create-datasource test-db-path))

;; ---------------------------------------------------------------------------
;; Projects
;; ---------------------------------------------------------------------------

(deftest create-project-test
  (let [ds (test-ds)]
    (testing "create-project! returns project with correct fields"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "infra-deploy" :tool-type "terraform"
                 :working-dir "./infra" :auto-detect true})]
        (is (some? (:id p)))
        (is (= "org-1" (:org-id p)))
        (is (= "infra-deploy" (:job-id p)))
        (is (= "terraform" (:tool-type p)))
        (is (= "./infra" (:working-dir p)))
        (is (= true (:auto-detect p)))))

    (testing "create-project! normalizes auto-detect boolean"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "no-detect" :tool-type "pulumi"
                 :auto-detect false})]
        (is (= false (:auto-detect p)))))

    (testing "create-project! stores and retrieves config JSON"
      (let [config {:backend "s3" :region "us-east-1"}
            p (iac-store/create-project! ds
                {:org-id "org-2" :job-id "cfg-project" :tool-type "terraform"
                 :config config})]
        (is (= config (:config p)))))))

(deftest get-project-test
  (let [ds (test-ds)]
    (testing "get-project retrieves created project"
      (let [created (iac-store/create-project! ds
                      {:org-id "org-1" :job-id "get-test" :tool-type "terraform"})
            retrieved (iac-store/get-project ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))
        (is (= "get-test" (:job-id retrieved)))))

    (testing "get-project with org-id scoping"
      (let [created (iac-store/create-project! ds
                      {:org-id "org-2" :job-id "org-scoped" :tool-type "pulumi"})]
        (is (some? (iac-store/get-project ds (:id created) :org-id "org-2")))
        (is (nil? (iac-store/get-project ds (:id created) :org-id "other-org")))))

    (testing "get-project returns nil for non-existent"
      (is (nil? (iac-store/get-project ds "non-existent-id"))))))

(deftest get-project-by-job-test
  (let [ds (test-ds)]
    (testing "get-project-by-job finds by org and job ID"
      (iac-store/create-project! ds
        {:org-id "org-1" :job-id "my-job" :tool-type "terraform"})
      (let [found (iac-store/get-project-by-job ds "org-1" "my-job")]
        (is (some? found))
        (is (= "my-job" (:job-id found)))))

    (testing "get-project-by-job returns nil for wrong org"
      (is (nil? (iac-store/get-project-by-job ds "other-org" "my-job"))))

    (testing "get-project-by-job returns nil for wrong job"
      (is (nil? (iac-store/get-project-by-job ds "org-1" "nonexistent-job"))))))

(deftest list-projects-test
  (let [ds (test-ds)]
    (testing "list-projects returns all projects"
      (iac-store/create-project! ds {:org-id "org-1" :job-id "j1" :tool-type "terraform"})
      (iac-store/create-project! ds {:org-id "org-1" :job-id "j2" :tool-type "pulumi"})
      (iac-store/create-project! ds {:org-id "org-2" :job-id "j3" :tool-type "terraform"})
      (is (= 3 (count (iac-store/list-projects ds)))))

    (testing "list-projects filters by org-id"
      (is (= 2 (count (iac-store/list-projects ds :org-id "org-1"))))
      (is (= 1 (count (iac-store/list-projects ds :org-id "org-2")))))

    (testing "list-projects filters by tool-type"
      (is (= 2 (count (iac-store/list-projects ds :tool-type "terraform"))))
      (is (= 1 (count (iac-store/list-projects ds :tool-type "pulumi")))))))

(deftest update-project-test
  (let [ds (test-ds)]
    (testing "update-project! updates fields"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "update-test" :tool-type "terraform"
                 :working-dir "."})
            cnt (iac-store/update-project! ds (:id p) {:working-dir "./new-dir"})]
        (is (= 1 cnt))
        (let [updated (iac-store/get-project ds (:id p))]
          (is (= "./new-dir" (:working-dir updated))))))

    (testing "update-project! updates config JSON round-trip"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "cfg-update" :tool-type "terraform"})
            config {:backend "gcs" :bucket "my-bucket"}
            _ (iac-store/update-project! ds (:id p) {:config config})
            updated (iac-store/get-project ds (:id p))]
        (is (= config (:config updated)))))

    (testing "update-project! with org-id scoping"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "scoped-update" :tool-type "terraform"})]
        (is (= 0 (iac-store/update-project! ds (:id p) {:working-dir "/x"} :org-id "wrong-org")))
        (is (= 1 (iac-store/update-project! ds (:id p) {:working-dir "/x"} :org-id "org-1")))))))

(deftest delete-project-test
  (let [ds (test-ds)]
    (testing "delete-project! removes project"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "delete-test" :tool-type "terraform"})]
        (is (true? (iac-store/delete-project! ds (:id p))))
        (is (nil? (iac-store/get-project ds (:id p))))))

    (testing "delete-project! returns false for non-existent"
      (is (false? (iac-store/delete-project! ds "non-existent-id"))))

    (testing "delete-project! with org-id scoping"
      (let [p (iac-store/create-project! ds
                {:org-id "org-1" :job-id "scoped-delete" :tool-type "terraform"})]
        (is (false? (iac-store/delete-project! ds (:id p) :org-id "wrong-org")))
        (is (true? (iac-store/delete-project! ds (:id p) :org-id "org-1")))))))

(deftest project-org-isolation-test
  (let [ds (test-ds)]
    (testing "projects are isolated by org"
      (iac-store/create-project! ds {:org-id "org-a" :job-id "shared-name" :tool-type "terraform"})
      (iac-store/create-project! ds {:org-id "org-b" :job-id "shared-name" :tool-type "terraform"})
      (is (= 1 (count (iac-store/list-projects ds :org-id "org-a"))))
      (is (= 1 (count (iac-store/list-projects ds :org-id "org-b")))))))

(deftest project-unique-constraint-test
  (let [ds (test-ds)]
    (testing "duplicate org+job fails on second create"
      (iac-store/create-project! ds {:org-id "org-1" :job-id "unique-job" :tool-type "terraform"})
      (is (thrown? Exception
            (iac-store/create-project! ds {:org-id "org-1" :job-id "unique-job" :tool-type "pulumi"}))))))

;; ---------------------------------------------------------------------------
;; Plans
;; ---------------------------------------------------------------------------

(defn- create-test-project! [ds job-id]
  (iac-store/create-project! ds
    {:org-id "org-1" :job-id job-id :tool-type "terraform"}))

(deftest create-plan-test
  (let [ds (test-ds)
        proj (create-test-project! ds "plan-proj")]
    (testing "create-plan! returns plan with correct fields"
      (let [plan (iac-store/create-plan! ds
                   {:org-id "org-1" :project-id (:id proj)
                    :build-id "build-1" :action "plan" :status "pending"
                    :resources-add 3 :resources-change 1 :resources-destroy 0
                    :initiated-by "user-1"})]
        (is (some? (:id plan)))
        (is (= "org-1" (:org-id plan)))
        (is (= (:id proj) (:project-id plan)))
        (is (= "build-1" (:build-id plan)))
        (is (= "plan" (:action plan)))
        (is (= "pending" (:status plan)))
        (is (= 3 (:resources-add plan)))
        (is (= 1 (:resources-change plan)))
        (is (= 0 (:resources-destroy plan)))
        (is (= "user-1" (:initiated-by plan)))))))

(deftest get-plan-test
  (let [ds (test-ds)
        proj (create-test-project! ds "get-plan-proj")]
    (testing "get-plan retrieves created plan"
      (let [created (iac-store/create-plan! ds
                      {:org-id "org-1" :project-id (:id proj) :action "plan"})
            retrieved (iac-store/get-plan ds (:id created))]
        (is (some? retrieved))
        (is (= (:id created) (:id retrieved)))))

    (testing "get-plan with org-id scoping"
      (let [created (iac-store/create-plan! ds
                      {:org-id "org-2" :project-id (:id proj) :action "plan"})]
        (is (some? (iac-store/get-plan ds (:id created) :org-id "org-2")))
        (is (nil? (iac-store/get-plan ds (:id created) :org-id "other-org")))))))

(deftest list-plans-test
  (let [ds (test-ds)
        proj (create-test-project! ds "list-plan-proj")]
    (testing "list-plans returns all plans"
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj) :action "plan"})
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj) :action "apply"})
      (is (<= 2 (count (iac-store/list-plans ds)))))

    (testing "list-plans filters by project-id"
      (let [plans (iac-store/list-plans ds :project-id (:id proj))]
        (is (every? #(= (:id proj) (:project-id %)) plans))))

    (testing "list-plans filters by status"
      (is (seq (iac-store/list-plans ds :status "pending"))))

    (testing "list-plans filters by build-id"
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj)
                                   :build-id "specific-build" :action "plan"})
      (let [plans (iac-store/list-plans ds :build-id "specific-build")]
        (is (= 1 (count plans)))
        (is (= "specific-build" (:build-id (first plans))))))))

(deftest update-plan-test
  (let [ds (test-ds)
        proj (create-test-project! ds "upd-plan-proj")]
    (testing "update-plan! updates status and output"
      (let [plan (iac-store/create-plan! ds
                   {:org-id "org-1" :project-id (:id proj) :action "plan"})
            cnt (iac-store/update-plan! ds (:id plan)
                  {:status "succeeded" :output "Plan completed"})]
        (is (= 1 cnt))
        (let [updated (iac-store/get-plan ds (:id plan))]
          (is (= "succeeded" (:status updated)))
          (is (= "Plan completed" (:output updated))))))))

(deftest approve-plan-test
  (let [ds (test-ds)
        proj (create-test-project! ds "approve-proj")]
    (testing "approve-plan! sets approved_by and status"
      (let [plan (iac-store/create-plan! ds
                   {:org-id "org-1" :project-id (:id proj) :action "plan" :status "pending"})]
        (is (true? (iac-store/approve-plan! ds (:id plan) "admin-user")))
        (let [approved (iac-store/get-plan ds (:id plan))]
          (is (= "approved" (:status approved)))
          (is (= "admin-user" (:approved-by approved)))
          (is (some? (:approved-at approved))))))

    (testing "approve-plan! fails for non-pending plan"
      (let [plan (iac-store/create-plan! ds
                   {:org-id "org-1" :project-id (:id proj) :action "plan" :status "pending"})]
        (iac-store/approve-plan! ds (:id plan) "admin")
        ;; Already approved, second attempt should fail
        (is (false? (iac-store/approve-plan! ds (:id plan) "admin2")))))))

(deftest get-latest-plan-test
  (let [ds (test-ds)
        proj (create-test-project! ds "latest-plan-proj")]
    (testing "get-latest-plan returns most recent plan"
      (iac-store/create-plan! ds
        {:org-id "org-1" :project-id (:id proj) :action "plan"})
      (Thread/sleep 1100) ;; Sleep > 1s for SQLite CURRENT_TIMESTAMP second precision
      (let [latest (iac-store/create-plan! ds
                     {:org-id "org-1" :project-id (:id proj) :action "apply"})
            found (iac-store/get-latest-plan ds (:id proj))]
        (is (some? found))
        (is (= (:id latest) (:id found)))
        (is (= "apply" (:action found)))))

    (testing "get-latest-plan returns nil for empty project"
      (let [proj2 (create-test-project! ds "empty-proj")]
        (is (nil? (iac-store/get-latest-plan ds (:id proj2))))))))

(deftest count-plans-by-status-test
  (let [ds (test-ds)
        proj (create-test-project! ds "count-proj")]
    (testing "count-plans-by-status groups correctly"
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj) :action "plan" :status "pending"})
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj) :action "plan" :status "pending"})
      (iac-store/create-plan! ds {:org-id "org-1" :project-id (:id proj) :action "apply" :status "succeeded"})
      (let [counts (iac-store/count-plans-by-status ds "org-1")]
        (is (= 2 (get counts "pending")))
        (is (= 1 (get counts "succeeded")))))))

(deftest plan-json-round-trip-test
  (let [ds (test-ds)
        proj (create-test-project! ds "json-proj")]
    (testing "plan_json stored and retrieved correctly"
      (let [plan-data {:resource_changes [{:type "aws_instance"
                                            :change {:actions ["create"]}}
                                           {:type "aws_s3_bucket"
                                            :change {:actions ["update"]}}]}
            plan (iac-store/create-plan! ds
                   {:org-id "org-1" :project-id (:id proj) :action "plan"
                    :plan-json plan-data})
            retrieved (iac-store/get-plan ds (:id plan))]
        (is (some? (:plan retrieved)))
        (is (= 2 (count (:resource_changes (:plan retrieved)))))
        (is (= "aws_instance" (get-in (:plan retrieved) [:resource_changes 0 :type])))))))
