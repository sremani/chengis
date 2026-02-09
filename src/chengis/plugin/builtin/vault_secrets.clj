(ns chengis.plugin.builtin.vault-secrets
  "SecretBackend plugin for HashiCorp Vault integration.
   Uses the Vault KV v2 HTTP API to fetch secrets.

   Configuration:
     :secrets {:backend \"vault\"
               :vault {:url \"http://127.0.0.1:8200\"
                       :token \"hvs.xxx\"          ;; or VAULT_TOKEN env var
                       :mount \"secret\"            ;; KV v2 mount path
                       :prefix \"chengis/\"}}       ;; key prefix within mount"
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

;; ---------------------------------------------------------------------------
;; Vault HTTP client
;; ---------------------------------------------------------------------------

(def ^:private http-client
  "Shared HTTP client with connection timeout for Vault API calls."
  (delay
    (-> (HttpClient/newBuilder)
        (.connectTimeout (java.time.Duration/ofSeconds 10))
        (.build))))

(defn- vault-get
  "Perform an authenticated GET to the Vault API (with 10s request timeout)."
  [vault-url token path]
  (try
    (let [url (str (str/replace vault-url #"/+$" "") path)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "X-Vault-Token" token)
                      (.header "Accept" "application/json")
                      (.timeout (java.time.Duration/ofSeconds 10))
                      (.GET)
                      (.build))
          response (.send @http-client request (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode response))
        (json/read-str (.body response) :key-fn keyword)))
    (catch Exception e
      (log/warn "Vault GET failed for" path ":" (.getMessage e))
      nil)))

(defn- vault-list
  "Perform an authenticated LIST to the Vault API (with 10s request timeout)."
  [vault-url token path]
  (try
    (let [url (str (str/replace vault-url #"/+$" "") path "?list=true")
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "X-Vault-Token" token)
                      (.header "Accept" "application/json")
                      (.timeout (java.time.Duration/ofSeconds 10))
                      (.method "LIST" (HttpRequest$BodyPublishers/noBody))
                      (.build))
          response (.send @http-client request (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode response))
        (let [body (json/read-str (.body response) :key-fn keyword)]
          (get-in body [:data :keys]))))
    (catch Exception e
      (log/warn "Vault LIST failed for" path ":" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Vault SecretBackend implementation
;; ---------------------------------------------------------------------------

(defn- resolve-vault-config
  "Resolve Vault configuration from config map, with env var fallbacks."
  [config]
  (let [vault-cfg (get-in config [:secrets :vault] {})]
    {:url   (or (:url vault-cfg)
                (System/getenv "VAULT_ADDR")
                "http://127.0.0.1:8200")
     :token (or (:token vault-cfg)
                (System/getenv "VAULT_TOKEN"))
     :mount (or (:mount vault-cfg) "secret")
     :prefix (or (:prefix vault-cfg) "chengis/")}))

(defn- kv2-data-path
  "Build the KV v2 data read path for a secret.
   When org-id is provided, scopes path under the org: chengis/<org-id>/<scope>/<name>"
  [mount prefix scope secret-name & {:keys [org-id]}]
  (let [org-segment (if org-id (str org-id "/") "")]
    (str "/v1/" mount "/data/" prefix org-segment scope "/" secret-name)))

(defn- kv2-metadata-path
  "Build the KV v2 metadata list path for a scope.
   When org-id is provided, scopes path under the org: chengis/<org-id>/<scope>/"
  [mount prefix scope & {:keys [org-id]}]
  (let [org-segment (if org-id (str org-id "/") "")]
    (str "/v1/" mount "/metadata/" prefix org-segment scope "/")))

(defrecord VaultSecretBackend []
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)
          org-id (:org-id config)
          path (kv2-data-path mount prefix (or scope "global") secret-name :org-id org-id)]
      (when token
        (when-let [response (vault-get url token path)]
          ;; KV v2 wraps the data: response.data.data.value
          (get-in response [:data :data :value])))))

  (list-secrets [_this scope config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)
          org-id (:org-id config)
          path (kv2-metadata-path mount prefix (or scope "global") :org-id org-id)]
      (when token
        (or (vault-list url token path) []))))

  (fetch-secrets-for-build [_this job-id config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)
          org-id (:org-id config)]
      (when token
        (let [global-keys (or (vault-list url token
                                (kv2-metadata-path mount prefix "global" :org-id org-id)) [])
              job-keys    (or (vault-list url token
                                (kv2-metadata-path mount prefix job-id :org-id org-id)) [])
              fetch-one (fn [scope key-name]
                          (when-let [resp (vault-get url token
                                           (kv2-data-path mount prefix scope key-name :org-id org-id))]
                            (get-in resp [:data :data :value])))
              ;; Global secrets first, then job-scoped overrides
              global-map (reduce (fn [m k]
                                   (if-let [v (fetch-one "global" k)]
                                     (assoc m k v) m))
                                 {} global-keys)
              job-map    (reduce (fn [m k]
                                   (if-let [v (fetch-one job-id k)]
                                     (assoc m k v) m))
                                 {} job-keys)]
          (merge global-map job-map))))))

(defn create-backend
  "Create a VaultSecretBackend instance."
  []
  (->VaultSecretBackend))

(defn init!
  "Plugin init — registers the plugin descriptor."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "vault-secrets" "0.8.0" "HashiCorp Vault KV v2 secret backend"
      :provides #{:secret-backend})))
