(ns chengis.plugin.loader
  "Plugin discovery and lifecycle management.
   Loads builtin plugins and external plugins from the plugins directory."
  (:require [chengis.db.plugin-policy-store :as plugin-policy-store]
            [chengis.plugin.registry :as registry]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Builtin plugin namespaces (always loaded)
;; ---------------------------------------------------------------------------

(def ^:private core-plugins
  "Core plugins that are always loaded (essential for basic operation)."
  ['chengis.plugin.builtin.shell
   'chengis.plugin.builtin.console-notifier
   'chengis.plugin.builtin.local-artifacts
   'chengis.plugin.builtin.git-scm
   'chengis.plugin.builtin.yaml-format
   'chengis.plugin.builtin.local-secrets])

(def ^:private optional-plugins
  "Optional plugins loaded only when their corresponding feature/config is active.
   Each entry is [namespace-symbol predicate-fn] where predicate-fn takes the config map."
  [['chengis.plugin.builtin.docker
    (fn [cfg] (or (get-in cfg [:docker :host])
                  (get-in cfg [:feature-flags :docker-layer-cache])))]
   ['chengis.plugin.builtin.slack-notifier
    (fn [cfg] (get-in cfg [:notifications :slack :default-webhook]))]
   ['chengis.plugin.builtin.email-notifier
    (fn [cfg] (get-in cfg [:notifications :email :host]))]
   ['chengis.plugin.builtin.github-status
    (fn [cfg] (or (get-in cfg [:scm :github :token])
                  (get-in cfg [:feature-flags :pr-status-checks])))]
   ['chengis.plugin.builtin.gitlab-status
    (fn [cfg] (or (get-in cfg [:scm :gitlab :token])
                  (get-in cfg [:feature-flags :pr-status-checks])))]
   ['chengis.plugin.builtin.vault-secrets
    (fn [cfg] (= "vault" (get-in cfg [:secrets :backend])))]])

;; ---------------------------------------------------------------------------
;; Plugin loading
;; ---------------------------------------------------------------------------

(defn- load-plugin-ns!
  "Require a plugin namespace and call its init! function."
  [ns-sym]
  (try
    (require ns-sym)
    (when-let [init-fn (resolve (symbol (str ns-sym) "init!"))]
      (init-fn)
      (log/debug "Loaded plugin:" ns-sym))
    true
    (catch Exception e
      (log/warn "Failed to load plugin" ns-sym ":" (.getMessage e))
      false)))

(defn- load-external-plugins!
  "Load external plugins from the plugins directory.
   Each plugin is a .clj file with a namespace that has an init! function.

   When a datasource is provided, checks plugin trust policy before loading.
   Plugins without an explicit 'allowed' policy are blocked.
   When no datasource is provided (backward compat), all plugins load.

   SECURITY NOTE: External plugins execute arbitrary Clojure code.
   Only place trusted .clj files in the plugins directory."
  [plugins-dir & {:keys [ds org-id]}]
  (let [dir (clojure.java.io/file plugins-dir)]
    (when (.isDirectory dir)
      (let [plugin-files (filter #(and (.isFile %)
                                       (.endsWith (.getName %) ".clj"))
                                 (.listFiles dir))]
        (when (seq plugin-files)
          (if ds
            (log/info "Loading" (count plugin-files) "external plugin(s) from" plugins-dir
                      "with trust policy enforcement")
            (log/warn "Loading" (count plugin-files) "external plugin(s) from" plugins-dir
                      "— no trust policy enforcement (no DB). Ensure you trust all files.")))
        (doseq [f plugin-files]
          (let [plugin-name (str/replace (.getName f) #"\.clj$" "")]
            (if (and ds (not (plugin-policy-store/plugin-allowed? ds plugin-name :org-id org-id)))
              (log/warn "Blocked untrusted external plugin:" plugin-name
                        "— add to allowlist via Admin > Plugin Policies")
              (try
                (load-file (.getAbsolutePath f))
                (log/info "Loaded external plugin:" (.getName f))
                (catch Exception e
                  (log/warn "Failed to load external plugin"
                            (.getName f) ":" (.getMessage e)))))))))))

;; ---------------------------------------------------------------------------
;; Lifecycle
;; ---------------------------------------------------------------------------

(defn load-plugins!
  "Load all plugins: core builtins first, then optional builtins (based on config),
   then external. Call this during system startup.
   When no system is provided (backward compat), loads all builtins."
  ([]
   (load-plugins! nil))
  ([system]
   (log/info "Loading plugins...")
   ;; Load core builtins (always needed)
   (doseq [ns-sym core-plugins]
     (load-plugin-ns! ns-sym))
   ;; Load optional builtins based on config
   (let [cfg (get system :config)]
     (if cfg
       ;; Config-aware lazy loading
       (doseq [[ns-sym pred-fn] optional-plugins]
         (if (pred-fn cfg)
           (load-plugin-ns! ns-sym)
           (log/debug "Skipping optional plugin:" ns-sym "(not configured)")))
       ;; No config available (backward compat) — load all optional plugins
       (doseq [[ns-sym _] optional-plugins]
         (load-plugin-ns! ns-sym))))
   ;; Load external plugins from configured directory (with trust policy enforcement)
   (when-let [plugins-dir (get-in system [:config :plugins :directory])]
     (load-external-plugins! plugins-dir :ds (:db system) :org-id nil))
   (let [summary (registry/registry-summary)]
     (log/info "Plugins loaded:"
               (:plugins summary) "plugins,"
               (count (:step-executors summary)) "step executors,"
               (count (:notifiers summary)) "notifiers,"
               (count (:pipeline-formats summary)) "formats")
     summary)))

(defn stop-plugins!
  "Stop all plugins. Call this during system shutdown."
  []
  (log/info "Stopping plugins...")
  (doseq [plugin (registry/list-plugins)]
    (when-let [stop-fn (:stop-fn plugin)]
      (try
        (stop-fn)
        (catch Exception e
          (log/warn "Error stopping plugin" (:name plugin) ":" (.getMessage e)))))))
