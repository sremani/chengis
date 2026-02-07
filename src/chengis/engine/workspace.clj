(ns chengis.engine.workspace
  (:require [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.nio.file Files Path FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]))

(defn- validate-path
  "Verify the resolved path is within workspace-root. Prevents path traversal."
  [workspace-root path]
  (let [root-canonical (.getCanonicalPath (io/file workspace-root))
        path-canonical (.getCanonicalPath (io/file path))]
    (when-not (.startsWith path-canonical root-canonical)
      (throw (ex-info "Path traversal detected"
               {:workspace-root root-canonical :resolved-path path-canonical})))
    path-canonical))

(defn workspace-path
  "Compute the workspace directory path for a build."
  [workspace-root job-id build-number]
  (let [raw-path (str workspace-root "/" job-id "/" build-number)]
    (validate-path workspace-root raw-path)))

(defn create-workspace
  "Create the workspace directory for a build. Returns the absolute path."
  [workspace-root job-id build-number]
  (let [path (workspace-path workspace-root job-id build-number)
        dir (io/file path)]
    (when-not (.exists dir)
      (when-not (.mkdirs dir)
        (throw (ex-info "Failed to create workspace directory" {:path path})))
      (log/info "Created workspace:" (.getAbsolutePath dir)))
    (.getAbsolutePath dir)))

(defn cleanup-workspace
  "Delete a workspace directory and all its contents.
   Uses walkFileTree to avoid following symlinks."
  [workspace-path]
  (let [dir (io/file workspace-path)
        dir-path (.toPath dir)]
    (when (.exists dir)
      (Files/walkFileTree dir-path
        (proxy [SimpleFileVisitor] []
          (visitFile [file _attrs]
            (Files/deleteIfExists file)
            FileVisitResult/CONTINUE)
          (postVisitDirectory [dir _exc]
            (Files/deleteIfExists dir)
            FileVisitResult/CONTINUE)))
      (log/info "Cleaned up workspace:" workspace-path))))

(defn cleanup-old-workspaces
  "Clean up workspaces for a job, keeping the last n builds."
  [workspace-root job-id keep-last-n]
  (let [job-dir (io/file workspace-root job-id)]
    (when (.exists job-dir)
      (let [files (or (.listFiles job-dir) (into-array java.io.File []))
            build-dirs (->> files
                            (filter #(.isDirectory %))
                            (sort-by #(try (Long/parseLong (.getName %))
                                           (catch NumberFormatException _ 0)))
                            reverse)
            to-delete (drop keep-last-n build-dirs)]
        (doseq [d to-delete]
          (cleanup-workspace (.getAbsolutePath d))
          (log/info "Cleaned old workspace:" (.getAbsolutePath d)))))))
