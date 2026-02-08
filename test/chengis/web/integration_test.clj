(ns chengis.web.integration-test
  "Integration tests that exercise the full Ring middleware chain via app-routes.
   These catch cross-middleware bugs that unit tests of individual handlers miss."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.db.job-store :as job-store]
            [chengis.db.build-store :as build-store]
            [chengis.web.auth :as auth]
            [chengis.web.routes :as routes]
            [chengis.web.webhook :as webhook]
            [chengis.metrics :as metrics]
            [chengis.distributed.agent-registry :as agent-reg]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util.concurrent ThreadPoolExecutor TimeUnit SynchronousQueue]))

;; ---------------------------------------------------------------------------
;; Test infrastructure
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-integration-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  ;; Reset the agent registry between tests
  (reset! @#'agent-reg/agents {})
  (f)
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
  "Build the full Ring handler through app-routes for a given system."
  [system]
  (routes/app-routes system))

(defn- json-body
  "Parse JSON response body."
  [resp]
  (when (:body resp)
    (try
      (json/read-str (:body resp) :key-fn keyword)
      (catch Exception _ nil))))

(defn- hmac-sha256
  "Compute HMAC-SHA256 for GitHub webhook testing."
  [secret body-str]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")]
    (.init mac key-spec)
    (let [hash (.doFinal mac (.getBytes ^String body-str "UTF-8"))]
      (str "sha256=" (apply str (map #(format "%02x" %) (seq hash)))))))

;; ---------------------------------------------------------------------------
;; Test 1: Distributed agent auth through full middleware chain
;; ---------------------------------------------------------------------------

(deftest distributed-agent-auth-through-middleware-test
  (let [system (make-system {:auth {:enabled true
                                    :jwt-secret "test-jwt-secret-key-12345678"}
                             :distributed {:enabled true
                                           :auth-token "agent-secret-token"}})
        ds (:db system)
        handler (make-handler system)]

    ;; Create a test user for RBAC scenarios
    (user-store/create-user! ds {:username "admin1" :password "password123" :role "admin"})

    (testing "agent heartbeat with valid distributed token passes through middleware"
      ;; Register an agent first so heartbeat has a valid target
      (let [agent (agent-reg/register-agent! {:name "test-agent" :url "http://localhost:9090"})
            agent-id (:id agent)
            resp (handler {:uri (str "/api/agents/" agent-id "/heartbeat")
                           :request-method :post
                           :headers {"authorization" "Bearer agent-secret-token"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes "{}" "UTF-8"))})]
        ;; Should NOT be 401 from wrap-auth. Should reach the handler.
        (is (= 200 (:status resp))
            "Valid distributed token should reach heartbeat handler")))

    (testing "agent heartbeat with wrong token returns 401"
      (let [resp (handler {:uri "/api/agents/fake-id/heartbeat"
                           :request-method :post
                           :headers {"authorization" "Bearer wrong-token"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes "{}" "UTF-8"))})]
        (is (= 401 (:status resp))
            "Wrong distributed token should return 401 from handler check-auth")))

    (testing "agent heartbeat with no auth header returns 401"
      (let [resp (handler {:uri "/api/agents/fake-id/heartbeat"
                           :request-method :post
                           :headers {"content-type" "application/json"}
                           :body (io/input-stream (.getBytes "{}" "UTF-8"))})]
        (is (= 401 (:status resp))
            "No auth header should return 401 from handler check-auth")))

    (testing "agent register endpoint still requires RBAC (not exempted)"
      (let [resp (handler {:uri "/api/agents/register"
                           :request-method :post
                           :headers {"authorization" "Bearer agent-secret-token"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes "{\"name\":\"a\",\"url\":\"http://a:9090\"}" "UTF-8"))})]
        ;; Register uses wrap-require-role :admin, so the distributed token
        ;; (which isn't a valid JWT/API token) should fail at wrap-auth → 401
        (is (#{401 403} (:status resp))
            "Register should require RBAC auth, not distributed auth")))

    (testing "agent register with admin JWT succeeds"
      (let [db-admin (user-store/get-user-by-username ds "admin1")
            admin-user {:id (:id db-admin) :username "admin1" :role "admin"
                        :session-version (:session-version db-admin)}
            jwt (auth/generate-jwt admin-user (:config system))
            resp (handler {:uri "/api/agents/register"
                           :request-method :post
                           :headers {"authorization" (str "Bearer " jwt)
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes "{\"name\":\"agent1\",\"url\":\"http://a:9090\"}" "UTF-8"))})]
        (is (= 201 (:status resp))
            "Admin JWT should allow agent registration through middleware")))))

;; ---------------------------------------------------------------------------
;; Test 2: /metrics auth policy consistency
;; ---------------------------------------------------------------------------

(deftest metrics-auth-policy-test
  (testing "metrics with auth disabled returns 200"
    (let [system (make-system {:auth {:enabled false}
                               :metrics {:enabled true :auth-required false}})
          handler (make-handler system)
          resp (handler {:uri "/metrics" :request-method :get})]
      (is (= 200 (:status resp)))))

  (testing "metrics with auth enabled + auth-required=false returns 200"
    (let [system (make-system {:auth {:enabled true
                                      :jwt-secret "test-key-12345678"}
                               :metrics {:enabled true :auth-required false}})
          handler (make-handler system)
          resp (handler {:uri "/metrics" :request-method :get})]
      (is (= 200 (:status resp))
          "/metrics should be public when auth-required is false")))

  (testing "metrics with auth enabled + auth-required=true rejects unauthenticated"
    (let [system (make-system {:auth {:enabled true
                                      :jwt-secret "test-key-12345678"}
                               :metrics {:enabled true :auth-required true}})
          ds (:db system)
          handler (make-handler system)]
      (user-store/create-user! ds {:username "viewer1" :password "password123" :role "viewer"})
      ;; No auth → should be rejected (not 403!)
      (let [resp (handler {:uri "/metrics" :request-method :get :headers {}})]
        (is (#{401 303} (:status resp))
            "/metrics should require auth when auth-required=true"))))

  (testing "metrics with auth enabled + auth-required=true + valid viewer JWT returns 200"
    (let [system (make-system {:auth {:enabled true
                                      :jwt-secret "test-key-12345678"}
                               :metrics {:enabled true :auth-required true}})
          ds (:db system)
          handler (make-handler system)]
      (user-store/create-user! ds {:username "viewer2" :password "password123" :role "viewer"})
      (let [db-viewer (user-store/get-user-by-username ds "viewer2")
            user {:id (:id db-viewer) :username "viewer2" :role "viewer"
                  :session-version (:session-version db-viewer)}
            jwt (auth/generate-jwt user (:config system))
            resp (handler {:uri "/metrics" :request-method :get
                           :headers {"authorization" (str "Bearer " jwt)}})]
        (is (= 200 (:status resp))
            "Authenticated viewer should access /metrics when auth-required=true")))))

;; ---------------------------------------------------------------------------
;; Test 3: Webhook provider + signature verification
;; ---------------------------------------------------------------------------

(deftest webhook-signature-verification-test
  (let [webhook-secret "my-webhook-secret"]

    (testing "GitHub webhook with valid HMAC passes"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret webhook-secret}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\",\"head_commit\":{\"id\":\"abc123\",\"author\":{\"name\":\"dev\"},\"message\":\"test\"},\"repository\":{\"clone_url\":\"https://github.com/test/repo.git\",\"full_name\":\"test/repo\"}}"
            signature (hmac-sha256 webhook-secret body-str)
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" signature
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        ;; Should be 200 (no matching jobs) not 401
        (is (= 200 (:status resp))
            "Valid GitHub HMAC should pass verification")
        (is (= 0 (:triggered (json-body resp))))))

    (testing "GitHub webhook with invalid HMAC fails"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret webhook-secret}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\"}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" "sha256=invalid"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 401 (:status resp))
            "Invalid GitHub HMAC should return 401")))

    (testing "GitLab webhook with valid token passes"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret webhook-secret}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\",\"checkout_sha\":\"abc123\",\"commits\":[{\"author\":{\"name\":\"dev\"},\"message\":\"test\"}],\"project\":{\"git_http_url\":\"https://gitlab.com/test/repo.git\",\"path_with_namespace\":\"test/repo\"}}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-gitlab-event" "Push Hook"
                                     "x-gitlab-token" webhook-secret
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        ;; Should be 200 (no matching jobs) not 401
        (is (= 200 (:status resp))
            "Valid GitLab token should pass verification")
        (is (= 0 (:triggered (json-body resp))))))

    (testing "GitLab webhook with invalid token fails"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret webhook-secret}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\"}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-gitlab-event" "Push Hook"
                                     "x-gitlab-token" "wrong-token"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 401 (:status resp))
            "Invalid GitLab token should return 401")))

    (testing "webhook with no provider headers returns 400"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret webhook-secret}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\"}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 400 (:status resp))
            "Missing provider headers should return 400")))

    (testing "webhook with no secret configured accepts all providers"
      (let [system (make-system {:auth {:enabled false}
                                 :webhook {:secret nil}})
            handler (make-handler system)
            body-str "{\"ref\":\"refs/heads/main\",\"checkout_sha\":\"abc\",\"commits\":[{\"author\":{\"name\":\"dev\"},\"message\":\"test\"}],\"project\":{\"git_http_url\":\"https://gitlab.com/t/r.git\",\"path_with_namespace\":\"t/r\"}}"
            resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-gitlab-event" "Push Hook"
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 200 (:status resp))
            "Without secret configured, all webhooks should pass signature check")))))

