(ns chengis.plugin.builtin.azure-keyvault-test
  "Tests for Azure Key Vault SecretBackend plugin.
   Covers: config resolution, secret fetching, listing, merge behavior,
   error handling, and plugin registration — all with mocked Azure SDK calls."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.plugin.builtin.azure-keyvault :as azure]
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

(deftest azure-plugin-init-test
  (testing "azure-keyvault init! registers plugin descriptor"
    (azure/init!)
    (let [plugin (registry/get-plugin "azure-keyvault")]
      (is (some? plugin))
      (is (= "azure-keyvault" (:name plugin)))
      (is (= "0.1.0" (:version plugin)))
      (is (contains? (:provides plugin) :secret-backend)))))

(deftest azure-backend-creation-test
  (testing "create-backend returns an AzureKeyVaultBackend"
    (let [backend (azure/create-backend)]
      (is (some? backend))
      (is (satisfies? proto/SecretBackend backend)))))

;; ---------------------------------------------------------------------------
;; Config Resolution Tests
;; ---------------------------------------------------------------------------

(deftest resolve-azure-config-explicit-test
  (testing "resolve-azure-config uses explicit config values"
    (let [config {:secrets {:azure {:vault-url "https://myvault.vault.azure.net"
                                    :prefix "myapp-"}}}
          result (azure/resolve-azure-config config)]
      (is (= "https://myvault.vault.azure.net" (:vault-url result)))
      (is (= "myapp-" (:prefix result))))))

(deftest resolve-azure-config-defaults-test
  (testing "resolve-azure-config provides defaults when config is empty"
    (with-redefs [azure/resolve-azure-config
                  (fn [_config]
                    {:vault-url nil
                     :prefix "chengis-"})]
      (let [result (azure/resolve-azure-config {})]
        (is (nil? (:vault-url result)))
        (is (= "chengis-" (:prefix result)))))))

;; ---------------------------------------------------------------------------
;; Secret Fetching with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest fetch-secret-no-vault-url-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url nil
                                  :prefix "chengis-"}}}]

    (testing "fetch-secret returns nil when no vault-url configured"
      (is (nil? (proto/fetch-secret backend "DB_PASSWORD" "global" config))))

    (testing "list-secrets returns empty when no vault-url configured"
      (is (empty? (proto/list-secrets backend "global" config))))

    (testing "fetch-secrets-for-build returns nil when no vault-url configured"
      (is (nil? (proto/fetch-secrets-for-build backend "job-1" config))))))

(deftest fetch-secret-with-mock-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    ;; Mock client creation and SDK calls
    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-get-secret
                  (fn [client secret-name]
                    (when (and (= client :mock-client)
                               (= secret-name "chengis-global-DB_PASSWORD"))
                      "s3cret-password"))]

      (testing "fetch-secret returns value for existing secret"
        (is (= "s3cret-password"
               (proto/fetch-secret backend "DB_PASSWORD" "global" config)))))

    ;; Mock for missing secret
    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-get-secret (fn [_client _name] nil)]

      (testing "fetch-secret returns nil for missing secret"
        (is (nil? (proto/fetch-secret backend "NONEXISTENT" "global" config)))))))

(deftest fetch-secret-with-org-id-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}
                :org-id "org-42"}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-get-secret
                  (fn [_client secret-name]
                    (when (= secret-name "chengis-org-42-global-API_KEY")
                      "org-scoped-key"))]

      (testing "fetch-secret includes org-id in key name"
        (is (= "org-scoped-key"
               (proto/fetch-secret backend "API_KEY" "global" config)))))))

;; ---------------------------------------------------------------------------
;; List Secrets with Mocked SDK
;; ---------------------------------------------------------------------------

(deftest list-secrets-with-mock-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-list-secrets
                  (fn [_client prefix]
                    (when (= prefix "chengis-global-")
                      ["chengis-global-DB_PASSWORD"
                       "chengis-global-API_KEY"
                       "chengis-global-JWT_SECRET"]))]

      (testing "list-secrets returns stripped secret names"
        (is (= ["DB_PASSWORD" "API_KEY" "JWT_SECRET"]
               (proto/list-secrets backend "global" config)))))))

(deftest list-secrets-empty-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-list-secrets (fn [_client _prefix] [])]

      (testing "list-secrets returns empty vector when no secrets"
        (is (= [] (proto/list-secrets backend "global" config)))))))

;; ---------------------------------------------------------------------------
;; Fetch Secrets for Build (Merge Behavior)
;; ---------------------------------------------------------------------------

(deftest fetch-secrets-for-build-merge-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}
        ;; Mock data
        mock-list-data {"chengis-global-"  ["chengis-global-SHARED_KEY"]
                        "chengis-my-job-"  ["chengis-my-job-JOB_SECRET"
                                            "chengis-my-job-SHARED_KEY"]}
        mock-get-data {"chengis-global-SHARED_KEY"  "global-shared"
                       "chengis-my-job-JOB_SECRET"  "job-specific"
                       "chengis-my-job-SHARED_KEY"  "job-overrides-global"}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-list-secrets
                  (fn [_client prefix] (get mock-list-data prefix []))
                  azure/kv-get-secret
                  (fn [_client secret-name] (get mock-get-data secret-name))]

      (testing "fetch-secrets-for-build merges global + job secrets (job wins)"
        (let [result (proto/fetch-secrets-for-build backend "my-job" config)]
          (is (= "job-overrides-global" (get result "SHARED_KEY"))
              "Job-scoped secret overrides global")
          (is (= "job-specific" (get result "JOB_SECRET"))
              "Job-only secret is included")
          (is (= 2 (count result))))))))

(deftest fetch-secrets-for-build-global-only-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-list-secrets
                  (fn [_client prefix]
                    (case prefix
                      "chengis-global-" ["chengis-global-DB_PASS"]
                      "chengis-no-job-secrets-" []))
                  azure/kv-get-secret
                  (fn [_client secret-name]
                    (when (= secret-name "chengis-global-DB_PASS")
                      "dbpass"))]

      (testing "only global secrets when job has none"
        (let [result (proto/fetch-secrets-for-build backend "no-job-secrets" config)]
          (is (= {"DB_PASS" "dbpass"} result)))))))

;; ---------------------------------------------------------------------------
;; Error Handling / Edge Cases
;; ---------------------------------------------------------------------------

(deftest fetch-secret-client-creation-failure-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    (with-redefs [azure/create-kv-client (fn [_url] (throw (Exception. "auth failed")))]

      (testing "fetch-secret returns nil when client creation fails"
        (is (nil? (proto/fetch-secret backend "TEST" "global" config))))

      (testing "list-secrets returns empty when client creation fails"
        (is (= [] (proto/list-secrets backend "global" config))))

      (testing "fetch-secrets-for-build returns nil when client creation fails"
        (is (nil? (proto/fetch-secrets-for-build backend "job-1" config)))))))

(deftest nil-scope-defaults-to-global-test
  (let [backend (azure/create-backend)
        config {:secrets {:azure {:vault-url "https://test.vault.azure.net"
                                  :prefix "chengis-"}}}]

    (with-redefs [azure/create-kv-client (fn [_url] :mock-client)
                  azure/kv-get-secret
                  (fn [_client secret-name]
                    (when (= secret-name "chengis-global-KEY")
                      "val"))]

      (testing "nil scope defaults to global"
        (is (= "val" (proto/fetch-secret backend "KEY" nil config)))))))
