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

(def ^:private builtin-plugins
  "Builtin plugins that are always loaded."
  ['chengis.plugin.builtin.shell
   'chengis.plugin.builtin.docker
   'chengis.plugin.builtin.console-notifier
   'chengis.plugin.builtin.slack-notifier
   'chengis.plugin.builtin.email-notifier
   'chengis.plugin.builtin.local-artifacts
   'chengis.plugin.builtin.git-scm
   'chengis.plugin.builtin.yaml-format
   'chengis.plugin.builtin.github-status
   'chengis.plugin.builtin.gitlab-status
   'chengis.plugin.builtin.local-secrets
   'chengis.plugin.builtin.vault-secrets])

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
  "Load all plugins: builtins first, then external.
   Call this during system startup."
  ([]
   (load-plugins! nil))
  ([system]
   (log/info "Loading plugins...")
   ;; Load builtins
   (doseq [ns-sym builtin-plugins]
     (load-plugin-ns! ns-sym))
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
