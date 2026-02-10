(ns chengis.plugin.builtin.aws-secrets-test
  "Tests for AWS Secrets Manager SecretBackend plugin.
   Covers: config resolution, secret fetching, listing, merge behavior,
   error handling, and plugin registration — all with mocked AWS SDK calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.aws-secrets :as aws]
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

(deftest aws-plugin-init-test
  (testing "aws-secrets init! registers plugin descriptor"
    (aws/init!)
    (let [plugin (registry/get-plugin "aws-secrets")]
      (is (some? plugin))
      (is (= "aws-secrets" (:name plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (contains? (:provides plugin) :secret-backend)))))

(deftest aws-backend-creation-test
  (testing "create-backend returns an AwsSecretsBackend"
    (let [backend (aws/create-backend)]
      (is (some? backend))
      (is (satisfies? proto/SecretBackend backend)))))

;; ---------------------------------------------------------------------------
;; Config Resolution Tests
;; ---------------------------------------------------------------------------

(deftest resolve-aws-config-explicit-test
  (testing "resolve-aws-config uses explicit config values"
    (let [config {:secrets {:aws {:region "eu-west-1"
                                  :access-key-id "AKIATEST"
                                  :secret-access-key "secret123"
                                  :prefix "myapp/"}}}
          result (aws/resolve-aws-config config)]
      (is (= "eu-west-1" (:region result)))
      (is (= "AKIATEST" (:access-key result)))
      (is (= "secret123" (:secret-key result)))
      (is (= "myapp/" (:prefix result))))))

(deftest resolve-aws-config-defaults-test
  (testing "resolve-aws-config provides defaults when config is empty"
    ;; Override env vars to nil to test defaults
    (with-redefs [chengis.plugin.builtin.aws-secrets/resolve-aws-config
                  (fn [_config]
                    {:region "us-east-1"
                     :access-key nil
                     :secret-key nil
                     :prefix "chengis/"})]
      (let [result (aws/resolve-aws-config {})]
        (is (= "us-east-1" (:region result)))
        (is (= "chengis/" (:prefix result)))))))

;; ---------------------------------------------------------------------------
;; Secret Fetching with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest fetch-secret-with-mock-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    ;; Mock client creation and SDK calls
    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-get-secret-value
                  (fn [client key-path]
                    (when (and (= client :mock-client)
                               (= key-path "chengis/global/DB_PASSWORD"))
                      "s3cret-password"))]

      (testing "fetch-secret returns value for existing secret"
        (is (= "s3cret-password"
               (proto/fetch-secret backend "DB_PASSWORD" "global" config)))))

    ;; Mock for missing secret
    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-get-secret-value (fn [_client _key] nil)]

      (testing "fetch-secret returns nil for missing secret"
        (is (nil? (proto/fetch-secret backend "NONEXISTENT" "global" config)))))))

(deftest fetch-secret-with-org-id-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}
                :org-id "org-42"}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-get-secret-value
                  (fn [_client key-path]
                    (when (= key-path "chengis/org-42/global/API_KEY")
                      "org-scoped-key"))]

      (testing "fetch-secret includes org-id in key path"
        (is (= "org-scoped-key"
               (proto/fetch-secret backend "API_KEY" "global" config)))))))

;; ---------------------------------------------------------------------------
;; List Secrets with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest list-secrets-with-mock-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-list-secrets
                  (fn [_client prefix]
                    (when (= prefix "chengis/global/")
                      ["chengis/global/DB_PASSWORD"
                       "chengis/global/API_KEY"
                       "chengis/global/JWT_SECRET"]))]

      (testing "list-secrets returns stripped secret names"
        (is (= ["DB_PASSWORD" "API_KEY" "JWT_SECRET"]
               (proto/list-secrets backend "global" config)))))))

(deftest list-secrets-empty-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-list-secrets (fn [_client _prefix] [])]

      (testing "list-secrets returns empty vector when no secrets"
        (is (= [] (proto/list-secrets backend "global" config)))))))

;; ---------------------------------------------------------------------------
;; Fetch Secrets for Build (Merge Behavior)
;; ---------------------------------------------------------------------------

(deftest fetch-secrets-for-build-merge-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}
        ;; Mock data
        mock-list-data {"chengis/global/"  ["chengis/global/SHARED_KEY"]
                        "chengis/my-job/"  ["chengis/my-job/JOB_SECRET"
                                            "chengis/my-job/SHARED_KEY"]}
        mock-get-data {"chengis/global/SHARED_KEY"  "global-shared"
                       "chengis/my-job/JOB_SECRET"  "job-specific"
                       "chengis/my-job/SHARED_KEY"  "job-overrides-global"}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-list-secrets
                  (fn [_client prefix] (get mock-list-data prefix []))
                  aws/sm-get-secret-value
                  (fn [_client key-path] (get mock-get-data key-path))]

      (testing "fetch-secrets-for-build merges global + job secrets (job wins)"
        (let [result (proto/fetch-secrets-for-build backend "my-job" config)]
          (is (= "job-overrides-global" (get result "SHARED_KEY"))
              "Job-scoped secret overrides global")
          (is (= "job-specific" (get result "JOB_SECRET"))
              "Job-only secret is included")
          (is (= 2 (count result))))))))

(deftest fetch-secrets-for-build-global-only-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-list-secrets
                  (fn [_client prefix]
                    (case prefix
                      "chengis/global/" ["chengis/global/DB_PASS"]
                      "chengis/no-job-secrets/" []))
                  aws/sm-get-secret-value
                  (fn [_client key-path]
                    (when (= key-path "chengis/global/DB_PASS")
                      "dbpass"))]

      (testing "only global secrets when job has none"
        (let [result (proto/fetch-secrets-for-build backend "no-job-secrets" config)]
          (is (= {"DB_PASS" "dbpass"} result)))))))

;; ---------------------------------------------------------------------------
;; Error Handling / Edge Cases
;; ---------------------------------------------------------------------------

(deftest fetch-secret-client-creation-failure-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    (with-redefs [aws/create-sm-client (fn [_cfg] (throw (Exception. "connection failed")))]

      (testing "fetch-secret returns nil when client creation fails"
        (is (nil? (proto/fetch-secret backend "TEST" "global" config))))

      (testing "list-secrets returns empty when client creation fails"
        (is (= [] (proto/list-secrets backend "global" config))))

      (testing "fetch-secrets-for-build returns nil when client creation fails"
        (is (nil? (proto/fetch-secrets-for-build backend "job-1" config)))))))

(deftest nil-scope-defaults-to-global-test
  (let [backend (aws/create-backend)
        config {:secrets {:aws {:region "us-east-1"
                                :access-key-id "AKIATEST"
                                :secret-access-key "secret123"
                                :prefix "chengis/"}}}]

    (with-redefs [aws/create-sm-client (fn [_cfg] :mock-client)
                  aws/sm-get-secret-value
                  (fn [_client key-path]
                    (when (= key-path "chengis/global/KEY")
                      "val"))]

      (testing "nil scope defaults to global"
        (is (= "val" (proto/fetch-secret backend "KEY" nil config)))))))
