(ns chengis.plugin.protocol
  "Protocols and contracts for the Chengis plugin system.
   Each extension point defines a protocol that plugins implement.")

;; ---------------------------------------------------------------------------
;; Step Executor — executes a single build step
;; ---------------------------------------------------------------------------

(defprotocol StepExecutor
  "Protocol for executing build steps.
   Implementations receive a build-ctx and step-def, return a result map."
  (execute-step [this build-ctx step-def]
    "Execute a step. Returns:
     {:exit-code int
      :stdout string
      :stderr string
      :duration-ms long
      :timed-out? boolean (optional)
      :cancelled? boolean (optional)}"))

;; ---------------------------------------------------------------------------
;; Pipeline Format — parses pipeline definition files
;; ---------------------------------------------------------------------------

(defprotocol PipelineFormat
  "Protocol for parsing pipeline definition files."
  (parse-pipeline [this file-path]
    "Parse a pipeline file. Returns:
     {:pipeline {...}} on success
     {:error \"message\"} on failure")
  (detect-file [this workspace-dir]
    "Check if this format's file exists in the workspace.
     Returns the file path if found, nil otherwise."))

;; ---------------------------------------------------------------------------
;; Notifier — sends build notifications
;; ---------------------------------------------------------------------------

(defprotocol Notifier
  "Protocol for sending build notifications."
  (send-notification [this build-result config]
    "Send a notification. Returns:
     {:status :sent/:failed
      :details \"message\"}"))

;; ---------------------------------------------------------------------------
;; Artifact Handler — collects and stores build artifacts
;; ---------------------------------------------------------------------------

(defprotocol ArtifactHandler
  "Protocol for collecting and storing build artifacts."
  (collect-artifacts [this workspace-dir artifact-dir patterns]
    "Collect artifacts matching patterns. Returns seq of:
     [{:filename :path :size-bytes :content-type}]"))

;; ---------------------------------------------------------------------------
;; SCM Provider — source code management operations
;; ---------------------------------------------------------------------------

(defprotocol ScmProvider
  "Protocol for source code management operations."
  (checkout-source [this source-config workspace-dir commit-override]
    "Clone/checkout source code. Returns:
     {:success? boolean
      :git-info {:commit :branch :author ...}
      :error \"message\" (on failure)}"))

;; ---------------------------------------------------------------------------
;; Plugin descriptor
;; ---------------------------------------------------------------------------

(defn plugin-descriptor
  "Create a standard plugin descriptor map."
  [name version description & {:keys [provides config-spec]}]
  {:name name
   :version version
   :description description
   :provides (or provides #{})
   :config-spec (or config-spec {})})
