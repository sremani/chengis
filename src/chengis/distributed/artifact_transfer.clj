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
  (:import [java.io File InputStream]
           [java.security MessageDigest]))

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
   Enforces a configurable maximum artifact size (:artifacts :max-size-bytes).
   Returns metadata about the stored artifact."
  [system build-id filename ^InputStream input-stream]
  (try
    (let [dir (artifact-dir system build-id)
          ;; Sanitize filename to prevent path traversal
          safe-name (.getName (io/file filename))
          target (io/file dir safe-name)]
      (io/copy input-stream target)
      (let [size (.length target)
            max-bytes (get-in system [:config :artifacts :max-size-bytes] (* 500 1024 1024))]
        ;; Enforce artifact size limit
        (when (> size max-bytes)
          (.delete target)
          (throw (ex-info "Artifact exceeds size limit"
                   {:filename safe-name :size size :max-bytes max-bytes :build-id build-id})))
        (log/info "Artifact received:" safe-name "for build" build-id
                  "(" size "bytes)")
        (try (metrics/record-artifact-transfer! (:metrics system) :success)
             (catch Exception _))
        {:filename safe-name
         :build-id build-id
         :size size
         :path (str target)}))
    (catch clojure.lang.ExceptionInfo e
      ;; Re-throw size limit violations (don't swallow our own errors)
      (throw e))
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

(defn- constant-time-equals?
  "Constant-time string comparison to prevent timing attacks.
   Uses MessageDigest/isEqual which is designed for this purpose."
  [^String a ^String b]
  (when (and a b)
    (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8"))))

(defn- check-auth
  "Validate the auth token from request headers.
   Requires a non-blank configured token — rejects all requests if token is
   nil, empty, or whitespace-only.
   Uses constant-time comparison to prevent timing attacks."
  [req system]
  (let [expected (get-in system [:config :distributed :auth-token])
        provided (some-> (get-in req [:headers "authorization"])
                         (str/replace #"^Bearer " "")
                         str/trim)]
    (and (not (str/blank? expected))
         (boolean (constant-time-equals? expected provided)))))

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
