(ns chengis.db.migrate
  (:require [migratus.core :as migratus]))

(defn migration-config
  "Build migratus configuration for a SQLite database."
  [db-path]
  {:store :database
   :migration-dir "migrations"
   :db {:dbtype "sqlite" :dbname db-path}})

(defn migrate!
  "Run all pending migrations."
  [db-path]
  (migratus/migrate (migration-config db-path)))

(defn rollback!
  "Roll back the last migration."
  [db-path]
  (migratus/rollback (migration-config db-path)))

(defn reset!
  "Roll back all migrations and re-apply them."
  [db-path]
  (migratus/reset (migration-config db-path)))
