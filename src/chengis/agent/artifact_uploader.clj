(ns chengis.agent.artifact-uploader
  "Agent-side artifact upload to master.
   After a build completes, scans the build workspace for artifacts
   and uploads them to the master via HTTP multipart POST."
  (:require [org.httpkit.client :as http]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Single file upload
;; ---------------------------------------------------------------------------

(defn upload-single!
  "Upload a single artifact file to the master.
   Returns true on success, false on failure."
  [master-url build-id ^File file config]
  (try
    (let [url (str master-url "/api/builds/" build-id "/artifacts")
          auth-token (:auth-token config)
          resp @(http/post url
                  {:headers (cond-> {}
                              auth-token (assoc "Authorization" (str "Bearer " auth-token)))
                   :multipart [{:name "file"
                                :content file
                                :filename (.getName file)}]
                   :timeout 60000})]
      (if (and (:status resp) (< (:status resp) 300))
        (do
          (log/info "Uploaded artifact:" (.getName file) "(" (.length file) "bytes)")
          true)
        (do
          (log/warn "Failed to upload artifact" (.getName file)
                    "- HTTP" (:status resp))
          false)))
    (catch Exception e
      (log/error "Error uploading artifact" (.getName file) ":" (.getMessage e))
      false)))

;; ---------------------------------------------------------------------------
;; Batch upload
;; ---------------------------------------------------------------------------

(defn- find-artifact-files
  "Find files in the artifact directory that should be uploaded."
  [artifact-dir]
  (when (and artifact-dir (.exists (File. ^String artifact-dir)))
    (->> (.listFiles (File. ^String artifact-dir))
         (filter #(.isFile ^File %))
         (vec))))

(defn upload-artifacts!
  "Upload all artifacts from a build to the master.
   Scans the build's artifact directory and uploads each file.

   Arguments:
     master-url   - master base URL (e.g. http://localhost:8080)
     build-id     - the build ID
     artifact-dir - path to the artifact directory
     config       - agent config with :auth-token

   Returns count of successfully uploaded artifacts."
  [master-url build-id artifact-dir config]
  (let [files (find-artifact-files artifact-dir)]
    (if (seq files)
      (do
        (log/info "Uploading" (count files) "artifact(s) for build" build-id)
        (let [results (mapv #(upload-single! master-url build-id % config) files)
              success-count (count (filter true? results))]
          (when (< success-count (count files))
            (log/warn (- (count files) success-count) "artifact(s) failed to upload"))
          success-count))
      (do
        (log/debug "No artifacts to upload for build" build-id)
        0))))
