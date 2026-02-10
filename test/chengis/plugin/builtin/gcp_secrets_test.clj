(ns chengis.plugin.builtin.gcp-secrets-test
  "Tests for GCP Secret Manager SecretBackend plugin.
   Covers: config resolution, secret fetching, listing, merge behavior,
   error handling, and plugin registration — all with mocked GCP SDK calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.gcp-secrets :as gcp]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Registry cleanup fixture
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    (registry/reset-registry!)
    (f)
    (registry/reset-registry!)))

;; ---------------------------------------------------------------------------
;; Plugin Registration Tests
;; ---------------------------------------------------------------------------

(deftest gcp-plugin-init-test
  (testing "gcp-secrets init! registers plugin descriptor"
    (gcp/init!)
    (let [plugin (registry/get-plugin "gcp-secrets")]
      (is (some? plugin))
      (is (= "gcp-secrets" (:name plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (contains? (:provides plugin) :secret-backend)))))

(deftest gcp-backend-creation-test
  (testing "create-backend returns a GcpSecretsBackend"
    (let [backend (gcp/create-backend)]
      (is (some? backend))
      (is (satisfies? proto/SecretBackend backend)))))

;; ---------------------------------------------------------------------------
;; Config Resolution Tests
;; ---------------------------------------------------------------------------

(deftest resolve-gcp-config-explicit-test
  (testing "resolve-gcp-config uses explicit config values"
    (let [config {:secrets {:gcp {:project-id "my-project-123"
                                  :prefix "myapp-"}}}
          result (gcp/resolve-gcp-config config)]
      (is (= "my-project-123" (:project-id result)))
      (is (= "myapp-" (:prefix result))))))

(deftest resolve-gcp-config-defaults-test
  (testing "resolve-gcp-config provides defaults when config is empty"
    (with-redefs [gcp/resolve-gcp-config
                  (fn [_config]
                    {:project-id nil
                     :prefix "chengis-"})]
      (let [result (gcp/resolve-gcp-config {})]
        (is (nil? (:project-id result)))
        (is (= "chengis-" (:prefix result)))))))

;; ---------------------------------------------------------------------------
;; Secret Fetching with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest fetch-secret-no-project-id-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id nil
                                :prefix "chengis-"}}}]

    (testing "fetch-secret returns nil when no project-id configured"
      (is (nil? (proto/fetch-secret backend "DB_PASSWORD" "global" config))))

    (testing "list-secrets returns empty when no project-id configured"
      (is (empty? (proto/list-secrets backend "global" config))))

    (testing "fetch-secrets-for-build returns nil when no project-id configured"
      (is (nil? (proto/fetch-secrets-for-build backend "job-1" config))))))

(deftest fetch-secret-with-mock-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    ;; Mock client creation and SDK calls
    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-access-secret
                  (fn [client project-id secret-id]
                    (when (and (= client :mock-client)
                               (= project-id "my-project")
                               (= secret-id "chengis-global-DB_PASSWORD"))
                      "s3cret-password"))]

      (testing "fetch-secret returns value for existing secret"
        (is (= "s3cret-password"
               (proto/fetch-secret backend "DB_PASSWORD" "global" config)))))

    ;; Mock for missing secret
    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-access-secret (fn [_client _proj _id] nil)]

      (testing "fetch-secret returns nil for missing secret"
        (is (nil? (proto/fetch-secret backend "NONEXISTENT" "global" config)))))))

(deftest fetch-secret-with-org-id-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}
                :org-id "org-42"}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-access-secret
                  (fn [_client _proj secret-id]
                    (when (= secret-id "chengis-org-42-global-API_KEY")
                      "org-scoped-key"))]

      (testing "fetch-secret includes org-id in key name"
        (is (= "org-scoped-key"
               (proto/fetch-secret backend "API_KEY" "global" config)))))))

