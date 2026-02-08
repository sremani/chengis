(ns chengis.plugin.builtin.vault-secrets-test
  "Tests for Vault SecretBackend plugin.
   Covers: KV v2 path construction, config resolution, HTTP error handling,
   plugin registration, and secret fetching with mocked HTTP."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.vault-secrets :as vault]
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

(deftest vault-plugin-init-test
  (testing "vault-secrets init! registers plugin descriptor"
    (vault/init!)
    (let [plugin (registry/get-plugin "vault-secrets")]
      (is (some? plugin))
      (is (= "vault-secrets" (:name plugin)))
      (is (= "0.8.0" (:version plugin)))
      (is (contains? (:provides plugin) :secret-backend)))))

(deftest vault-backend-creation-test
  (testing "create-backend returns a VaultSecretBackend"
    (let [backend (vault/create-backend)]
      (is (some? backend))
      (is (satisfies? proto/SecretBackend backend)))))

;; ---------------------------------------------------------------------------
;; Secret Fetching with Mocked HTTP
;; ---------------------------------------------------------------------------

(deftest fetch-secret-no-token-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token nil ;; No token
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    (testing "fetch-secret returns nil when no Vault token configured"
      (is (nil? (proto/fetch-secret backend "DB_PASSWORD" "global" config))))

    (testing "list-secrets returns empty when no Vault token configured"
      (is (empty? (proto/list-secrets backend "global" config))))

    (testing "fetch-secrets-for-build returns nil when no Vault token configured"
      (is (nil? (proto/fetch-secrets-for-build backend "job-1" config))))))

(deftest fetch-secret-with-mock-http-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token "test-vault-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    ;; Mock vault-get to return KV v2 response format
    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-get
                  (fn [url token path]
                    (when (and (= token "test-vault-token")
                               (= path "/v1/secret/data/chengis/global/DB_PASSWORD"))
                      {:data {:data {:value "s3cret"} :metadata {:version 1}}}))]

      (testing "fetch-secret extracts value from KV v2 response"
        (is (= "s3cret" (proto/fetch-secret backend "DB_PASSWORD" "global" config)))))

    ;; Mock for unknown secret
    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-get
                  (fn [_url _token _path] nil)]

      (testing "fetch-secret returns nil for missing secret"
        (is (nil? (proto/fetch-secret backend "NONEXISTENT" "global" config)))))))

(deftest list-secrets-with-mock-http-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token "test-vault-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-list
                  (fn [_url _token path]
                    (when (= path "/v1/secret/metadata/chengis/global/")
                      ["DB_PASSWORD" "API_KEY" "JWT_SECRET"]))]

      (testing "list-secrets returns key names from Vault"
        (is (= ["DB_PASSWORD" "API_KEY" "JWT_SECRET"]
               (proto/list-secrets backend "global" config)))))))

(deftest fetch-secrets-for-build-merge-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token "test-vault-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}
        ;; Set up mock responses
        mock-list-responses {"/v1/secret/metadata/chengis/global/" ["SHARED_KEY"]
                             "/v1/secret/metadata/chengis/my-job/" ["JOB_SECRET" "SHARED_KEY"]}
        mock-get-responses {"/v1/secret/data/chengis/global/SHARED_KEY"
                            {:data {:data {:value "global-shared"}}}
                            "/v1/secret/data/chengis/my-job/JOB_SECRET"
                            {:data {:data {:value "job-specific"}}}
                            "/v1/secret/data/chengis/my-job/SHARED_KEY"
                            {:data {:data {:value "job-overrides-global"}}}
                            }]

    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-list
                  (fn [_url _token path] (get mock-list-responses path))
                  chengis.plugin.builtin.vault-secrets/vault-get
                  (fn [_url _token path] (get mock-get-responses path))]

      (testing "fetch-secrets-for-build merges global + job secrets (job wins)"
        (let [result (proto/fetch-secrets-for-build backend "my-job" config)]
          (is (= "job-overrides-global" (get result "SHARED_KEY"))
              "Job-scoped secret overrides global")
          (is (= "job-specific" (get result "JOB_SECRET"))
              "Job-only secret is included")
          (is (= 2 (count result))))))))

(deftest fetch-secrets-for-build-global-only-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token "test-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-list
                  (fn [_url _token path]
                    (case path
                      "/v1/secret/metadata/chengis/global/" ["DB_PASS"]
                      "/v1/secret/metadata/chengis/no-job-secrets/" nil))
                  chengis.plugin.builtin.vault-secrets/vault-get
                  (fn [_url _token path]
                    (when (= path "/v1/secret/data/chengis/global/DB_PASS")
                      {:data {:data {:value "dbpass"}}}))]

      (testing "only global secrets when job has none"
        (let [result (proto/fetch-secrets-for-build backend "no-job-secrets" config)]
          (is (= {"DB_PASS" "dbpass"} result)))))))

;; ---------------------------------------------------------------------------
;; HTTP Error Handling Tests
;; ---------------------------------------------------------------------------

(deftest vault-http-connection-refused-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://127.0.0.1:1"  ;; Port 1 = connection refused
                                  :token "test-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    (testing "fetch-secret returns nil on connection refused"
      (is (nil? (proto/fetch-secret backend "TEST" "global" config))))

    (testing "list-secrets returns empty on connection refused"
      (is (empty? (proto/list-secrets backend "global" config))))))

;; ---------------------------------------------------------------------------
;; Default Scope Handling
;; ---------------------------------------------------------------------------

(deftest default-scope-test
  (let [backend (vault/create-backend)
        config {:secrets {:vault {:url "http://vault.test:8200"
                                  :token "test-token"
                                  :mount "secret"
                                  :prefix "chengis/"}}}]

    (with-redefs [chengis.plugin.builtin.vault-secrets/vault-get
                  (fn [_url _token path]
                    (when (= path "/v1/secret/data/chengis/global/KEY")
                      {:data {:data {:value "val"}}}))]

      (testing "nil scope defaults to global"
        (is (= "val" (proto/fetch-secret backend "KEY" nil config)))))))
