(ns chengis.db.org-isolation-test
  "Cross-org isolation tests — verifies that resources in one org are not
   visible to queries scoped to another org."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.org-store :as org-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.db.template-store :as template-store]
            [chengis.db.audit-store :as audit-store]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.db.approval-store :as approval-store]
            [chengis.distributed.agent-registry :as agent-reg]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-org-isolation-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (agent-reg/reset-registry!)
  (f)
  (agent-reg/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Helper: create two orgs for isolation testing
;; ---------------------------------------------------------------------------

(defn- create-two-orgs [ds]
  (let [org-a (:id (org-store/create-org! ds {:name "Acme Corp" :slug "acme"}))
        org-b (:id (org-store/create-org! ds {:name "Beta Inc" :slug "beta"}))]
    {:org-a org-a :org-b org-b}))

;; ---------------------------------------------------------------------------
;; Job isolation
;; ---------------------------------------------------------------------------

(deftest job-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [org-a org-b]} (create-two-orgs ds)]

    (testing "jobs in org-A not visible to org-B"
      (job-store/create-job! ds {:pipeline-name "deploy-service"
                                  :stages []}
        :org-id org-a)
      (job-store/create-job! ds {:pipeline-name "run-tests"
                                  :stages []}
        :org-id org-b)

      (let [a-jobs (job-store/list-jobs ds :org-id org-a)
            b-jobs (job-store/list-jobs ds :org-id org-b)]
        (is (= 1 (count a-jobs)))
        (is (= "deploy-service" (:name (first a-jobs))))
        (is (= 1 (count b-jobs)))
        (is (= "run-tests" (:name (first b-jobs))))))

    (testing "get-job by name scoped to org"
      (is (some? (job-store/get-job ds "deploy-service" :org-id org-a)))
      (is (nil? (job-store/get-job ds "deploy-service" :org-id org-b))))

    (testing "same job name allowed in different orgs"
      (let [id-a (job-store/create-job! ds {:pipeline-name "shared-name"
                                             :stages []}
                   :org-id org-a)
            id-b (job-store/create-job! ds {:pipeline-name "shared-name"
                                             :stages []}
                   :org-id org-b)]
        (is (some? id-a))
        (is (some? id-b))
        (is (not= (:id id-a) (:id id-b)))))

    (testing "nil org-id returns all jobs (backward compat)"
      (let [all-jobs (job-store/list-jobs ds)]
        (is (>= (count all-jobs) 4))))))

;; ---------------------------------------------------------------------------
;; Build isolation
;; ---------------------------------------------------------------------------

(deftest build-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [org-a org-b]} (create-two-orgs ds)
        ;; Create jobs first so builds have valid job-ids
        job-a (:id (job-store/create-job! ds {:pipeline-name "test-a" :stages []}
                      :org-id org-a))
        job-b (:id (job-store/create-job! ds {:pipeline-name "test-b" :stages []}
                      :org-id org-b))]

    (testing "builds scoped to org"
      (let [build-a (build-store/create-build! ds {:job-id job-a :org-id org-a})
            build-b (build-store/create-build! ds {:job-id job-b :org-id org-b})
            a-builds (build-store/list-builds ds {:org-id org-a})
            b-builds (build-store/list-builds ds {:org-id org-b})]
        (is (= 1 (count a-builds)))
        (is (= 1 (count b-builds)))
        (is (= (:id build-a) (:id (first a-builds))))
        (is (= (:id build-b) (:id (first b-builds))))))

    (testing "build stats scoped to org"
      (let [stats-a (build-store/get-build-stats ds {:org-id org-a})
            stats-b (build-store/get-build-stats ds {:org-id org-b})]
        (is (= 1 (:total stats-a)))
        (is (= 1 (:total stats-b)))))))

;; ---------------------------------------------------------------------------
;; Secret isolation
;; ---------------------------------------------------------------------------

