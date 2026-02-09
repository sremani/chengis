(ns chengis.engine.artifacts
  "Build artifact collection and storage.
   Copies files matching glob patterns from the workspace to persistent storage."
  (:require [clojure.java.io :as io]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.nio.file FileSystems Files Path Paths]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security MessageDigest]
           [java.io FileInputStream]))

(defn- glob-match
  "Find all files in `dir` matching a glob pattern. Returns a seq of Path objects."
  [^String dir ^String pattern]
  (let [base-path (Paths/get dir (into-array String []))
        ;; Normalize pattern:
        ;; - Patterns with path separators (e.g., "target/foo/*.jar") are used as-is
        ;; - Simple filename patterns (e.g., "*.jar") get **/ prepended to match anywhere
        ;; For simple patterns like "*.txt", use {*.txt,**/*.txt} so we match
        ;; both root-level files AND files in subdirectories.
        ;; Java's glob **/ requires at least one directory segment.
        effective-pattern (cond
                            (.startsWith pattern "**/") pattern
                            (.startsWith pattern "/")   pattern
                            (.contains pattern "/")     pattern
                            :else                       (str "{" pattern ",**/" pattern "}"))
        matcher (.getPathMatcher (FileSystems/getDefault)
                                  (str "glob:" effective-pattern))
        result (atom [])]
    (when (Files/exists base-path (into-array java.nio.file.LinkOption []))
      (Files/walkFileTree base-path
        (proxy [java.nio.file.SimpleFileVisitor] []
          (visitFile [^Path path ^BasicFileAttributes _attrs]
            (let [relative (.relativize base-path path)]
              (when (.matches matcher relative)
                (swap! result conj path)))
            java.nio.file.FileVisitResult/CONTINUE)
          (visitFileFailed [_path _exc]
            java.nio.file.FileVisitResult/CONTINUE))))
    @result))

(defn- content-type-for
  "Guess MIME type from filename extension."
  [filename]
  (cond
    (re-find #"\.jar$" filename)  "application/java-archive"
    (re-find #"\.zip$" filename)  "application/zip"
    (re-find #"\.tar\.gz$" filename) "application/gzip"
    (re-find #"\.html?$" filename) "text/html"
    (re-find #"\.json$" filename) "application/json"
    (re-find #"\.xml$" filename)  "application/xml"
    (re-find #"\.txt$" filename)  "text/plain"
    (re-find #"\.log$" filename)  "text/plain"
    (re-find #"\.css$" filename)  "text/css"
    (re-find #"\.js$" filename)   "application/javascript"
    (re-find #"\.pdf$" filename)  "application/pdf"
    (re-find #"\.png$" filename)  "image/png"
    (re-find #"\.jpg$" filename)  "image/jpeg"
    (re-find #"\.svg$" filename)  "image/svg+xml"
    :else "application/octet-stream"))

;; Use shared util/format-size for human-readable byte formatting

(defn compute-sha256
  "Compute SHA-256 hash of a file. Returns lowercase hex string, or nil on error."
  [^java.io.File file]
  (try
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (with-open [fis (FileInputStream. file)]
        (loop []
          (let [n (.read fis buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur)))))
      (format "%064x" (BigInteger. 1 (.digest digest))))
    (catch Exception e
      (log/warn "Failed to compute SHA-256 for" (.getName file) ":" (.getMessage e))
      nil)))

(defn collect-artifacts!
  "Collect artifacts matching glob patterns from workspace to artifact directory.
   Returns a vector of {:filename :path :size-bytes :content-type} maps."
  [workspace-dir artifact-dir patterns]
  (when (seq patterns)
    (let [dest (io/file artifact-dir)]
      (.mkdirs dest)
      (log/info "Collecting artifacts to:" artifact-dir)
      (into []
        (for [pattern patterns
              ^Path source-path (glob-match workspace-dir pattern)]
          (let [source-file (.toFile source-path)
                filename (.toString (.relativize
                                      (Paths/get workspace-dir (into-array String []))
                                      source-path))
                ;; Flatten nested paths to avoid directory conflicts in artifact dir
                flat-name (.replace filename "/" "_")
                dest-file (io/file dest flat-name)]
            ;; Copy file to artifact directory
            (io/make-parents dest-file)
            (io/copy source-file dest-file)
            (let [size (.length dest-file)]
              (log/info "  Artifact:" flat-name "(" (util/format-size size) ")")
              {:filename flat-name
               :original-path filename
               :path (.getAbsolutePath dest-file)
               :size-bytes size
               :content-type (content-type-for flat-name)
               :sha256-hash (compute-sha256 dest-file)})))))))