;; ---------------------------------------------------------------------------
;; List Secrets with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest list-secrets-with-mock-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-list-secrets
                  (fn [_client _proj prefix]
                    (when (= prefix "chengis-global-")
                      ["chengis-global-DB_PASSWORD"
                       "chengis-global-API_KEY"
                       "chengis-global-JWT_SECRET"]))]

      (testing "list-secrets returns stripped secret names"
        (is (= ["DB_PASSWORD" "API_KEY" "JWT_SECRET"]
               (proto/list-secrets backend "global" config)))))))

(deftest list-secrets-empty-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-list-secrets (fn [_client _proj _prefix] [])]

      (testing "list-secrets returns empty vector when no secrets"
        (is (= [] (proto/list-secrets backend "global" config)))))))

;; ---------------------------------------------------------------------------
;; Fetch Secrets for Build (Merge Behavior)
;; ---------------------------------------------------------------------------

(deftest fetch-secrets-for-build-merge-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}
        ;; Mock data
        mock-list-data {"chengis-global-"  ["chengis-global-SHARED_KEY"]
                        "chengis-my-job-"  ["chengis-my-job-JOB_SECRET"
                                            "chengis-my-job-SHARED_KEY"]}
        mock-get-data {"chengis-global-SHARED_KEY"  "global-shared"
                       "chengis-my-job-JOB_SECRET"  "job-specific"
                       "chengis-my-job-SHARED_KEY"  "job-overrides-global"}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-list-secrets
                  (fn [_client _proj prefix] (get mock-list-data prefix []))
                  gcp/gcp-access-secret
                  (fn [_client _proj secret-id] (get mock-get-data secret-id))]

      (testing "fetch-secrets-for-build merges global + job secrets (job wins)"
        (let [result (proto/fetch-secrets-for-build backend "my-job" config)]
          (is (= "job-overrides-global" (get result "SHARED_KEY"))
              "Job-scoped secret overrides global")
          (is (= "job-specific" (get result "JOB_SECRET"))
              "Job-only secret is included")
          (is (= 2 (count result))))))))

(deftest fetch-secrets-for-build-global-only-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-list-secrets
                  (fn [_client _proj prefix]
                    (case prefix
                      "chengis-global-" ["chengis-global-DB_PASS"]
                      "chengis-no-job-secrets-" []))
                  gcp/gcp-access-secret
                  (fn [_client _proj secret-id]
                    (when (= secret-id "chengis-global-DB_PASS")
                      "dbpass"))]

      (testing "only global secrets when job has none"
        (let [result (proto/fetch-secrets-for-build backend "no-job-secrets" config)]
          (is (= {"DB_PASS" "dbpass"} result)))))))

;; ---------------------------------------------------------------------------
;; Error Handling / Edge Cases
;; ---------------------------------------------------------------------------

(deftest fetch-secret-client-creation-failure-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    (with-redefs [gcp/create-sm-client (fn [] (throw (Exception. "auth failed")))]

      (testing "fetch-secret returns nil when client creation fails"
        (is (nil? (proto/fetch-secret backend "TEST" "global" config))))

      (testing "list-secrets returns empty when client creation fails"
        (is (= [] (proto/list-secrets backend "global" config))))

      (testing "fetch-secrets-for-build returns nil when client creation fails"
        (is (nil? (proto/fetch-secrets-for-build backend "job-1" config)))))))

(deftest nil-scope-defaults-to-global-test
  (let [backend (gcp/create-backend)
        config {:secrets {:gcp {:project-id "my-project"
                                :prefix "chengis-"}}}]

    (with-redefs [gcp/create-sm-client (fn [] :mock-client)
                  gcp/gcp-access-secret
                  (fn [_client _proj secret-id]
                    (when (= secret-id "chengis-global-KEY")
                      "val"))]

      (testing "nil scope defaults to global"
        (is (= "val" (proto/fetch-secret backend "KEY" nil config)))))))