(deftest secret-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        config {:secrets {:master-key "test-key-12345678"}}
        {:keys [org-a org-b]} (create-two-orgs ds)]

    (testing "secrets scoped to org"
      (secret-store/set-secret! ds config "API_KEY" "secret-a" :org-id org-a)
      (secret-store/set-secret! ds config "API_KEY" "secret-b" :org-id org-b)

      (is (= "secret-a" (secret-store/get-secret ds config "API_KEY" :org-id org-a)))
      (is (= "secret-b" (secret-store/get-secret ds config "API_KEY" :org-id org-b))))

    (testing "secret names scoped to org"
      (secret-store/set-secret! ds config "DB_PASS" "pass-a" :org-id org-a)
      (let [a-names (secret-store/list-secret-names ds :org-id org-a)
            b-names (secret-store/list-secret-names ds :org-id org-b)]
        (is (= 2 (count a-names)))  ;; API_KEY + DB_PASS
        (is (= 1 (count b-names))))) ;; API_KEY only

    (testing "delete secret scoped to org"
      (is (true? (secret-store/delete-secret! ds "API_KEY" :org-id org-a)))
      (is (nil? (secret-store/get-secret ds config "API_KEY" :org-id org-a)))
      ;; org-B's secret still exists
      (is (= "secret-b" (secret-store/get-secret ds config "API_KEY" :org-id org-b))))))

;; ---------------------------------------------------------------------------
;; Template isolation
;; ---------------------------------------------------------------------------

(deftest template-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [org-a org-b]} (create-two-orgs ds)]

    (testing "templates scoped to org"
      (template-store/create-template! ds {:name "deploy-template"
                                            :description "Deploy pipeline"
                                            :format "clojure"
                                            :content "(defpipeline deploy)"
                                            :org-id org-a})
      (template-store/create-template! ds {:name "test-template"
                                            :description "Test pipeline"
                                            :format "clojure"
                                            :content "(defpipeline test-it)"
                                            :org-id org-b})

      (let [a-templates (template-store/list-templates ds :org-id org-a)
            b-templates (template-store/list-templates ds :org-id org-b)]
        (is (= 1 (count a-templates)))
        (is (= "deploy-template" (:name (first a-templates))))
        (is (= 1 (count b-templates)))
        (is (= "test-template" (:name (first b-templates))))))

    (testing "same template name allowed in different orgs"
      (let [id-a (template-store/create-template! ds {:name "shared-tmpl"
                                                       :description "Shared"
                                                       :format "clojure"
                                                       :content "(defpipeline shared)"
                                                       :org-id org-a})
            id-b (template-store/create-template! ds {:name "shared-tmpl"
                                                       :description "Shared"
                                                       :format "clojure"
                                                       :content "(defpipeline shared)"
                                                       :org-id org-b})]
        (is (some? id-a))
        (is (some? id-b))))))

;; ---------------------------------------------------------------------------
;; Audit log isolation
;; ---------------------------------------------------------------------------

(deftest audit-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [org-a org-b]} (create-two-orgs ds)]

    (testing "audit logs scoped to org"
      (audit-store/insert-audit! ds {:user-id "u1" :username "alice"
                                      :action "login" :resource-type "session"
                                      :org-id org-a})
      (audit-store/insert-audit! ds {:user-id "u2" :username "bob"
                                      :action "login" :resource-type "session"
                                      :org-id org-b})
      (audit-store/insert-audit! ds {:user-id "u1" :username "alice"
                                      :action "create-job" :resource-type "job"
                                      :org-id org-a})

      (let [a-audits (audit-store/query-audits ds {:org-id org-a})
            b-audits (audit-store/query-audits ds {:org-id org-b})]
        (is (= 2 (count a-audits)))
        (is (= 1 (count b-audits)))))

    (testing "audit count scoped to org"
      (is (= 2 (audit-store/count-audits ds {:org-id org-a})))
      (is (= 1 (audit-store/count-audits ds {:org-id org-b}))))))

;; ---------------------------------------------------------------------------
;; Webhook log isolation
;; ---------------------------------------------------------------------------

(deftest webhook-isolation-test
  (let [ds (conn/create-datasource test-db-path)
        {:keys [org-a org-b]} (create-two-orgs ds)]

    (testing "webhook events scoped to org"
      (webhook-log/log-webhook-event! ds {:provider :github :event-type "push"
                                           :org-id org-a})
      (webhook-log/log-webhook-event! ds {:provider :gitlab :event-type "push"
                                           :org-id org-b})
      (webhook-log/log-webhook-event! ds {:provider :github :event-type "pull_request"
                                           :org-id org-a})

      (let [a-events (webhook-log/list-webhook-events ds :org-id org-a)
            b-events (webhook-log/list-webhook-events ds :org-id org-b)]
        (is (= 2 (count a-events)))
        (is (= 1 (count b-events)))))

    (testing "webhook count scoped to org"
      (is (= 2 (webhook-log/count-webhook-events ds :org-id org-a)))
      (is (= 1 (webhook-log/count-webhook-events ds :org-id org-b))))))

