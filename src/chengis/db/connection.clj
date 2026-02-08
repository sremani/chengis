(ns chengis.db.connection
  "Database connection management — supports SQLite (default) and PostgreSQL.
   SQLite uses a direct JDBC datasource. PostgreSQL uses HikariCP connection pooling."
  (:require [next.jdbc :as jdbc]
            [taoensso.timbre :as log])
  (:import [com.zaxxer.hikari HikariDataSource]))

;; ---------------------------------------------------------------------------
;; SQLite datasource (zero-config, file-based)
;; ---------------------------------------------------------------------------

(defn- create-sqlite-datasource
  "Create a SQLite datasource from a database file path."
  [db-path]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path}))

;; ---------------------------------------------------------------------------
;; PostgreSQL datasource (HikariCP-pooled)
;; ---------------------------------------------------------------------------

(defn- create-postgresql-datasource
  "Create a HikariCP-pooled PostgreSQL datasource from a config map.
   Config keys: :host :port :dbname :user :password :pool {:minimum-idle :maximum-pool-size}"
  [db-cfg]
  (let [pool-cfg (get db-cfg :pool {})
        jdbc-url (str "jdbc:postgresql://"
                      (get db-cfg :host "localhost")
                      ":" (get db-cfg :port 5432)
                      "/" (get db-cfg :dbname "chengis"))
        ds (doto (HikariDataSource.)
             (.setJdbcUrl jdbc-url)
             (.setUsername (get db-cfg :user "chengis"))
             (.setPassword (get db-cfg :password ""))
             (.setMinimumIdle (get pool-cfg :minimum-idle 2))
             (.setMaximumPoolSize (get pool-cfg :maximum-pool-size 10))
             (.setConnectionTimeout 30000)
             (.setIdleTimeout 600000)
             (.setMaxLifetime 1800000)
             (.setPoolName "chengis-pg-pool"))]
    (log/info (str "PostgreSQL connection pool created: " jdbc-url
                   " (pool-size: " (.getMaximumPoolSize ds) ")"))
    ds))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn create-datasource
  "Create a datasource from configuration.
   Accepts either:
     - A string path (backward-compatible SQLite mode)
     - A database config map with :type key (\"sqlite\" or \"postgresql\")"
  [db-config]
  (if (string? db-config)
    ;; Backward compatibility: string path = SQLite
    (create-sqlite-datasource db-config)
    ;; Config map: dispatch on :type
    (case (get db-config :type "sqlite")
      "sqlite"     (create-sqlite-datasource (get db-config :path "chengis.db"))
      "postgresql" (create-postgresql-datasource db-config)
      (throw (ex-info (str "Unsupported database type: " (get db-config :type))
                      {:type :config-validation-error
                       :db-type (get db-config :type)})))))

(defn test-connection
  "Verify the database connection works."
  [ds]
  (jdbc/execute-one! ds ["SELECT 1 AS result"]))

(defn close-datasource!
  "Close the datasource connection.
   For SQLite: no-op (unless wrapped in Closeable).
   For PostgreSQL (HikariCP): closes the connection pool."
  [ds]
  (when (instance? java.io.Closeable ds)
    (log/info "Closing database connection pool...")
    (.close ^java.io.Closeable ds)))

(defn datasource-type
  "Returns :sqlite or :postgresql based on the datasource class."
  [ds]
  (if (instance? HikariDataSource ds)
    :postgresql
    :sqlite))