;; ---------------------------------------------------------------------------
;; Test 4: Webhook queue saturation marks build as failed (HF-02)
;; ---------------------------------------------------------------------------

(deftest webhook-queue-saturation-marks-build-failed-test
  (let [webhook-secret "saturation-secret"
        system (make-system {:auth {:enabled false}
                             :webhook {:secret webhook-secret}})
        ds (:db system)
        ;; Create a job whose source URL matches the webhook payload
        repo-url "https://github.com/test/saturate.git"
        _ (job-store/create-job! ds {:pipeline-name "saturate-test"
                                     :source {:type :git :url repo-url}
                                     :stages [{:stage-name "build"
                                               :steps [{:name "echo" :type :shell
                                                        :command "echo hi"}]}]})
        ;; Create a saturated executor: 1 thread, SynchronousQueue (no buffering)
        saturated-executor (ThreadPoolExecutor. 1 1 0 TimeUnit/SECONDS
                                               (SynchronousQueue.))
        ;; Block the only thread so next submit is rejected
        blocker (promise)
        _ (.submit saturated-executor ^Runnable (fn [] @blocker))
        ;; Build the webhook handler directly with our saturated executor
        handler (webhook/webhook-handler system saturated-executor)
        body-str (json/write-str {:ref "refs/heads/main"
                                  :head_commit {:id "abc123"
                                                :author {:name "dev"}
                                                :message "test"}
                                  :repository {:clone_url repo-url
                                               :full_name "test/saturate"}})
        signature (hmac-sha256 webhook-secret body-str)]

    (testing "webhook returns 200 but triggered count is 0 when queue is full"
      (let [resp (handler {:uri "/api/webhook"
                           :request-method :post
                           :headers {"x-github-event" "push"
                                     "x-hub-signature-256" signature
                                     "content-type" "application/json"}
                           :body (io/input-stream (.getBytes body-str "UTF-8"))})]
        (is (= 200 (:status resp)))
        (is (= 0 (:triggered (json-body resp)))
            "No builds should be triggered when executor is saturated")))

    (testing "rejected build is marked as failed in DB"
      (let [job (job-store/get-job ds "saturate-test")
            builds (build-store/list-builds ds (:id job))]
        (is (= 1 (count builds))
            "One build record should exist from the webhook")
        (let [build (first builds)]
          (is (= :failure (:status build))
              "Build must be marked as :failure when executor rejects")
          (is (some? (:completed-at build))
              "completed-at must be set for rejected build"))))

    ;; Cleanup
    (deliver blocker :done)
    (.shutdownNow saturated-executor)))

