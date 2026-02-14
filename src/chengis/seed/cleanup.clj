(ns chengis.seed.cleanup
  "Remove all simulation seed data from the database.

   Usage:
     lein run -m chengis.seed.cleanup"
  (:require [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.seed.sim.inserters :as ins]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn- table-count
  "Get the row count for a table."
  [ds table-name]
  (let [result (jdbc/execute-one! ds
                 [(str "SELECT COUNT(*) AS cnt FROM " table-name)]
                 {:builder-fn rs/as-unqualified-kebab-maps})]
    (:cnt result 0)))

(defn- print-counts
  "Print row counts for all relevant tables."
  [ds label]
  (let [tables ["users" "jobs" "builds" "build_stages" "build_steps"
                "build_logs" "webhook_events" "audit_logs" "approval_gates"]]
    (println (str "\n" label ":"))
    (doseq [t tables]
      (printf "  %-20s %d\n" t (table-count ds t)))
    (println)))

(defn -main
  "Entry point for cleanup. Removes all data from simulation-relevant tables."
  [& _args]
  (let [cfg    (config/load-config)
        db-cfg (:database cfg)
        _      (println "Seed Data Cleanup")
        _      (println (str "Database: " (if (= "postgresql" (:type db-cfg))
                                            (str "PostgreSQL " (:host db-cfg) ":" (:port db-cfg) "/" (:dbname db-cfg))
                                            (str "SQLite " (:path db-cfg)))))
        _      (migrate/migrate! db-cfg)
        ds     (conn/create-datasource db-cfg)]
    (try
      (print-counts ds "Before cleanup")
      (println "Clearing all tables...")
      (ins/clear-all-tables! ds)
      (print-counts ds "After cleanup")
      (println "Cleanup complete.")
      (finally
        (conn/close-datasource! ds)))))
