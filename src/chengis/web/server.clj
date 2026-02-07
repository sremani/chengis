(ns chengis.web.server
  (:require [org.httpkit.server :as http]
            [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.plugin.loader :as plugin-loader]
            [chengis.web.routes :as routes]
            [taoensso.timbre :as log]))

(defonce server-instance (atom nil))

(defn start!
  "Start the Chengis web server."
  [& _args]
  (let [cfg (config/load-config)
        db-path (get-in cfg [:database :path])
        _ (migrate/migrate! db-path)
        ds (conn/create-datasource db-path)
        system {:config cfg :db ds :db-path db-path}
        _ (plugin-loader/load-plugins! system)
        port (get-in cfg [:server :port] 8080)
        host (get-in cfg [:server :host] "0.0.0.0")
        app (routes/app-routes system)
        stop-fn (http/run-server app {:port port :ip host})]
    (reset! server-instance stop-fn)
    (log/info (str "Chengis web UI started on http://" host ":" port))
    (println)
    (println (str "  Chengis CI is running at http://localhost:" port))
    (println "  Press Ctrl+C to stop.")
    (println)
    ;; Block main thread
    @(promise)))

(defn stop!
  "Stop the running web server."
  []
  (when-let [stop-fn @server-instance]
    (stop-fn :timeout 5000)
    (reset! server-instance nil)
    (log/info "Chengis web UI stopped")))
