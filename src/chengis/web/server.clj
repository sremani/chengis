(ns chengis.web.server
  (:require [org.httpkit.server :as http]
            [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.logging :as logging]
            [chengis.metrics :as metrics]
            [chengis.engine.events :as events]
            [chengis.distributed.queue-processor :as queue-processor]
            [chengis.distributed.orphan-monitor :as orphan-monitor]
            [chengis.engine.retention :as retention]
            [chengis.plugin.loader :as plugin-loader]
            [chengis.web.audit :as audit]
            [chengis.web.routes :as routes]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(declare stop!)

(defonce server-instance (atom nil))
(defonce https-instance (atom nil))
(defonce system-state (atom nil))
(defonce shutdown-hook-registered? (atom false))

(defn- redirect-handler
  "Create a handler that redirects all HTTP requests to HTTPS."
  [https-port]
  (fn [req]
    (let [host (get-in req [:headers "host"] "localhost")
          host-no-port (first (clojure.string/split host #":"))
          target-url (str "https://" host-no-port
                          (when (not= https-port 443) (str ":" https-port))
                          (:uri req)
                          (when (:query-string req) (str "?" (:query-string req))))]
      {:status 301
       :headers {"Location" target-url}
       :body ""})))

(defn validate-config!
  "Validate configuration at startup. Throws on invalid config."
  [cfg]
  (when (and (get-in cfg [:distributed :enabled])
             (str/blank? (get-in cfg [:distributed :auth-token])))
    (throw (ex-info "Distributed mode enabled but :distributed :auth-token is not set. Set CHENGIS_DISTRIBUTED_AUTH_TOKEN or disable distributed mode."
                    {:type :config-validation-error}))))

(defn start!
  "Start the Chengis web server."
  [& _args]
  (let [cfg (config/load-config)
        ;; Configure logging first (before any log calls)
        _ (logging/configure-logging! cfg)
        ;; Validate configuration before proceeding
        _ (validate-config! cfg)
        db-cfg (get cfg :database)
        db-path (get db-cfg :path)
        _ (migrate/migrate! db-cfg)
        ds (conn/create-datasource db-cfg)
        ;; Initialize metrics registry (nil when disabled — all record-* fns no-op)
        metrics-registry (when (get-in cfg [:metrics :enabled])
                           (try
                             (metrics/init-registry)
                             (catch Exception e
                               (log/error "Failed to initialize metrics registry — metrics disabled:" (.getMessage e))
                               nil)))
        ;; Set metrics registry on event bus
        _ (events/set-metrics-registry! metrics-registry)
        ;; Start audit writer
        audit-writer (when (get-in cfg [:audit :enabled] true)
                       (audit/start-audit-writer! ds cfg))
        system {:config cfg :db ds :db-path db-path
                :audit-writer audit-writer :metrics metrics-registry}
        _ (plugin-loader/load-plugins! system)
        ;; Start queue processor when distributed + queue enabled
        _ (when (and (get-in cfg [:distributed :enabled])
                     (get-in cfg [:distributed :dispatch :queue-enabled]))
            (log/info "Starting distributed build queue processor")
            (queue-processor/start-processor! system))
        ;; Start orphan monitor when distributed enabled
        _ (when (get-in cfg [:distributed :enabled])
            (log/info "Starting orphan build monitor")
            (orphan-monitor/start-monitor! system))
        ;; Start retention scheduler when enabled
        _ (when (get-in cfg [:retention :enabled])
            (log/info "Starting data retention scheduler")
            (retention/start-retention! system))
        ;; Seed admin user when auth is enabled
        _ (when (get-in cfg [:auth :enabled])
            (user-store/seed-admin! ds (get-in cfg [:auth :seed-admin-password] "admin")))
        port (get-in cfg [:server :port] 8080)
        host (get-in cfg [:server :host] "0.0.0.0")
        app (routes/app-routes system)
        ;; HTTPS configuration
        https-enabled? (get-in cfg [:https :enabled] false)
        https-port (get-in cfg [:https :port] 8443)
        keystore (get-in cfg [:https :keystore])
        keystore-pw (get-in cfg [:https :keystore-password])]

    ;; Start HTTPS server if configured
    (when (and https-enabled? keystore)
      (let [https-stop-fn (http/run-server app
                            {:port https-port
                             :ip host
                             :ssl? true
                             :ssl-port https-port
                             :keystore keystore
                             :key-password keystore-pw})]
        (reset! https-instance https-stop-fn)
        (log/info (str "HTTPS server started on https://" host ":" https-port))))

    ;; Start HTTP server
    (let [http-handler (if (and https-enabled? (get-in cfg [:https :redirect-http] true))
                         (redirect-handler https-port)
                         app)
          stop-fn (http/run-server http-handler {:port port :ip host})]
      (reset! server-instance stop-fn)
      (if (and https-enabled? keystore)
        (log/info (str "HTTP→HTTPS redirect on http://" host ":" port))
        (log/info (str "Chengis web UI started on http://" host ":" port))))

    ;; Store system state for shutdown
    (reset! system-state {:ds ds :audit-writer audit-writer :config cfg})

    ;; Register JVM shutdown hook (once)
    (when (compare-and-set! shutdown-hook-registered? false true)
      (.addShutdownHook (Runtime/getRuntime)
        (Thread. ^Runnable
          (fn []
            (log/info "Shutdown signal received — stopping Chengis gracefully")
            (stop!))
          "chengis-shutdown-hook")))

    (when metrics-registry
      (log/info (str "Prometheus metrics enabled at " (get-in cfg [:metrics :path] "/metrics"))))
    (when (get-in cfg [:auth :enabled])
      (log/info "Authentication is ENABLED — login required"))
    (when (get-in cfg [:audit :enabled] true)
      (log/info "Audit logging is ENABLED"))
    (println)
    (if (and https-enabled? keystore)
      (do
        (println (str "  Chengis CI is running at https://localhost:" https-port))
        (println (str "  HTTP redirect active on port " port)))
      (println (str "  Chengis CI is running at http://localhost:" port)))
    (when (get-in cfg [:auth :enabled])
      (println "  Authentication enabled — default: admin/admin"))
    (println "  Press Ctrl+C to stop.")
    (println)
    ;; Block main thread
    @(promise)))

(defn stop!
  "Stop the running web server with ordered shutdown:
   1. Stop background services (orphan monitor, queue processor)
   2. Stop HTTP/HTTPS servers (with timeout for in-flight requests)
   3. Close audit writer channel
   4. Close database connection"
  []
  ;; 1. Stop background services first
  (when (retention/running?*)
    (log/info "Stopping retention scheduler...")
    (retention/stop-retention!))
  (when (orphan-monitor/running?*)
    (log/info "Stopping orphan monitor...")
    (orphan-monitor/stop-monitor!))
  (when (queue-processor/running?*)
    (log/info "Stopping queue processor...")
    (queue-processor/stop-processor!))

  ;; 2. Stop HTTP/HTTPS servers — allow 15s for in-flight requests to complete
  (when-let [stop-fn @server-instance]
    (log/info "Stopping HTTP server...")
    (stop-fn :timeout 15000)
    (reset! server-instance nil))
  (when-let [stop-fn @https-instance]
    (log/info "Stopping HTTPS server...")
    (stop-fn :timeout 15000)
    (reset! https-instance nil))

  ;; 3. Close audit writer and datasource
  (when-let [{:keys [audit-writer ds]} @system-state]
    (when-let [stop-fn (:stop-fn audit-writer)]
      (log/info "Closing audit writer...")
      (stop-fn))
    ;; 4. Close datasource
    (when ds
      (log/info "Closing database connection...")
      (conn/close-datasource! ds)))
  (reset! system-state nil)

  (log/info "Chengis web UI stopped"))
