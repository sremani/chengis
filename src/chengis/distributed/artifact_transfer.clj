(ns chengis.distributed.artifact-transfer
  "Master-side artifact receiving and storage.
   Agents upload build artifacts to the master via HTTP multipart POST.
   Artifacts are stored under the configured artifacts root directory,
   organized by build ID."
  (:require [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.io File InputStream]))

;; ---------------------------------------------------------------------------
;; Storage
;; ---------------------------------------------------------------------------

(defn- artifact-dir
  "Resolve the directory for a build's artifacts.
   Creates the directory if it doesn't exist."
  [system build-id]
  (let [root (get-in system [:config :artifacts :root] "artifacts")
        dir (io/file root build-id)]
    (when-not (.exists dir)
      (.mkdirs dir))
    dir))

(defn receive-artifact!
  "Store an artifact uploaded by an agent.
   Writes the input stream to artifacts/<build-id>/<filename>.
   Returns metadata about the stored artifact."
  [system build-id filename ^InputStream input-stream]
  (try
    (let [dir (artifact-dir system build-id)
          ;; Sanitize filename to prevent path traversal
          safe-name (.getName (io/file filename))
          target (io/file dir safe-name)]
      (io/copy input-stream target)
      (let [size (.length target)]
        (log/info "Artifact received:" safe-name "for build" build-id
                  "(" size "bytes)")
        (try (metrics/record-artifact-transfer! (:metrics system) :success)
             (catch Exception _))
        {:filename safe-name
         :build-id build-id
         :size size
         :path (str target)}))
    (catch Exception e
      (log/error "Failed to store artifact" filename "for build" build-id ":" (.getMessage e))
      (try (metrics/record-artifact-transfer! (:metrics system) :failure)
           (catch Exception _))
      nil)))

(defn list-artifacts
  "List stored artifacts for a build. Returns seq of {:filename :size :path}."
  [system build-id]
  (let [dir (artifact-dir system build-id)]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isFile ^File %))
           (mapv (fn [^File f]
                   {:filename (.getName f)
                    :size (.length f)
                    :path (str f)}))))))

;; ---------------------------------------------------------------------------
;; Ring handler for artifact upload
;; ---------------------------------------------------------------------------

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn- check-auth
  "Validate the auth token from request headers."
  [req system]
  (let [expected (get-in system [:config :distributed :auth-token])
        provided (some-> (get-in req [:headers "authorization"])
                         (str/replace #"^Bearer " ""))]
    (or (nil? expected)
        (= expected provided))))

(defn artifact-upload-handler
  "POST /api/builds/:id/artifacts — Receive multipart artifact uploads from agents.
   Validates auth token. Expects a multipart request with a 'file' part."
  [system]
  (fn [req]
    (if-not (check-auth req system)
      (json-response 401 {:error "Unauthorized"})
      (let [build-id (get-in req [:path-params :id])
            multipart (:multipart-params req)
            file-part (get multipart "file")]
        (if (and file-part (:tempfile file-part))
          (let [filename (:filename file-part)
                tempfile (:tempfile file-part)]
            (with-open [is (io/input-stream tempfile)]
              (let [result (receive-artifact! system build-id filename is)]
                (if result
                  (json-response 200 {:status "ok"
                                      :filename (:filename result)
                                      :size (:size result)})
                  (json-response 500 {:error "Failed to store artifact"})))))
          (json-response 400 {:error "Missing 'file' multipart parameter"}))))))
