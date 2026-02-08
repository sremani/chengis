(ns chengis.db.migrate
  "Database migration management — supports SQLite and PostgreSQL.
   Migrations are stored in separate directories per database type:
     resources/migrations/sqlite/      — SQLite-dialect SQL
     resources/migrations/postgresql/  — PostgreSQL-dialect SQL"
  (:require [migratus.core :as migratus]))

(defn migration-config
  "Build migratus configuration for a database.
   Accepts either:
     - A string path (backward-compatible SQLite mode)
     - A database config map with :type key"
  [db-config]
  (if (string? db-config)
    ;; Backward compatibility: string path = SQLite
    {:store :database
     :migration-dir "migrations/sqlite"
     :db {:dbtype "sqlite" :dbname db-config}}
    ;; Config map: dispatch on :type
    (case (get db-config :type "sqlite")
      "sqlite"
      {:store :database
       :migration-dir "migrations/sqlite"
       :db {:dbtype "sqlite" :dbname (get db-config :path "chengis.db")}}

      "postgresql"
      {:store :database
       :migration-dir "migrations/postgresql"
       :db {:dbtype "postgresql"
            :host (get db-config :host "localhost")
            :port (get db-config :port 5432)
            :dbname (get db-config :dbname "chengis")
            :user (get db-config :user "chengis")
            :password (get db-config :password)}}

      (throw (ex-info (str "Unsupported database type: " (get db-config :type))
                      {:type :config-validation-error
                       :db-type (get db-config :type)})))))

(defn migrate!
  "Run all pending migrations."
  [db-config]
  (migratus/migrate (migration-config db-config)))

(defn rollback!
  "Roll back the last migration."
  [db-config]
  (migratus/rollback (migration-config db-config)))

(defn reset!
  "Roll back all migrations and re-apply them."
  [db-config]
  (migratus/reset (migration-config db-config)))
