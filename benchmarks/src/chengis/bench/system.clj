(ns chengis.bench.system
  "System bootstrap helpers for benchmark runs.
   Provides lightweight (no DB) and full-lifecycle (with DB) system maps."
  (:require [chengis.config :as config]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.job-store :as job-store]
            [chengis.engine.workspace :as workspace]
            [chengis.plugin.loader :as plugin-loader]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;; Guard to load plugins only once
(defonce ^:private plugins-loaded? (atom false))

(defn init-plugins!
  "Load all builtin plugins. Idempotent — safe to call multiple times."
  []
  (when (compare-and-set! plugins-loaded? false true)
    (log/info "Loading plugins for benchmark...")
    (plugin-loader/load-plugins!)))

(defn create-lightweight-system
  "Create a minimal system with config only (no DB).
   Suitable for executor-only benchmarks."
  [workspace-root]
  {:config {:workspace {:root workspace-root}}})

(defn create-full-system
  "Create a full system with DB + config.
   Creates a fresh SQLite database and runs all migrations."
  [db-path workspace-root]
  (let [db-file (File. ^String db-path)]
    ;; Clean slate
    (when (.exists db-file)
      (.delete db-file))
    (migrate/migrate! db-path)
    (let [ds (conn/create-datasource db-path)]
      {:config  {:workspace {:root workspace-root}
                 :database  {:path db-path}}
       :db      ds
       :db-path db-path})))

(defn register-benchmark-job!
  "Create a job in the DB for a given pipeline. Returns the job map."
  [system pipeline]
  (job-store/create-job! (:db system) pipeline))

(defn cleanup!
  "Remove workspace directory and benchmark DB file."
  [{:keys [db-path] :as system}]
  (let [ws-root (get-in system [:config :workspace :root])]
    (when ws-root
      (let [ws-dir (File. ^String ws-root)]
        (when (.exists ws-dir)
          (workspace/cleanup-workspace ws-root))))
    (when db-path
      (let [db-file (File. ^String db-path)]
        (when (.exists db-file)
          (.delete db-file))))))
