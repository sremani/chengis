(ns chengis.plugin.builtin.aws-secrets
  "SecretBackend plugin for AWS Secrets Manager.
   Uses the AWS SDK v2 to fetch secrets.

   Configuration:
     :secrets {:backend \"aws-sm\"
               :aws {:region \"us-east-1\"
                     :access-key-id nil     ;; or AWS_ACCESS_KEY_ID env var
                     :secret-access-key nil ;; or AWS_SECRET_ACCESS_KEY env var
                     :prefix \"chengis/\"}}  ;; key prefix"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [software.amazon.awssdk.services.secretsmanager SecretsManagerClient]
           [software.amazon.awssdk.services.secretsmanager.model GetSecretValueRequest
            ListSecretsRequest ListSecretsResponse Filter FilterNameStringType
            SecretListEntry ResourceNotFoundException]
           [software.amazon.awssdk.regions Region]
           [software.amazon.awssdk.auth.credentials StaticCredentialsProvider
            AwsBasicCredentials DefaultCredentialsProvider]))

;; ---------------------------------------------------------------------------
;; AWS Configuration
;; ---------------------------------------------------------------------------

(defn resolve-aws-config
  "Resolve AWS configuration from config map, with env var fallbacks."
  [config]
  (let [aws-cfg (get-in config [:secrets :aws] {})]
    {:region     (or (:region aws-cfg)
                     (System/getenv "AWS_REGION")
                     (System/getenv "AWS_DEFAULT_REGION")
                     "us-east-1")
     :access-key (or (:access-key-id aws-cfg)
                     (System/getenv "AWS_ACCESS_KEY_ID"))
     :secret-key (or (:secret-access-key aws-cfg)
                     (System/getenv "AWS_SECRET_ACCESS_KEY"))
     :prefix     (or (:prefix aws-cfg) "chengis/")}))

;; ---------------------------------------------------------------------------
;; AWS Secrets Manager Client
;; ---------------------------------------------------------------------------

(defn create-sm-client
  "Create an AWS SecretsManagerClient with the given configuration.
   Uses explicit credentials if provided, otherwise falls back to
   DefaultCredentialsProvider (env vars, instance profile, etc.)."
  [{:keys [region access-key secret-key]}]
  (let [region-obj (Region/of region)
        creds-provider (if (and access-key secret-key)
                         (StaticCredentialsProvider/create
                           (AwsBasicCredentials/create access-key secret-key))
                         (DefaultCredentialsProvider/create))]
    (-> (SecretsManagerClient/builder)
        (.region region-obj)
        (.credentialsProvider creds-provider)
        (.build))))

(defn- close-client
  "Safely close a client if it implements java.lang.AutoCloseable."
  [client]
  (when (instance? java.lang.AutoCloseable client)
    (.close ^java.lang.AutoCloseable client)))

;; ---------------------------------------------------------------------------
;; Secret Key Naming
;; ---------------------------------------------------------------------------

(defn- secret-key-path
  "Build the full secret key path in AWS Secrets Manager.
   Format: {prefix}{org-id}/{scope}/{secret-name}
   When org-id is nil, omits the org segment: {prefix}{scope}/{secret-name}"
  [prefix scope secret-name org-id]
  (let [org-segment (if org-id (str org-id "/") "")]
    (str prefix org-segment (or scope "global") "/" secret-name)))

(defn- scope-prefix
  "Build the key prefix for listing secrets under a scope.
   Format: {prefix}{org-id}/{scope}/
   When org-id is nil, omits the org segment: {prefix}{scope}/"
  [prefix scope org-id]
  (let [org-segment (if org-id (str org-id "/") "")]
    (str prefix org-segment (or scope "global") "/")))

;; ---------------------------------------------------------------------------
;; SDK Wrapper Functions (mockable in tests)
;; ---------------------------------------------------------------------------

(defn sm-get-secret-value
  "Fetch a single secret value from AWS Secrets Manager.
   Returns the secret string value, or nil if not found."
  [^SecretsManagerClient client secret-id]
  (try
    (let [request (-> (GetSecretValueRequest/builder)
                      (.secretId secret-id)
                      (.build))
          response (.getSecretValue client request)]
      (.secretString response))
    (catch ResourceNotFoundException _e
      (log/debug "AWS secret not found:" secret-id)
      nil)
    (catch Exception e
      (log/warn "AWS Secrets Manager GET failed for" secret-id ":" (.getMessage e))
      nil)))

(defn sm-list-secrets
  "List secrets from AWS Secrets Manager matching the given name prefix.
   Returns a vector of full secret name strings."
  [^SecretsManagerClient client name-prefix]
  (try
    (let [filter-obj (-> (Filter/builder)
                         (.key FilterNameStringType/NAME)
                         (.values [name-prefix])
                         (.build))
          request (-> (ListSecretsRequest/builder)
                      (.filters [filter-obj])
                      (.build))
          ^ListSecretsResponse response (.listSecrets client request)]
      (mapv #(.name ^SecretListEntry %) (.secretList response)))
    (catch Exception e
      (log/warn "AWS Secrets Manager LIST failed for prefix" name-prefix ":" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; AWS SecretBackend Implementation
;; ---------------------------------------------------------------------------

(defrecord AwsSecretsBackend []
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (let [aws-cfg (resolve-aws-config config)
          org-id (:org-id config)
          key-path (secret-key-path (:prefix aws-cfg) scope secret-name org-id)]
      (try
        (let [client (create-sm-client aws-cfg)]
          (try
            (sm-get-secret-value client key-path)
            (finally
              (close-client client))))
        (catch Exception e
          (log/warn "Failed to create AWS SM client:" (.getMessage e))
          nil))))

  (list-secrets [_this scope config]
    (let [aws-cfg (resolve-aws-config config)
          org-id (:org-id config)
          prefix (:prefix aws-cfg)
          s-prefix (scope-prefix prefix scope org-id)]
      (try
        (let [client (create-sm-client aws-cfg)]
          (try
            (let [full-names (sm-list-secrets client s-prefix)]
              ;; Strip the scope prefix to return just the secret names
              (mapv (fn [full-name]
                      (subs full-name (count s-prefix)))
                    (filterv #(str/starts-with? % s-prefix) full-names)))
            (finally
              (close-client client))))
        (catch Exception e
          (log/warn "Failed to create AWS SM client:" (.getMessage e))
          []))))

  (fetch-secrets-for-build [_this job-id config]
    (let [aws-cfg (resolve-aws-config config)
          org-id (:org-id config)
          prefix (:prefix aws-cfg)]
      (try
        (let [client (create-sm-client aws-cfg)]
          (try
            (let [global-prefix (scope-prefix prefix "global" org-id)
                  job-prefix (scope-prefix prefix job-id org-id)
                  global-full-names (sm-list-secrets client global-prefix)
                  job-full-names (sm-list-secrets client job-prefix)
                  ;; Fetch each secret value
                  fetch-one (fn [secret-id]
                              (sm-get-secret-value client secret-id))
                  ;; Build global secrets map
                  global-map (reduce
                               (fn [m full-name]
                                 (if (str/starts-with? full-name global-prefix)
                                   (let [short-name (subs full-name (count global-prefix))
                                         value (fetch-one full-name)]
                                     (if value (assoc m short-name value) m))
                                   m))
                               {} global-full-names)
                  ;; Build job-scoped secrets map (overrides global)
                  job-map (reduce
                            (fn [m full-name]
                              (if (str/starts-with? full-name job-prefix)
                                (let [short-name (subs full-name (count job-prefix))
                                      value (fetch-one full-name)]
                                  (if value (assoc m short-name value) m))
                                m))
                            {} job-full-names)]
              (merge global-map job-map))
            (finally
              (close-client client))))
        (catch Exception e
          (log/warn "Failed to create AWS SM client:" (.getMessage e))
          nil)))))

(defn create-backend
  "Create an AwsSecretsBackend instance."
  []
  (->AwsSecretsBackend))

(defn init!
  "Plugin init — registers the plugin descriptor."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "aws-secrets" "0.1.0" "AWS Secrets Manager secret backend"
      :provides #{:secret-backend})))
