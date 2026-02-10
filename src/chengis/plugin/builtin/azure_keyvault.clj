(ns chengis.plugin.builtin.azure-keyvault
  "SecretBackend plugin for Azure Key Vault.
   Uses the Azure SDK to fetch secrets.

   Configuration:
     :secrets {:backend \"azure-kv\"
               :azure {:vault-url nil        ;; e.g., \"https://myvault.vault.azure.net\"
                                             ;; or AZURE_KEYVAULT_URL env var
                       :prefix \"chengis-\"}} ;; secret name prefix

   Authentication:
     Uses DefaultAzureCredential (supports managed identity, environment variables,
     Azure CLI, IntelliJ, VS Code, etc.)."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [com.azure.security.keyvault.secrets SecretClient SecretClientBuilder]
           [com.azure.security.keyvault.secrets.models KeyVaultSecret SecretProperties]
           [com.azure.identity DefaultAzureCredentialBuilder]
           [com.azure.core.exception ResourceNotFoundException]))

;; ---------------------------------------------------------------------------
;; Azure Configuration
;; ---------------------------------------------------------------------------

(defn resolve-azure-config
  "Resolve Azure configuration from config map, with env var fallbacks."
  [config]
  (let [azure-cfg (get-in config [:secrets :azure] {})]
    {:vault-url (or (:vault-url azure-cfg)
                    (System/getenv "AZURE_KEYVAULT_URL"))
     :prefix    (or (:prefix azure-cfg) "chengis-")}))

;; ---------------------------------------------------------------------------
;; Secret Key Naming
;; ---------------------------------------------------------------------------

(defn- secret-key-name
  "Build the Azure Key Vault secret name.
   Azure secret names use hyphens (alphanumeric + hyphens only, no slashes).
   Format: {prefix}{org-id}-{scope}-{secret-name}
   When org-id is nil, omits the org segment: {prefix}{scope}-{secret-name}"
  [prefix scope secret-name org-id]
  (let [org-segment (if org-id (str org-id "-") "")]
    (str prefix org-segment (or scope "global") "-" secret-name)))

(defn- scope-name-prefix
  "Build the key prefix for filtering secrets under a scope.
   Format: {prefix}{org-id}-{scope}-
   When org-id is nil, omits the org segment: {prefix}{scope}-"
  [prefix scope org-id]
  (let [org-segment (if org-id (str org-id "-") "")]
    (str prefix org-segment (or scope "global") "-")))

;; ---------------------------------------------------------------------------
;; SDK Wrapper Functions (mockable in tests)
;; ---------------------------------------------------------------------------

(defn create-kv-client
  "Create an Azure SecretClient with DefaultAzureCredential.
   The vault-url should be like 'https://myvault.vault.azure.net'."
  [vault-url]
  (-> (SecretClientBuilder.)
      (.vaultUrl vault-url)
      (.credential (.build (DefaultAzureCredentialBuilder.)))
      (.buildClient)))

(defn kv-get-secret
  "Fetch a single secret value from Azure Key Vault.
   Returns the secret string value, or nil if not found."
  [^SecretClient client secret-name]
  (try
    (let [^KeyVaultSecret secret (.getSecret client secret-name)]
      (.getValue secret))
    (catch ResourceNotFoundException _e
      (log/debug "Azure Key Vault secret not found:" secret-name)
      nil)
    (catch Exception e
      (log/warn "Azure Key Vault GET failed for" secret-name ":" (.getMessage e))
      nil)))

(defn kv-list-secrets
  "List secret names from Azure Key Vault matching the given name prefix.
   Returns a vector of secret name strings."
  [^SecretClient client name-prefix]
  (try
    (let [all-secrets (.listPropertiesOfSecrets client)]
      (vec
        (keep (fn [^SecretProperties props]
                (let [sname (.getName props)]
                  (when (str/starts-with? sname name-prefix)
                    sname)))
              (iterator-seq (.iterator all-secrets)))))
    (catch Exception e
      (log/warn "Azure Key Vault LIST failed for prefix" name-prefix ":" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; Azure SecretBackend Implementation
;; ---------------------------------------------------------------------------

(defrecord AzureKeyVaultBackend []
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (let [azure-cfg (resolve-azure-config config)
          vault-url (:vault-url azure-cfg)
          org-id (:org-id config)
          key-name (secret-key-name (:prefix azure-cfg) scope secret-name org-id)]
      (when vault-url
        (try
          (let [client (create-kv-client vault-url)]
            (kv-get-secret client key-name))
          (catch Exception e
            (log/warn "Failed to create Azure KV client:" (.getMessage e))
            nil)))))

  (list-secrets [_this scope config]
    (let [azure-cfg (resolve-azure-config config)
          vault-url (:vault-url azure-cfg)
          org-id (:org-id config)
          prefix (:prefix azure-cfg)
          s-prefix (scope-name-prefix prefix scope org-id)]
      (if-not vault-url
        []
        (try
          (let [client (create-kv-client vault-url)
                full-names (kv-list-secrets client s-prefix)]
            ;; Strip the scope prefix to return just the secret names
            (mapv (fn [full-name]
                    (subs full-name (count s-prefix)))
                  full-names))
          (catch Exception e
            (log/warn "Failed to create Azure KV client:" (.getMessage e))
            [])))))

  (fetch-secrets-for-build [_this job-id config]
    (let [azure-cfg (resolve-azure-config config)
          vault-url (:vault-url azure-cfg)
          org-id (:org-id config)
          prefix (:prefix azure-cfg)]
      (when vault-url
        (try
          (let [client (create-kv-client vault-url)
                global-prefix (scope-name-prefix prefix "global" org-id)
                job-prefix (scope-name-prefix prefix job-id org-id)
                global-full-names (kv-list-secrets client global-prefix)
                job-full-names (kv-list-secrets client job-prefix)
                ;; Fetch each secret value
                fetch-one (fn [sname]
                            (kv-get-secret client sname))
                ;; Build global secrets map
                global-map (reduce
                             (fn [m full-name]
                               (let [short-name (subs full-name (count global-prefix))
                                     value (fetch-one full-name)]
                                 (if value (assoc m short-name value) m)))
                             {} global-full-names)
                ;; Build job-scoped secrets map (overrides global)
                job-map (reduce
                          (fn [m full-name]
                            (let [short-name (subs full-name (count job-prefix))
                                  value (fetch-one full-name)]
                              (if value (assoc m short-name value) m)))
                          {} job-full-names)]
            (merge global-map job-map))
          (catch Exception e
            (log/warn "Failed to create Azure KV client:" (.getMessage e))
            nil))))))

(defn create-backend
  "Create an AzureKeyVaultBackend instance."
  []
  (->AzureKeyVaultBackend))

(defn init!
  "Plugin init — registers the plugin descriptor."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "azure-keyvault" "0.1.0" "Azure Key Vault secret backend"
      :provides #{:secret-backend})))
