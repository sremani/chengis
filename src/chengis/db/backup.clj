(ns chengis.db.backup
  "Database backup and restore utilities.
   Uses SQLite's VACUUM INTO for safe hot backups of a running database."
  (:require [next.jdbc :as jdbc]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]
           [java.io File]))

(def ^:private backup-timestamp-fmt
  (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn generate-backup-path
  "Generate a timestamped backup filename in the given directory.
   Returns the full path string."
  [base-dir]
  (let [timestamp (.format (LocalDateTime/now) backup-timestamp-fmt)
        filename (str "chengis-backup-" timestamp ".db")]
    (.getAbsolutePath (io/file base-dir filename))))

(defn backup!
  "Create a safe hot backup of the SQLite database using VACUUM INTO.
   This is safe to call while the database is in use (WAL mode compatible).
   Returns {:path output-path :size-bytes N :timestamp ISO-string}."
  [ds output-path]
  (let [out-file (io/file output-path)]
    ;; Ensure parent directory exists
    (when-let [parent (.getParentFile out-file)]
      (.mkdirs parent))
    ;; VACUUM INTO creates a clean, defragmented copy
    (log/info "Creating database backup:" output-path)
    (jdbc/execute! ds [(str "VACUUM INTO '" (.getAbsolutePath out-file) "'")])
    (let [size (.length out-file)]
      (log/info "Backup complete:" output-path "(" size "bytes)")
      {:path (.getAbsolutePath out-file)
       :size-bytes size
       :timestamp (str (java.time.Instant/now))})))

(defn restore!
  "Restore a database from a backup file by copying it to the target path.
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
                          (.endsWith (.getName f) ".db"))))
           (sort-by #(.lastModified ^File %) >)
           (mapv (fn [^File f]
                   {:path (.getAbsolutePath f)
                    :filename (.getName f)
                    :size-bytes (.length f)
                    :modified (str (java.time.Instant/ofEpochMilli (.lastModified f)))}))))))
