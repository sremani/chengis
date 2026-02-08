(ns chengis.plugin.builtin.local-secrets
  "Builtin SecretBackend that wraps the existing AES-256-GCM encrypted database store.
   This is the default backend when no external secret store is configured."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [chengis.db.secret-store :as secret-store]
            [taoensso.timbre :as log]))

(defrecord LocalSecretBackend [ds]
  proto/SecretBackend
  (fetch-secret [_this secret-name scope config]
    (secret-store/get-secret ds config secret-name :scope (or scope "global")))

  (list-secrets [_this scope _config]
    (secret-store/list-secret-names ds :scope (or scope "global")))

  (fetch-secrets-for-build [_this job-id config]
    (secret-store/get-secrets-for-build ds config job-id)))

(defn create-backend
  "Create a LocalSecretBackend instance. Called during system startup."
  [ds]
  (->LocalSecretBackend ds))

(defn init!
  "Plugin init — registers the plugin descriptor.
   The actual backend instance is created later with create-backend when the
   datasource is available (during server startup)."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "local-secrets" "0.8.0" "Built-in AES-256-GCM encrypted database secret store"
      :provides #{:secret-backend})))
