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
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

;; ---------------------------------------------------------------------------
;; Vault HTTP client
;; ---------------------------------------------------------------------------

(def ^:private http-client
  (delay (HttpClient/newHttpClient)))

(defn- vault-get
  "Perform an authenticated GET to the Vault API."
  [vault-url token path]
  (try
    (let [url (str (str/trimr vault-url "/") path)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "X-Vault-Token" token)
                      (.header "Accept" "application/json")
                      (.GET)
                      (.build))
          response (.send @http-client request (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode response))
        (json/read-str (.body response) :key-fn keyword)))
    (catch Exception e
      (log/warn "Vault GET failed for" path ":" (.getMessage e))
      nil)))

(defn- vault-list
  "Perform an authenticated LIST to the Vault API."
  [vault-url token path]
  (try
    (let [url (str (str/trimr vault-url "/") path "?list=true")
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create url))
                      (.header "X-Vault-Token" token)
                      (.header "Accept" "application/json")
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
  "Build the KV v2 data read path for a secret."
  [mount prefix scope secret-name]
  (str "/v1/" mount "/data/" prefix scope "/" secret-name))

(defn- kv2-metadata-path
  "Build the KV v2 metadata list path for a scope."
  [mount prefix scope]
  (str "/v1/" mount "/metadata/" prefix scope "/"))

(defrecord VaultSecretBackend []
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)
          path (kv2-data-path mount prefix (or scope "global") secret-name)]
      (when token
        (when-let [response (vault-get url token path)]
          ;; KV v2 wraps the data: response.data.data.value
          (get-in response [:data :data :value])))))

  (list-secrets [_this scope config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)
          path (kv2-metadata-path mount prefix (or scope "global"))]
      (when token
        (or (vault-list url token path) []))))

  (fetch-secrets-for-build [_this job-id config]
    (let [{:keys [url token mount prefix]} (resolve-vault-config config)]
      (when token
        (let [global-keys (or (vault-list url token
                                (kv2-metadata-path mount prefix "global")) [])
              job-keys    (or (vault-list url token
                                (kv2-metadata-path mount prefix job-id)) [])
              fetch-one (fn [scope key-name]
                          (when-let [resp (vault-get url token
                                           (kv2-data-path mount prefix scope key-name))]
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