;; ---------------------------------------------------------------------------
;; Agent pool isolation (in-memory)
;; ---------------------------------------------------------------------------

(deftest agent-isolation-test
  (testing "shared agent visible to all orgs"
    (let [shared (agent-reg/register-agent! {:name "shared-agent"
                                              :url "http://shared:9090"
                                              :max-builds 2})]
      (is (some? (some #(= (:id shared) (:id %))
                       (agent-reg/list-agents :org-id "org-x"))))
      (is (some? (some #(= (:id shared) (:id %))
                       (agent-reg/list-agents :org-id "org-y"))))))

  (testing "org-specific agent only visible to its org"
    (let [org-agent (agent-reg/register-agent! {:name "acme-agent"
                                                 :url "http://acme:9090"
                                                 :max-builds 2
                                                 :org-id "org-acme"})]
      ;; Visible to org-acme
      (is (some? (some #(= (:id org-agent) (:id %))
                       (agent-reg/list-agents :org-id "org-acme"))))
      ;; Not visible to org-beta
      (is (nil? (some #(= (:id org-agent) (:id %))
                      (agent-reg/list-agents :org-id "org-beta"))))))

  (testing "find-available-agent respects org context"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "acme-builder"
                                 :url "http://acme-b:9090"
                                 :labels #{"docker"}
                                 :max-builds 2
                                 :org-id "org-acme"})
    (agent-reg/register-agent! {:name "beta-builder"
                                 :url "http://beta-b:9090"
                                 :labels #{"docker"}
                                 :max-builds 2
                                 :org-id "org-beta"})
    ;; org-acme should only find acme-builder
    (let [found (agent-reg/find-available-agent #{"docker"} :org-id "org-acme")]
      (is (some? found))
      (is (= "acme-builder" (:name found))))
    ;; org-beta should only find beta-builder
    (let [found (agent-reg/find-available-agent #{"docker"} :org-id "org-beta")]
      (is (some? found))
      (is (= "beta-builder" (:name found)))))

  (testing "shared agent found alongside org agent"
    (agent-reg/reset-registry!)
    (agent-reg/register-agent! {:name "shared"
                                 :url "http://shared:9090"
                                 :max-builds 2})
    (agent-reg/register-agent! {:name "acme-only"
                                 :url "http://acme:9090"
                                 :max-builds 2
                                 :org-id "org-acme"})
    ;; org-acme sees both (2 agents)
    (is (= 2 (count (agent-reg/list-agents :org-id "org-acme"))))
    ;; org-beta sees only shared (1 agent)
    (is (= 1 (count (agent-reg/list-agents :org-id "org-beta"))))
    ;; No org filter sees all (2 agents)
    (is (= 2 (count (agent-reg/list-agents)))))

  (testing "registry-summary respects org-id"
    (let [acme-summary (agent-reg/registry-summary :org-id "org-acme")
          beta-summary (agent-reg/registry-summary :org-id "org-beta")]
      (is (= 2 (:total acme-summary)))
      (is (= 1 (:total beta-summary))))))

;; ---------------------------------------------------------------------------
;; Default org backward compatibility
;; ---------------------------------------------------------------------------

(deftest default-org-backward-compat-test
  (let [ds (conn/create-datasource test-db-path)]

    (testing "default org exists after migration"
      (let [default-org (org-store/get-org ds "default-org")]
        (is (some? default-org))
        (is (= "Default" (:name default-org)))
        (is (= "default" (:slug default-org)))))

    (testing "resources created without explicit org-id use default"
      ;; Create a job without org-id (uses DEFAULT 'default-org' from schema)
      (job-store/create-job! ds {:pipeline-name "legacy-job"
                                  :stages []})
      ;; Should be visible when querying with default-org
      (let [jobs (job-store/list-jobs ds :org-id "default-org")]
        (is (some #(= "legacy-job" (:name %)) jobs))))

    (testing "nil org-id returns all resources including default org"
      (let [all-jobs (job-store/list-jobs ds)]
        (is (some #(= "legacy-job" (:name %)) all-jobs))))))
