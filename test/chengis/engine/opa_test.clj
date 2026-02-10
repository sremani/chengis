(ns chengis.engine.opa-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.opa-store :as opa-store]
            [chengis.engine.opa :as opa]
            [chengis.engine.process :as process]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-opa-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; build-opa-input tests
;; ---------------------------------------------------------------------------

(deftest build-opa-input-complete-context-test
  (testing "build-opa-input assembles all fields from build context"
    (let [ctx {:build-id "b-123"
               :job-id "j-456"
               :org-id "org-A"
               :git-branch "main"
               :git-author "alice"
               :parameters {:env "prod" :force "true"}
               :stage-name "deploy"}
          input (opa/build-opa-input ctx)]
      (is (= "b-123" (:build_id input)))
      (is (= "j-456" (:job_id input)))
      (is (= "org-A" (:org_id input)))
      (is (= "main" (:branch input)))
      (is (= "alice" (:author input)))
      (is (= {:env "prod" :force "true"} (:parameters input)))
      (is (= "deploy" (:stage_name input))))))

(deftest build-opa-input-minimal-context-test
  (testing "build-opa-input handles nil fields gracefully"
    (let [ctx {:build-id "b-1"}
          input (opa/build-opa-input ctx)]
      (is (= "b-1" (:build_id input)))
      (is (nil? (:job_id input)))
      (is (nil? (:org_id input)))
      (is (nil? (:branch input)))
      (is (nil? (:author input)))
      (is (nil? (:parameters input)))
      (is (nil? (:stage_name input))))))

;; ---------------------------------------------------------------------------
;; evaluate-opa-policy! tests (mocked process)
;; ---------------------------------------------------------------------------

(deftest evaluate-opa-policy-allow-test
  (testing "evaluate-opa-policy! returns allow when OPA result value is true"
    (let [opa-output (json/write-str
                       {:result [{:expressions [{:value true :text "data.test.allow"}]}]})
          system {:config {:opa {:eval-timeout-ms 5000}}}
          policy {:rego-source "package test\nallow = true"
                  :package-name "test"
                  :name "test-policy"}
          build-ctx {:build-id "b-1" :org-id "org-A"}]
      (with-redefs [process/execute-command
                    (fn [_opts]
                      {:exit-code 0 :stdout opa-output :stderr "" :timed-out? false})]
        (let [result (opa/evaluate-opa-policy! system policy build-ctx)]
          (is (= :allow (:result result)))
          (is (= "OPA policy allowed" (:reason result))))))))

(deftest evaluate-opa-policy-deny-test
  (testing "evaluate-opa-policy! returns deny when OPA result value is false"
    (let [opa-output (json/write-str
                       {:result [{:expressions [{:value false :text "data.test.allow"}]}]})
          system {:config {:opa {:eval-timeout-ms 5000}}}
          policy {:rego-source "package test\nallow = false"
                  :package-name "test"
                  :name "test-policy"}
          build-ctx {:build-id "b-1" :org-id "org-A"}]
      (with-redefs [process/execute-command
                    (fn [_opts]
                      {:exit-code 0 :stdout opa-output :stderr "" :timed-out? false})]
        (let [result (opa/evaluate-opa-policy! system policy build-ctx)]
          (is (= :deny (:result result)))
          (is (= "OPA policy denied" (:reason result))))))))

(deftest evaluate-opa-policy-not-found-test
  (testing "evaluate-opa-policy! returns allow when opa binary not found (exit 127)"
    (let [system {:config {}}
          policy {:rego-source "package test\nallow = true"
                  :package-name "test"
                  :name "test-policy"}
          build-ctx {:build-id "b-1"}]
      (with-redefs [process/execute-command
                    (fn [_opts]
                      {:exit-code 127 :stdout "" :stderr "opa: command not found" :timed-out? false})]
        (let [result (opa/evaluate-opa-policy! system policy build-ctx)]
          (is (= :allow (:result result)))
          (is (clojure.string/includes? (:reason result) "not available")))))))

(deftest evaluate-opa-policy-timeout-test
  (testing "evaluate-opa-policy! returns deny on timeout"
    (let [system {:config {:opa {:eval-timeout-ms 100}}}
          policy {:rego-source "package test\nallow = true"
                  :package-name "test"
                  :name "timeout-policy"}
          build-ctx {:build-id "b-1"}]
      (with-redefs [process/execute-command
                    (fn [_opts]
                      {:exit-code -1 :stdout "" :stderr "timed out" :timed-out? true})]
        (let [result (opa/evaluate-opa-policy! system policy build-ctx)]
          (is (= :deny (:result result)))
          (is (clojure.string/includes? (:reason result) "timed out")))))))

