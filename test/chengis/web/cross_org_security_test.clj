(ns chengis.web.cross-org-security-test
  "Regression tests for cross-org security findings:
   - SSE endpoint denies access to builds from other orgs
   - Webhook-triggered builds inherit org-id from matched job
   - Alerts are scoped to the requesting user's org
   - Webhook endpoint works when auth is enabled (public path bypass)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.org-store :as org-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [chengis.web.alerts :as alerts]
            [chengis.web.routes :as routes]
            [chengis.metrics :as metrics]
            [chengis.distributed.agent-registry :as agent-reg]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Test infrastructure
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-cross-org-security-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (reset! @#'agent-reg/agents {})
  (f)
  (reset! @#'agent-reg/agents {})
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(defn- make-system
  "Create a test system map with config overrides."
  [config-overrides]
  (let [ds (conn/create-datasource test-db-path)
        registry (try (metrics/init-registry) (catch Exception _ nil))
        audit-ch (async/chan 16)]
    {:db ds
     :config (merge {:database {:path test-db-path}
                     :auth {:enabled false}
                     :distributed {:enabled false}
                     :metrics {:enabled true :auth-required false}}
                    config-overrides)
     :metrics registry
     :audit-writer {:channel audit-ch}}))

(defn- make-handler
  "Build the full Ring handler through app-routes."
  [system]
  (routes/app-routes system))

(defn- json-body [resp]
  (when (:body resp)
    (try
      (json/read-str (:body resp) :key-fn keyword)
      (catch Exception _ nil))))

(defn- create-two-orgs
  "Create two isolated orgs for testing."
  [ds]
  (let [org-a (:id (org-store/create-org! ds {:name "Alpha Corp" :slug "alpha"}))
        org-b (:id (org-store/create-org! ds {:name "Bravo Inc" :slug "bravo"}))]
    {:org-a org-a :org-b org-b}))

(defn- create-user-in-org!
  "Create a user and add them to an org with a role. Returns the user record."
  [ds org-id username role]
  (user-store/create-user! ds {:username username :password "password123" :role role})
  (let [user (user-store/get-user-by-username ds username)]
    (org-store/add-member! ds {:org-id org-id :user-id (:id user) :role role})
    user))

(defn- jwt-for
  "Generate a JWT for the given user."
  [ds config username]
  (let [db-user (user-store/get-user-by-username ds username)
        user {:id (:id db-user) :username username :role (:role db-user)
              :session-version (:session-version db-user)}]
    (auth/generate-jwt user config)))

(defn- hmac-sha256
  "Compute HMAC-SHA256 for GitHub webhook testing."
  [secret body-str]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac (.getBytes ^String body-str "UTF-8"))]
      (str "sha256=" (apply str (map #(format "%02x" %) (seq hash)))))))

;; ---------------------------------------------------------------------------
;; Test 1: Cross-org SSE denial
;; ---------------------------------------------------------------------------

(deftest cross-org-sse-denial-test
  (let [system (make-system {:auth {:enabled true
                                    :jwt-secret "test-jwt-secret-key-12345678"}})
        ds (:db system)
        handler (make-handler system)
        {:keys [org-a org-b]} (create-two-orgs ds)
        ;; Create users in each org
        _user-a (create-user-in-org! ds org-a "alice" "developer")
        _user-b (create-user-in-org! ds org-b "bob" "developer")
        ;; Create a job and build in org-A
        job-a (:id (job-store/create-job! ds {:pipeline-name "deploy-alpha"
                                               :stages [{:stage-name "build"
                                                         :steps [{:name "echo"
                                                                  :type :shell
                                                                  :command "echo hi"}]}]}
                     :org-id org-a))
        build-a (build-store/create-build! ds {:job-id job-a :org-id org-a})
        jwt-a (jwt-for ds (:config system) "alice")
        jwt-b (jwt-for ds (:config system) "bob")]

    (testing "owner can access their own build's SSE endpoint"
      ;; Alice is a member of org-A only. wrap-org-context resolves her org
      ;; from membership (first org), so she gets org-A context automatically.
      (let [resp (handler {:uri (str "/api/builds/" (:id build-a) "/events")
                           :request-method :get
                           :headers {"authorization" (str "Bearer " jwt-a)
                                     "accept" "text/event-stream"}})]
        ;; Should NOT be 404 — alice owns this build via org-A
        (is (not= 404 (:status resp))
            "Build owner should be able to access SSE events")))

    (testing "user from different org gets 404 for cross-org build SSE"
      ;; Bob is a member of org-B only. wrap-org-context resolves his org
      ;; from membership, so he gets org-B context — org-A's build is invisible.
      (let [resp (handler {:uri (str "/api/builds/" (:id build-a) "/events")
                           :request-method :get
                           :headers {"authorization" (str "Bearer " jwt-b)
                                     "accept" "text/event-stream"}})]
        (is (= 404 (:status resp))
            "Cross-org user must get 404 — build should not be visible")))

    (testing "unauthenticated user denied SSE access"
      (let [resp (handler {:uri (str "/api/builds/" (:id build-a) "/events")
                           :request-method :get
                           :headers {}})]
        (is (#{401 303} (:status resp))
            "Unauthenticated user should be redirected or get 401")))))

;; ---------------------------------------------------------------------------
;; Test 2: Webhook build org attribution
;; ---------------------------------------------------------------------------

(deftest webhook-build-org-attribution-test
  (let [webhook-secret "webhook-org-test-secret"
        system (make-system {:auth {:enabled false}
                             :webhook {:secret webhook-secret}})
        ds (:db system)
        handler (make-handler system)
        {:keys [org-a org-b]} (create-two-orgs ds)
        ;; Create jobs in different orgs with distinct repo URLs
        repo-url-a "https://github.com/alpha/service.git"
        repo-url-b "https://github.com/bravo/service.git"
        _ (job-store/create-job! ds {:pipeline-name "alpha-deploy"
                                      :source {:type :git :url repo-url-a}
                                      :stages [{:stage-name "build"
                                                :steps [{:name "echo" :type :shell
                                                         :command "echo alpha"}]}]}
            :org-id org-a)
        _ (job-store/create-job! ds {:pipeline-name "bravo-deploy"
                                      :source {:type :git :url repo-url-b}
                                      :stages [{:stage-name "build"
                                                :steps [{:name "echo" :type :shell
                                                         :command "echo bravo"}]}]}
            :org-id org-b)]

    (testing "webhook-triggered build inherits org-id from matched job (org-A)"
      (let [body-str (json/write-str {:ref "refs/heads/main"
                                      :head_commit {:id "aaa111"
                                                    :author {:name "dev"}
                                                    :message "deploy alpha"}
                                      :repository {:clone_url repo-url-a
                                                   :full_name "alpha/service"}})
            signature (hmac-sha256 webhook-secret body-str)
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" signature
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        ;; Webhook accepted — check build was created with correct org-id
        (is (= 200 (:status resp)))
        (let [a-builds (build-store/list-builds ds {:org-id org-a})
              b-builds (build-store/list-builds ds {:org-id org-b})]
          (is (= 1 (count a-builds))
              "Build should be created in org-A")
          (is (= 0 (count b-builds))
              "No build should exist in org-B"))))

    (testing "webhook-triggered build inherits org-id from matched job (org-B)"
      (let [body-str (json/write-str {:ref "refs/heads/main"
                                      :head_commit {:id "bbb222"
                                                    :author {:name "dev"}
                                                    :message "deploy bravo"}
                                      :repository {:clone_url repo-url-b
                                                   :full_name "bravo/service"}})
            signature (hmac-sha256 webhook-secret body-str)
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" signature
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 200 (:status resp)))
        (let [b-builds (build-store/list-builds ds {:org-id org-b})]
          (is (= 1 (count b-builds))
              "Build should be created in org-B"))))

    (testing "org-A build not visible to org-B query"
      (let [a-builds (build-store/list-builds ds {:org-id org-a})
            b-builds (build-store/list-builds ds {:org-id org-b})]
        (is (= 1 (count a-builds)))
        (is (= 1 (count b-builds)))
        ;; Verify the builds have different org-ids
        (is (= org-a (:org-id (first a-builds))))
        (is (= org-b (:org-id (first b-builds))))))))

;; ---------------------------------------------------------------------------
;; Test 3: Alerts org scoping isolation
;; ---------------------------------------------------------------------------

(deftest alerts-org-scoping-test
  (let [system (make-system {:auth {:enabled false}})
        ds (:db system)
        {:keys [org-a org-b]} (create-two-orgs ds)
        ;; Create jobs in each org
        job-a (:id (job-store/create-job! ds {:pipeline-name "alpha-pipeline"
                                               :stages []}
                     :org-id org-a))
        job-b (:id (job-store/create-job! ds {:pipeline-name "bravo-pipeline"
                                               :stages []}
                     :org-id org-b))]

    (testing "failure alert only appears for org with failing builds"
      ;; Create 10 failing builds in org-A
      (dotimes [_ 10]
        (let [build (build-store/create-build! ds {:job-id job-a :org-id org-a})]
          (build-store/save-build-result! ds
            {:build-id (:id build) :build-status :failure :stage-results []
             :started-at "2025-01-01T00:00:00Z"
             :completed-at "2025-01-01T00:01:00Z"})))
      ;; Create 6 successful builds in org-B
      (dotimes [_ 6]
        (let [build (build-store/create-build! ds {:job-id job-b :org-id org-b})]
          (build-store/save-build-result! ds
            {:build-id (:id build) :build-status :success :stage-results []
             :started-at "2025-01-01T00:00:00Z"
             :completed-at "2025-01-01T00:01:00Z"})))

      ;; Org-A should have failure alert
      (let [alerts-a (alerts/check-alerts system :org-id org-a)
            failure-alert (first (filter #(= "build-failure-rate" (:metric %)) alerts-a))]
        (is (some? failure-alert)
            "Org-A should have a failure rate alert (100% failures)"))

      ;; Org-B should NOT have failure alert
      (let [alerts-b (alerts/check-alerts system :org-id org-b)
            failure-alert (first (filter #(= "build-failure-rate" (:metric %)) alerts-b))]
        (is (nil? failure-alert)
            "Org-B should have no failure alert (100% success)")))

    (testing "alerts without org-id see all builds (backward compat)"
      (let [all-alerts (alerts/check-alerts system)
            failure-alert (first (filter #(= "build-failure-rate" (:metric %)) all-alerts))]
        ;; With 10 failures and 6 successes globally (62.5% failure) → should alert
        (is (some? failure-alert)
            "Global alerts should reflect cross-org aggregate")))

    (testing "long-running build alert uses build-number not build-id"
      ;; Create a running build with a very old created-at in org-A
      (let [build (build-store/create-build! ds {:job-id job-a :org-id org-a})
            ;; Manually update created-at to 2 hours ago to trigger long-running alert
            old-time (str (.minus (java.time.Instant/now) (java.time.Duration/ofHours 2)))]
        (jdbc/execute-one! ds
          [(str "UPDATE builds SET status = 'running', created_at = ? WHERE id = ?")
           old-time (:id build)])
        (let [alerts-a (alerts/check-alerts system :org-id org-a)
              long-alert (first (filter #(= "long-running-build" (:metric %)) alerts-a))]
          (is (some? long-alert)
              "Should detect long-running build")
          (when long-alert
            (is (re-find #"Build #\d+" (:message long-alert))
                "Alert message should use build-number (#N), not raw build-id UUID")
            (is (not (re-find #"[0-9a-f]{8}-[0-9a-f]{4}" (:message long-alert)))
                "Alert message must NOT contain UUID build-id")))))))

;; ---------------------------------------------------------------------------
;; Test 4: Webhook works with auth.enabled=true
;; ---------------------------------------------------------------------------

(deftest webhook-auth-bypass-test
  (let [webhook-secret "auth-bypass-test-secret"
        system (make-system {:auth {:enabled true
                                    :jwt-secret "test-jwt-secret-key-12345678"}
                             :webhook {:secret webhook-secret}})
        ds (:db system)
        handler (make-handler system)]

    ;; Create a user so wrap-auth can resolve JWTs (needed for the system)
    (user-store/create-user! ds {:username "admin1" :password "password123" :role "admin"})

    (testing "webhook succeeds with auth enabled and valid signature (no JWT needed)"
      (let [body-str (json/write-str {:ref "refs/heads/main"
                                      :head_commit {:id "abc123"
                                                    :author {:name "dev"}
                                                    :message "test commit"}
                                      :repository {:clone_url "https://github.com/test/repo.git"
                                                   :full_name "test/repo"}})
            signature (hmac-sha256 webhook-secret body-str)
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" signature
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 200 (:status resp))
            "Webhook should bypass auth middleware and succeed with valid HMAC")
        (is (= 0 (:triggered (json-body resp)))
            "No matching jobs expected — but request should not be rejected by auth")))

    (testing "webhook with invalid signature still returns 401 (not auth 401)"
      (let [body-str "{\"ref\":\"refs/heads/main\"}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" "sha256=invalid"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 401 (:status resp))
            "Invalid HMAC should be rejected by webhook handler (not by auth middleware)")))

    (testing "webhook without any signature or provider headers returns 400"
      (let [body-str "{\"ref\":\"refs/heads/main\"}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 400 (:status resp))
            "Missing provider headers should return 400, not auth 401")))

    (testing "non-webhook API endpoint still requires auth"
      (let [resp (handler {:uri "/api/alerts"
                           :request-method :get
                           :headers {}})]
        (is (#{401 303} (:status resp))
            "Non-webhook API endpoints should still require authentication")))))

;; ---------------------------------------------------------------------------
;; Test 5: Cross-org secret isolation during build execution path
;; ---------------------------------------------------------------------------

(deftest cross-org-build-secret-isolation-test
  (let [system (make-system {:auth {:enabled false}
                             :secrets {:master-key "test-master-key-12345678"}})
        ds (:db system)
        config (:config system)
        {:keys [org-a org-b]} (create-two-orgs ds)
        ;; Create jobs in each org
        job-a (:id (job-store/create-job! ds {:pipeline-name "alpha-build"
                                               :stages []}
                     :org-id org-a))
        job-b (:id (job-store/create-job! ds {:pipeline-name "bravo-build"
                                               :stages []}
                     :org-id org-b))]

    (testing "same-name secrets in different orgs have different values"
      ;; Set same-name secret with different values per org
      (secret-store/set-secret! ds config "API_TOKEN" "alpha-secret-value" :org-id org-a)
      (secret-store/set-secret! ds config "API_TOKEN" "bravo-secret-value" :org-id org-b)
      (secret-store/set-secret! ds config "SHARED_KEY" "alpha-shared" :org-id org-a :scope "global")
      (secret-store/set-secret! ds config "SHARED_KEY" "bravo-shared" :org-id org-b :scope "global")

      ;; Build in org-A should only see org-A's secrets
      (let [secrets-a (secret-store/get-secrets-for-build ds config job-a :org-id org-a)]
        (is (= "alpha-secret-value" (get secrets-a "API_TOKEN"))
            "Org-A build should see org-A's API_TOKEN")
        (is (= "alpha-shared" (get secrets-a "SHARED_KEY"))
            "Org-A build should see org-A's SHARED_KEY"))

      ;; Build in org-B should only see org-B's secrets
      (let [secrets-b (secret-store/get-secrets-for-build ds config job-b :org-id org-b)]
        (is (= "bravo-secret-value" (get secrets-b "API_TOKEN"))
            "Org-B build should see org-B's API_TOKEN")
        (is (= "bravo-shared" (get secrets-b "SHARED_KEY"))
            "Org-B build should see org-B's SHARED_KEY")))

    (testing "cross-org secret query does not leak secrets from other org"
      ;; Create a job-scoped secret only in org-A
      (secret-store/set-secret! ds config "JOB_SECRET" "alpha-job-only"
                                :org-id org-a :scope job-a)

      ;; Querying with org-B context for org-A's job should not return org-A secrets
      (let [secrets-cross (secret-store/get-secrets-for-build ds config job-a :org-id org-b)]
        (is (nil? (get secrets-cross "JOB_SECRET"))
            "Org-B query for org-A's job-scoped secret must return nil")
        ;; Global secrets from org-B are still visible (they belong to org-B),
        ;; but the values must be org-B's, never org-A's
        (is (not= "alpha-secret-value" (get secrets-cross "API_TOKEN"))
            "Cross-org query must never return org-A's secret value")
        (is (not= "alpha-shared" (get secrets-cross "SHARED_KEY"))
            "Cross-org query must never return org-A's SHARED_KEY value")))))
