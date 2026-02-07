(ns chengis.engine.artifacts
  "Build artifact collection and storage.
   Copies files matching glob patterns from the workspace to persistent storage."
  (:require [clojure.java.io :as io]
            [chengis.util :as util]
            [taoensso.timbre :as log])
  (:import [java.nio.file FileSystems Files Path Paths]
           [java.nio.file.attribute BasicFileAttributes]))

(defn- glob-match
  "Find all files in `dir` matching a glob pattern. Returns a seq of Path objects."
  [^String dir ^String pattern]
  (let [base-path (Paths/get dir (into-array String []))
        ;; Normalize pattern: if it doesn't start with **, prepend **/
        effective-pattern (if (or (.startsWith pattern "**/")
                                   (.startsWith pattern "/"))
                            pattern
                            (str "**/" pattern))
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
               :content-type (content-type-for flat-name)})))))))
