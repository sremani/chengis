(ns chengis.plugin.builtin.gcp-secrets
  "SecretBackend plugin for Google Cloud Secret Manager.
   Uses the Google Cloud SDK to fetch secrets.

   Configuration:
     :secrets {:backend \"gcp-sm\"
               :gcp {:project-id nil       ;; GCP project ID (or GOOGLE_CLOUD_PROJECT env var)
                     :prefix \"chengis-\"}} ;; secret name prefix

   Authentication:
     Uses Application Default Credentials (GOOGLE_APPLICATION_CREDENTIALS env var,
     GCE metadata server, or gcloud auth application-default login)."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [com.google.cloud.secretmanager.v1 SecretManagerServiceClient
            SecretVersionName AccessSecretVersionResponse
            ListSecretsRequest SecretName ProjectName]
           [com.google.api.gax.rpc NotFoundException]))

;; ---------------------------------------------------------------------------
;; GCP Configuration
;; ---------------------------------------------------------------------------

(defn resolve-gcp-config
  "Resolve GCP configuration from config map, with env var fallbacks."
  [config]
  (let [gcp-cfg (get-in config [:secrets :gcp] {})]
    {:project-id (or (:project-id gcp-cfg)
                     (System/getenv "GOOGLE_CLOUD_PROJECT")
                     (System/getenv "GCLOUD_PROJECT"))
     :prefix     (or (:prefix gcp-cfg) "chengis-")}))

;; ---------------------------------------------------------------------------
;; Secret Key Naming
;; ---------------------------------------------------------------------------

(defn- secret-key-name
  "Build the GCP secret name.
   GCP secret names cannot contain '/', so we use hyphens as separators.
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

(defn- close-client
  "Safely close a client if it implements java.lang.AutoCloseable."
  [client]
  (when (instance? java.lang.AutoCloseable client)
    (.close ^java.lang.AutoCloseable client)))

;; ---------------------------------------------------------------------------
;; SDK Wrapper Functions (mockable in tests)
;; ---------------------------------------------------------------------------

(defn create-sm-client
  "Create a GCP SecretManagerServiceClient.
   Uses Application Default Credentials automatically."
  []
  (SecretManagerServiceClient/create))

(defn gcp-access-secret
  "Access the latest version of a secret from GCP Secret Manager.
   Returns the secret string value, or nil if not found."
  [^SecretManagerServiceClient client project-id secret-id]
  (try
    (let [version-name (SecretVersionName/of project-id secret-id "latest")
          ^AccessSecretVersionResponse response (.accessSecretVersion client version-name)]
      (-> response .getPayload .getData .toStringUtf8))
    (catch NotFoundException _e
      (log/debug "GCP secret not found:" secret-id)
      nil)
    (catch Exception e
      (log/warn "GCP Secret Manager access failed for" secret-id ":" (.getMessage e))
      nil)))

(defn gcp-list-secrets
  "List secrets from GCP Secret Manager matching the given name prefix.
   Returns a vector of secret name strings (just the short name, not the full resource path)."
  [^SecretManagerServiceClient client project-id name-prefix]
  (try
    (let [parent (str "projects/" project-id)
          request (-> (ListSecretsRequest/newBuilder)
                      (.setParent parent)
                      (.build))
          response (.listSecrets client request)
          all-secrets (iterator-seq (.iterateAll response))]
      ;; Filter by prefix and extract just the secret name from the resource path
      ;; Resource path format: projects/{project}/secrets/{name}
      (vec
        (keep (fn [secret]
                (let [resource-name (.getName secret)
                      ;; Extract the short name from projects/{proj}/secrets/{name}
                      short-name (last (str/split resource-name #"/"))]
                  (when (str/starts-with? short-name name-prefix)
                    short-name)))
              all-secrets)))
    (catch Exception e
      (log/warn "GCP Secret Manager LIST failed for prefix" name-prefix ":" (.getMessage e))
      [])))

;; ---------------------------------------------------------------------------
;; GCP SecretBackend Implementation
;; ---------------------------------------------------------------------------

(defrecord GcpSecretsBackend []
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (let [gcp-cfg (resolve-gcp-config config)
          project-id (:project-id gcp-cfg)
          org-id (:org-id config)
          key-name (secret-key-name (:prefix gcp-cfg) scope secret-name org-id)]
      (when project-id
        (try
          (let [client (create-sm-client)]
            (try
              (gcp-access-secret client project-id key-name)
              (finally
                (close-client client))))
          (catch Exception e
            (log/warn "Failed to create GCP SM client:" (.getMessage e))
            nil)))))

  (list-secrets [_this scope config]
    (let [gcp-cfg (resolve-gcp-config config)
          project-id (:project-id gcp-cfg)
          org-id (:org-id config)
          prefix (:prefix gcp-cfg)
          s-prefix (scope-name-prefix prefix scope org-id)]
      (if-not project-id
        []
        (try
          (let [client (create-sm-client)]
            (try
              (let [full-names (gcp-list-secrets client project-id s-prefix)]
                ;; Strip the scope prefix to return just the secret names
                (mapv (fn [full-name]
                        (subs full-name (count s-prefix)))
                      full-names))
              (finally
                (close-client client))))
          (catch Exception e
            (log/warn "Failed to create GCP SM client:" (.getMessage e))
            [])))))

  (fetch-secrets-for-build [_this job-id config]
    (let [gcp-cfg (resolve-gcp-config config)
          project-id (:project-id gcp-cfg)
          org-id (:org-id config)
          prefix (:prefix gcp-cfg)]
      (when project-id
        (try
          (let [client (create-sm-client)]
            (try
              (let [global-prefix (scope-name-prefix prefix "global" org-id)
                    job-prefix (scope-name-prefix prefix job-id org-id)
                    global-full-names (gcp-list-secrets client project-id global-prefix)
                    job-full-names (gcp-list-secrets client project-id job-prefix)
                    ;; Fetch each secret value
                    fetch-one (fn [secret-name]
                                (gcp-access-secret client project-id secret-name))
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
              (finally
                (close-client client))))
          (catch Exception e
            (log/warn "Failed to create GCP SM client:" (.getMessage e))
            nil))))))

(defn create-backend
  "Create a GcpSecretsBackend instance."
  []
  (->GcpSecretsBackend))

(defn init!
  "Plugin init — registers the plugin descriptor."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "gcp-secrets" "0.1.0" "Google Cloud Secret Manager secret backend"
      :provides #{:secret-backend})))
