(ns chengis.engine.cache
  "Artifact/dependency caching for build acceleration.
   Provides cache-key resolution, restore/save operations, and eviction.
   Cache entries are keyed by file hashes (e.g., package-lock.json) and stored
   as directories on the filesystem with metadata tracked in the database."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [chengis.db.cache-store :as cache-store]
            [chengis.feature-flags :as ff]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.nio.file Files Path Paths StandardCopyOption]
           [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; Cache key resolution
;; ---------------------------------------------------------------------------

(defn- sha256-file
  "Compute SHA-256 hash of a file's contents using streaming for large files.
   Returns hex string."
  [^File f]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [is (java.io.FileInputStream. f)]
      (loop []
        (let [n (.read is buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (let [digest (.digest md)]
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn- resolve-hash-files
  "Resolve {{ hashFiles('pattern') }} expressions in a cache key template.
   Computes SHA-256 of matching files in the workspace."
  [workspace key-template]
  (if-let [[_ file-pattern] (re-find #"\{\{\s*hashFiles\('([^']+)'\)\s*\}\}" key-template)]
    (let [f (io/file workspace file-pattern)]
      (if (.exists f)
        (let [hash (sha256-file f)
              short-hash (subs hash 0 16)]
          (str/replace key-template
                       #"\{\{\s*hashFiles\('[^']+'\)\s*\}\}"
                       short-hash))
        (do
          (log/warn "hashFiles pattern not found:" file-pattern "in" workspace)
          (str/replace key-template
                       #"\{\{\s*hashFiles\('[^']+'\)\s*\}\}"
                       "missing"))))
    ;; No expression found — return as-is (literal key)
    key-template))

(defn resolve-cache-key
  "Resolve a cache key template to a concrete key.
   Supports: {{ hashFiles('filename') }} expressions."
  [workspace key-template]
  (resolve-hash-files workspace key-template))

;; ---------------------------------------------------------------------------
;; Cache paths
;; ---------------------------------------------------------------------------

(defn cache-root
  "Get the cache root directory from config."
  [config]
  (get-in config [:cache :root] "cache"))

(defn cache-path
  "Compute the storage path for a cache entry."
  [root job-id cache-key]
  (str root "/" job-id "/" cache-key))

;; ---------------------------------------------------------------------------
;; File copy utilities
;; ---------------------------------------------------------------------------

(defn- copy-directory!
  "Recursively copy a directory from src to dest.
   Handles the case where .listFiles returns null (permission denied, etc.)."
  [^File src ^File dest]
  (when (.exists src)
    (.mkdirs dest)
    (when-let [files (.listFiles src)]
      (doseq [f files]
        (let [target (io/file dest (.getName f))]
          (if (.isDirectory f)
            (copy-directory! f target)
            (io/copy f target)))))))

(defn- directory-size
  "Compute total size of a directory in bytes.
   Returns 0 if the directory can't be listed (permission denied, etc.)."
  [^File dir]
  (if (.isDirectory dir)
    (if-let [files (.listFiles dir)]
      (reduce + 0 (map directory-size files))
      0)
    (.length dir)))

;; ---------------------------------------------------------------------------
;; Cache restore and save
;; ---------------------------------------------------------------------------

(defn restore-cache!
  "Restore cached directories to workspace paths.
   For each cache declaration, resolves the key, checks for cache hit,
   and copies cached content back to workspace.
   Returns a list of restored cache keys (or empty if all miss)."
  [workspace config ds job-id cache-decls]
  (when (and (ff/enabled? config :artifact-cache) (seq cache-decls))
    (let [root (cache-root config)]
      (doall
        (for [decl cache-decls
              :let [key-template (:key decl)
                    resolved-key (resolve-cache-key workspace key-template)
                    cp (cache-path root job-id resolved-key)
                    cache-dir (io/file cp)
                    ;; Try exact match first, then restore-keys prefix
                    found? (.exists cache-dir)
                    prefix-match (when (and (not found?) (seq (:restore-keys decl)) ds)
                                   (let [prefixes (:restore-keys decl)]
                                     (first
                                       (for [prefix prefixes
                                             :let [entries (cache-store/find-by-prefix ds job-id prefix)]
                                             entry entries
                                             :let [alt-cp (cache-path root job-id (:cache-key entry))
                                                   alt-dir (io/file alt-cp)]
                                             :when (.exists alt-dir)]
                                         {:key (:cache-key entry) :dir alt-dir}))))
                    effective-dir (cond found? cache-dir
                                        prefix-match (:dir prefix-match)
                                        :else nil)]
              :when effective-dir]
          (do
            (log/info "Cache hit for" resolved-key
                      (when prefix-match (str "(prefix match: " (:key prefix-match) ")")))
            ;; Restore each path from cache to workspace
            (doseq [path (:paths decl)]
              (let [dest (io/file workspace path)
                    src (io/file effective-dir path)]
                (when (.exists src)
                  (copy-directory! src dest))))
            ;; Record hit in DB
            (when ds
              (try
                (cache-store/record-cache-hit! ds job-id
                  (or (when prefix-match (:key prefix-match)) resolved-key))
                (catch Exception e
                  (log/debug "Failed to record cache hit:" (.getMessage e)))))
            resolved-key))))))

(defn save-cache!
  "Save workspace directories to the cache.
   For each cache declaration, resolves the key, copies paths to cache storage.
   Skips if cache already exists for that key (immutable)."
  [workspace config ds job-id cache-decls]
  (when (and (ff/enabled? config :artifact-cache) (seq cache-decls))
    (let [root (cache-root config)]
      (doseq [decl cache-decls]
        (let [key-template (:key decl)
              resolved-key (resolve-cache-key workspace key-template)
              cp (cache-path root job-id resolved-key)
              cache-dir (io/file cp)]
          (if (.exists cache-dir)
            (log/debug "Cache already exists for" resolved-key "- skipping save")
            (do
              (log/info "Saving cache for" resolved-key)
              (.mkdirs cache-dir)
              (let [total-size (atom 0)]
                (doseq [path (:paths decl)]
                  (let [src (io/file workspace path)]
                    (when (.exists src)
                      (let [dest (io/file cache-dir path)]
                        (copy-directory! src dest)
                        (swap! total-size + (directory-size src))))))
                ;; Persist metadata to DB
                (when ds
                  (try
                    (cache-store/save-cache-entry! ds
                      {:job-id job-id
                       :cache-key resolved-key
                       :paths (str/join "," (:paths decl))
                       :size-bytes @total-size})
                    (catch Exception e
                      (log/debug "Failed to save cache metadata:" (.getMessage e)))))))))))))

(defn evict-cache!
  "Remove cache entries older than the specified number of days."
  [config ds job-id]
  (let [retention-days (get-in config [:cache :retention-days] 30)
        root (cache-root config)]
    (when ds
      (try
        (cache-store/delete-cache-entries! ds job-id retention-days)
        (catch Exception e
          (log/debug "Failed to evict cache entries:" (.getMessage e)))))))

(defn cache-stats
  "Return cache statistics for a job."
  [config ds job-id]
  (when ds
    (let [entries (cache-store/list-cache-entries ds job-id)]
      {:count (count entries)
       :total-size-bytes (reduce + 0 (map :size-bytes entries))
       :entries entries})))