;; ---------------------------------------------------------------------------
;; Test 5: Custom metrics path routing (HF-03)
;; ---------------------------------------------------------------------------

(deftest custom-metrics-path-routing-test
  (testing "custom metrics path serves Prometheus metrics"
    (let [system (make-system {:auth {:enabled false}
                               :metrics {:path "/custom-metrics"
                                         :auth-required false}})
          handler (make-handler system)
          resp (handler {:uri "/custom-metrics" :request-method :get})]
      (is (= 200 (:status resp))
          "Custom metrics path should return 200")
      (is (clojure.string/includes?
            (get-in resp [:headers "Content-Type"] "")
            "text/plain")
          "Metrics response should be Prometheus text format")))

  (testing "default /metrics path returns 404 when custom path is configured"
    (let [system (make-system {:auth {:enabled false}
                               :metrics {:path "/custom-metrics"
                                         :auth-required false}})
          handler (make-handler system)
          resp (handler {:uri "/metrics" :request-method :get})]
      (is (= 404 (:status resp))
          "/metrics should be 404 when custom path overrides it")))

  (testing "custom metrics path with auth-required=true rejects unauthenticated"
    (let [system (make-system {:auth {:enabled true
                                      :jwt-secret "test-key-12345678"}
                               :metrics {:path "/custom-metrics"
                                         :auth-required true}})
          handler (make-handler system)
          resp (handler {:uri "/custom-metrics" :request-method :get :headers {}})]
      (is (#{401 303} (:status resp))
          "Custom metrics path should require auth when auth-required=true"))))
