(ns chengis.db.backup
  "Database backup and restore utilities.
   SQLite: Uses VACUUM INTO for safe hot backups of a running database.
   PostgreSQL: Uses pg_dump for logical backups."
  (:require [next.jdbc :as jdbc]
            [chengis.db.connection :as conn]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [babashka.process :as proc])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File]))

(def ^:private backup-timestamp-fmt
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn generate-backup-path
  "Generate a timestamped backup filename in the given directory.
   Extension is .db for SQLite, .sql for PostgreSQL.
   Returns the full path string."
  ([base-dir]
   (generate-backup-path base-dir :sqlite))
  ([base-dir db-type]
   (let [timestamp (.format (LocalDateTime/now) backup-timestamp-fmt)
         ext (if (= db-type :postgresql) ".sql" ".db")
         filename (str "chengis-backup-" timestamp ext)]
     (.getAbsolutePath (io/file base-dir filename)))))

;; ---------------------------------------------------------------------------
;; SQLite backup
;; ---------------------------------------------------------------------------

(defn- backup-sqlite!
  "Create a safe hot backup of the SQLite database using VACUUM INTO."
  [ds output-path]
  (let [out-file (io/file output-path)]
    (when-let [parent (.getParentFile out-file)]
      (.mkdirs parent))
    (log/info "Creating SQLite backup:" output-path)
    (jdbc/execute! ds [(str "VACUUM INTO '" (.getAbsolutePath out-file) "'")])
    (let [size (.length out-file)]
      (log/info "Backup complete:" output-path "(" size "bytes)")
      {:path (.getAbsolutePath out-file)
       :size-bytes size
       :timestamp (str (java.time.Instant/now))})))

;; ---------------------------------------------------------------------------
;; PostgreSQL backup
;; ---------------------------------------------------------------------------

(defn- backup-postgresql!
  "Create a logical backup of PostgreSQL using pg_dump.
   Requires pg_dump to be available on PATH.
   Uses the system config to derive connection parameters."
  [system output-path]
  (let [db-cfg (get-in system [:config :database])
        out-file (io/file output-path)]
    (when-let [parent (.getParentFile out-file)]
      (.mkdirs parent))
    (log/info "Creating PostgreSQL backup via pg_dump:" output-path)
    (let [result (proc/shell {:out output-path
                              :extra-env {"PGPASSWORD" (get db-cfg :password "")}}
                   "pg_dump"
                   "-h" (get db-cfg :host "localhost")
                   "-p" (str (get db-cfg :port 5432))
                   "-U" (get db-cfg :user "chengis")
                   "-d" (get db-cfg :dbname "chengis")
                   "--no-owner" "--no-acl")]
      (when (not= 0 (:exit result))
        (throw (ex-info "pg_dump failed" {:exit (:exit result)
                                          :stderr (slurp (:err result))})))
      (let [size (.length out-file)]
        (log/info "Backup complete:" output-path "(" size "bytes)")
        {:path (.getAbsolutePath out-file)
         :size-bytes size
         :timestamp (str (java.time.Instant/now))}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn backup!
  "Create a backup of the database.
   For SQLite: uses VACUUM INTO (safe during live traffic).
   For PostgreSQL: uses pg_dump (requires pg_dump on PATH).
   Returns {:path output-path :size-bytes N :timestamp ISO-string}."
  ([ds output-path]
   ;; Legacy 2-arity: auto-detect from datasource type
   (backup-sqlite! ds output-path))
  ([ds output-path system]
   ;; 3-arity: use system to determine DB type
   (if (= :postgresql (conn/datasource-type ds))
     (backup-postgresql! system output-path)
     (backup-sqlite! ds output-path))))

(defn restore!
  "Restore a database from a backup file by copying it to the target path.
   For SQLite only — PostgreSQL restore should use psql directly.
   Safety: refuses to overwrite unless force? is true.
   Returns {:restored-from backup-path :target target-path}."
  [backup-path target-path & {:keys [force?] :or {force? false}}]
  (let [backup-file (io/file backup-path)
        target-file (io/file target-path)]
    (when-not (.exists backup-file)
      (throw (ex-info "Backup file not found" {:path backup-path})))
    (when (and (.exists target-file) (not force?))
      (throw (ex-info "Target database already exists. Use --force to overwrite."
                      {:target target-path})))
    (log/info "Restoring database from" backup-path "to" target-path)
    (io/copy backup-file target-file)
    (log/info "Restore complete")
    {:restored-from (.getAbsolutePath backup-file)
     :target (.getAbsolutePath target-file)}))

(defn list-backups
  "List all chengis backup files in a directory, sorted newest first.
   Returns [{:path ... :filename ... :size-bytes ... :modified ...}]."
  [backup-dir]
  (let [dir (io/file backup-dir)]
    (if-not (.exists dir)
      []
      (->> (.listFiles dir)
           (filter (fn [^File f]
                     (and (.isFile f)
                          (.startsWith (.getName f) "chengis-backup-")
                          (or (.endsWith (.getName f) ".db")
                              (.endsWith (.getName f) ".sql")))))
           (sort-by #(.lastModified ^File %) >)
           (mapv (fn [^File f]
                   {:path (.getAbsolutePath f)
                    :filename (.getName f)
                    :size-bytes (.length f)
                    :modified (str (java.time.Instant/ofEpochMilli (.lastModified f)))}))))))
