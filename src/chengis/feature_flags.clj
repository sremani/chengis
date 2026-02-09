(ns chengis.feature-flags
  "Feature flag resolution. Reads from the :feature-flags key in config.
   All Phase 1 governance features (policy engine, artifact checksums,
   compliance reports) are gated behind feature flags that default to false.")

(defn enabled?
  "Check if a feature flag is enabled in the config.
   Returns false when the flag is missing or nil."
  [config flag-key]
  (boolean (get-in config [:feature-flags flag-key] false)))

(defn all-flags
  "Return the full feature-flags map from config."
  [config]
  (get config :feature-flags {}))

(defn require-flag!
  "Assert that a feature flag is enabled. Throws ex-info with
   {:type :feature-disabled :flag flag-key} when the flag is off.
   Use as a guard in handlers to return 404 for disabled features."
  [config flag-key]
  (when-not (enabled? config flag-key)
    (throw (ex-info (str "Feature not enabled: " (name flag-key))
                    {:type :feature-disabled
                     :flag flag-key})))
  true)
