(ns chengis.web.server
  (:require [org.httpkit.server :as http]
            [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.logging :as logging]
            [chengis.metrics :as metrics]
            [chengis.engine.events :as events]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.queue-processor :as queue-processor]
            [chengis.distributed.orphan-monitor :as orphan-monitor]
            [chengis.distributed.leader-election :as leader]
            [chengis.engine.analytics :as analytics]
            [chengis.engine.retention :as retention]
            [chengis.plugin.loader :as plugin-loader]
            [chengis.web.audit :as audit]
            [chengis.web.rate-limit :as rate-limit]
            [chengis.web.routes :as routes]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(declare stop!)

(defonce server-instance (atom nil))
(defonce https-instance (atom nil))
(defonce system-state (atom nil))
(defonce shutdown-hook-registered? (atom false))
(defonce leader-loops (atom []))  ;; leader election loops to stop on shutdown

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
        ;; Initialize event bus buffer from config (must be before subscribers)
        _ (events/init-event-bus! cfg)
        ;; Set metrics registry on event bus
        _ (events/set-metrics-registry! metrics-registry)
        ;; Set database reference on event bus for durable persistence
        _ (events/set-db! ds)
        ;; Start audit writer
        audit-writer (when (get-in cfg [:audit :enabled] true)
                       (audit/start-audit-writer! ds cfg))
        system {:config cfg :db ds :db-path db-path
                :audit-writer audit-writer :metrics metrics-registry}
        _ (plugin-loader/load-plugins! system)
        ;; Register secret backend based on configuration
        _ (let [backend-type (get-in cfg [:secrets :backend] "local")
                plugin-reg (requiring-resolve 'chengis.plugin.registry/register-secret-backend!)]
            (case backend-type
              "vault" (let [create-fn (requiring-resolve 'chengis.plugin.builtin.vault-secrets/create-backend)]
                        (plugin-reg "vault" (create-fn))
                        (log/info "Secret backend: HashiCorp Vault"))
              "aws-sm" (let [create-fn (requiring-resolve 'chengis.plugin.builtin.aws-secrets/create-backend)]
                         (plugin-reg "aws-sm" (create-fn))
                         (log/info "Secret backend: AWS Secrets Manager"))
              "gcp-sm" (let [create-fn (requiring-resolve 'chengis.plugin.builtin.gcp-secrets/create-backend)]
                         (plugin-reg "gcp-sm" (create-fn))
                         (log/info "Secret backend: Google Cloud Secret Manager"))
              "azure-kv" (let [create-fn (requiring-resolve 'chengis.plugin.builtin.azure-keyvault/create-backend)]
                           (plugin-reg "azure-kv" (create-fn))
                           (log/info "Secret backend: Azure Key Vault"))
              ;; Default to local encrypted DB store
              (let [create-fn (requiring-resolve 'chengis.plugin.builtin.local-secrets/create-backend)]
                (plugin-reg "local" (create-fn ds))
                (log/info "Secret backend: local (AES-256-GCM encrypted database)"))))
        ;; Configure agent registry with heartbeat timeout from config
        _ (agent-reg/set-config! cfg)
        ;; Enable write-through persistence for agent registry
        _ (agent-reg/set-db! ds)
        _ (agent-reg/hydrate-from-db!)
        ;; Start singleton services — use leader election when HA is enabled
        ha-enabled? (get-in cfg [:ha :enabled])
        poll-ms (get-in cfg [:ha :leader-poll-ms] 15000)
        ;; Queue processor (lock-id 100001)
        _ (when (and (get-in cfg [:distributed :enabled])
                     (get-in cfg [:distributed :dispatch :queue-enabled]))
            (if ha-enabled?
              (do (log/info "Starting queue processor with leader election")
                  (swap! leader-loops conj
                    (leader/start-leader-loop! ds 100001 "queue-processor"
                      #(queue-processor/start-processor! system)
                      #(queue-processor/stop-processor!)
                      poll-ms)))
              (do (log/info "Starting distributed build queue processor")
                  (queue-processor/start-processor! system))))
        ;; Orphan monitor (lock-id 100002)
        _ (when (get-in cfg [:distributed :enabled])
            (if ha-enabled?
              (do (log/info "Starting orphan monitor with leader election")
                  (swap! leader-loops conj
                    (leader/start-leader-loop! ds 100002 "orphan-monitor"
                      #(orphan-monitor/start-monitor! system)
                      #(orphan-monitor/stop-monitor!)
                      poll-ms)))
              (do (log/info "Starting orphan build monitor")
                  (orphan-monitor/start-monitor! system))))
        ;; Retention scheduler (lock-id 100003)
        _ (when (get-in cfg [:retention :enabled])
            (if ha-enabled?
              (do (log/info "Starting retention scheduler with leader election")
                  (swap! leader-loops conj
                    (leader/start-leader-loop! ds 100003 "retention-scheduler"
                      #(retention/start-retention! system)
                      #(retention/stop-retention!)
                      poll-ms)))
              (do (log/info "Starting data retention scheduler")
                  (retention/start-retention! system))))
        ;; Analytics scheduler (lock-id 100004)
        _ (when (get-in cfg [:feature-flags :build-analytics])
            (if ha-enabled?
              (do (log/info "Starting analytics scheduler with leader election")
                  (swap! leader-loops conj
                    (leader/start-leader-loop! ds 100004 "analytics-scheduler"
                      #(analytics/start-analytics! system)
                      #(analytics/stop-analytics!)
                      poll-ms)))
              (do (log/info "Starting build analytics scheduler")
                  (analytics/start-analytics! system))))
        ;; LDAP group sync scheduler (lock-id 100005)
        _ (when (get-in cfg [:feature-flags :ldap])
            (let [sync-fn (requiring-resolve 'chengis.web.ldap/sync-ldap-groups!)]
              (if ha-enabled?
                (do (log/info "Starting LDAP group sync with leader election")
                    (swap! leader-loops conj
                      (leader/start-leader-loop! ds 100005 "ldap-sync"
                        #(future (while true
                                   (sync-fn ds cfg)
                                   (Thread/sleep (* 60 1000 (get-in cfg [:ldap :sync-interval-minutes] 60)))))
                        #(log/info "Stopping LDAP sync scheduler")
                        poll-ms)))
                (do (log/info "Starting LDAP group sync scheduler")
                    (future (while true
                              (sync-fn ds cfg)
                              (Thread/sleep (* 60 1000 (get-in cfg [:ldap :sync-interval-minutes] 60)))))))))
        ;; Secret rotation scheduler (lock-id 100006)
        _ (when (get-in cfg [:feature-flags :secret-rotation])
            (let [start-fn (requiring-resolve 'chengis.engine.secret-rotation/start-rotation-scheduler!)
                  stop-fn (requiring-resolve 'chengis.engine.secret-rotation/stop-rotation-scheduler!)]
              (if ha-enabled?
                (do (log/info "Starting secret rotation scheduler with leader election")
                    (swap! leader-loops conj
                      (leader/start-leader-loop! ds 100006 "secret-rotation"
                        #(start-fn system)
                        #(stop-fn)
                        poll-ms)))
                (do (log/info "Starting secret rotation scheduler")
                    (start-fn system)))))
        ;; Start periodic event subscriber cleanup (every 30 minutes).
        ;; Daemon thread — stops automatically on JVM shutdown.
        _ (doto (Thread.
                  (fn []
                    (log/info "Event subscriber cleanup thread started")
                    (while (not (.isInterrupted (Thread/currentThread)))
                      (try
                        (Thread/sleep (* 30 60 1000))
                        (events/cleanup-stale-subscribers!)
                        (catch InterruptedException _
                          (.interrupt (Thread/currentThread)))
                        (catch Exception e
                          (log/warn "Subscriber cleanup error:" (.getMessage e))))))
                  "chengis-subscriber-cleanup")
            (.setDaemon true)
            (.start))
        ;; Start background rate-limit cleanup timer (runs even when no requests arrive)
        _ (when (get-in cfg [:rate-limit :enabled])
            (rate-limit/start-cleanup-timer!))
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
          worker-threads (get-in cfg [:server :worker-threads] 8)
          queue-size (get-in cfg [:server :queue-size] 20480)
          max-body (get-in cfg [:server :max-body] 8388608)
          stop-fn (http/run-server http-handler
                    {:port port :ip host
                     :thread worker-threads
                     :queue-size queue-size
                     :max-body max-body})]
      (reset! server-instance stop-fn)
      (if (and https-enabled? keystore)
        (log/info (str "HTTP→HTTPS redirect on http://" host ":" port))
        (log/info (str "Chengis web UI started on http://" host ":" port))))

    ;; Mark startup as complete (for /startup probe)
    ((requiring-resolve 'chengis.web.handlers/mark-startup-complete!))

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
    (when ha-enabled?
      (log/info (str "HA mode ENABLED — leader election active (poll: " poll-ms "ms)")))
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
  ;; 1. Stop leader election loops (if any)
  (doseq [loop @leader-loops]
    (try
      (leader/stop-leader-loop! loop)
      (catch Exception e
        (log/error "Error stopping leader loop:" (.getMessage e)))))
  (reset! leader-loops [])

  ;; 1b. Stop background services that may be running directly (non-HA mode)
  (when (retention/running?*)
    (log/info "Stopping retention scheduler...")
    (retention/stop-retention!))
  (when (orphan-monitor/running?*)
    (log/info "Stopping orphan monitor...")
    (orphan-monitor/stop-monitor!))
  (when (queue-processor/running?*)
    (log/info "Stopping queue processor...")
    (queue-processor/stop-processor!))
  (when (analytics/running?*)
    (log/info "Stopping analytics scheduler...")
    (analytics/stop-analytics!))
  ;; Stop rate-limit cleanup timer
  (rate-limit/stop-cleanup-timer!)

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