;; ---------------------------------------------------------------------------
;; validate-rego-syntax tests (mocked process)
;; ---------------------------------------------------------------------------

(deftest validate-rego-syntax-valid-test
  (testing "validate-rego-syntax returns valid for correct rego"
    (with-redefs [process/execute-command
                  (fn [_opts]
                    {:exit-code 0 :stdout "" :stderr "" :timed-out? false})]
      (let [result (opa/validate-rego-syntax {} "package test\nallow = true")]
        (is (true? (:valid? result)))
        (is (nil? (:errors result)))))))

(deftest validate-rego-syntax-invalid-test
  (testing "validate-rego-syntax returns errors for invalid rego"
    (with-redefs [process/execute-command
                  (fn [_opts]
                    {:exit-code 1 :stdout "" :stderr "1 error occurred: rego_parse_error" :timed-out? false})]
      (let [result (opa/validate-rego-syntax {} "invalid rego {{{")]
        (is (false? (:valid? result)))
        (is (clojure.string/includes? (:errors result) "rego_parse_error"))))))

;; ---------------------------------------------------------------------------
;; OPA store CRUD tests
;; ---------------------------------------------------------------------------

(deftest opa-store-crud-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "create and get OPA policy"
      (let [created (opa-store/create-policy! ds
                      {:org-id "org-A"
                       :name "branch-gate"
                       :description "Gate on branch"
                       :rego-source "package branch\nallow = true"
                       :package-name "branch"
                       :input-schema nil})
            fetched (opa-store/get-policy ds (:id created))]
        (is (some? (:id created)))
        (is (= "branch-gate" (:name fetched)))
        (is (= "branch" (:package-name fetched)))
        (is (= 1 (:enabled fetched)))))

    (testing "update OPA policy"
      (let [policies (opa-store/list-policies ds :org-id "org-A")
            policy-id (:id (first policies))]
        (opa-store/update-policy! ds policy-id
          {:description "Updated gate" :rego-source "package branch\nallow = false"})
        (let [updated (opa-store/get-policy ds policy-id)]
          (is (= "Updated gate" (:description updated)))
          (is (= "package branch\nallow = false" (:rego-source updated))))))

    (testing "delete OPA policy"
      (let [policies (opa-store/list-policies ds :org-id "org-A")
            policy-id (:id (first policies))]
        (opa-store/delete-policy! ds policy-id)
        (is (nil? (opa-store/get-policy ds policy-id)))))))

(deftest opa-store-list-enabled-only-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "list-policies with enabled-only filter"
      (opa-store/create-policy! ds
        {:org-id "org-B" :name "enabled-policy"
         :rego-source "package e\nallow=true" :package-name "e"
         :enabled true})
      (opa-store/create-policy! ds
        {:org-id "org-B" :name "disabled-policy"
         :rego-source "package d\nallow=false" :package-name "d"
         :enabled false})
      (let [all-policies (opa-store/list-policies ds :org-id "org-B")
            enabled-only (opa-store/list-policies ds :org-id "org-B" :enabled-only true)]
        (is (= 2 (count all-policies)))
        (is (= 1 (count enabled-only)))
        (is (= "enabled-policy" (:name (first enabled-only))))))))

(deftest opa-store-org-scoping-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "org-id scoping isolates policies"
      (opa-store/create-policy! ds
        {:org-id "org-X" :name "policy-x"
         :rego-source "package x\nallow=true" :package-name "x"})
      (opa-store/create-policy! ds
        {:org-id "org-Y" :name "policy-y"
         :rego-source "package y\nallow=true" :package-name "y"})
      (let [x-policies (opa-store/list-policies ds :org-id "org-X")
            y-policies (opa-store/list-policies ds :org-id "org-Y")]
        (is (= 1 (count x-policies)))
        (is (= "policy-x" (:name (first x-policies))))
        (is (= 1 (count y-policies)))
        (is (= "policy-y" (:name (first y-policies)))))
      ;; get-policy with wrong org returns nil
      (let [x-policy (first (opa-store/list-policies ds :org-id "org-X"))]
        (is (nil? (opa-store/get-policy ds (:id x-policy) :org-id "org-Y")))
        (is (some? (opa-store/get-policy ds (:id x-policy) :org-id "org-X")))))))
