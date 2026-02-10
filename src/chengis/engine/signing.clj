(ns chengis.engine.signing
  "Artifact signing engine.
   Supports cosign and GPG for signing build artifacts.
   Feature-flag gated via :artifact-signing."
  (:require [chengis.feature-flags :as feature-flags]
            [chengis.engine.process :as process]
            [chengis.db.signature-store :as signature-store]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]
           [java.math BigInteger]
           [java.io File]))

;; ---------------------------------------------------------------------------
;; Command builders
;; ---------------------------------------------------------------------------

(defn build-cosign-command
  "Build a cosign sign-blob command string."
  [key-ref artifact-path sig-output-path]
  (str "cosign sign-blob --yes --key " key-ref
       " --output-signature " sig-output-path
       " " artifact-path))

(defn build-gpg-command
  "Build a gpg detach-sign command string."
  [key-ref artifact-path sig-output-path]
  (str "gpg --batch --yes --detach-sign --armor --local-user " key-ref
       " --output " sig-output-path
       " " artifact-path))

;; ---------------------------------------------------------------------------
;; Digest computation
;; ---------------------------------------------------------------------------

(defn compute-digest
  "Compute SHA-256 hash of file contents. Returns hex string, or nil if file doesn't exist."
  [file-path]
  (let [f (File. (str file-path))]
    (when (.exists f)
      (try
        (let [digest (MessageDigest/getInstance "SHA-256")
              buffer (byte-array 8192)]
          (with-open [fis (java.io.FileInputStream. f)]
            (loop []
              (let [n (.read fis buffer)]
                (when (pos? n)
                  (.update digest buffer 0 n)
                  (recur)))))
          (format "%064x" (BigInteger. 1 (.digest digest))))
        (catch Exception e
          (log/warn "Failed to compute digest for" file-path ":" (.getMessage e))
          nil)))))

;; ---------------------------------------------------------------------------
;; Signing
;; ---------------------------------------------------------------------------

(defn sign-single-artifact!
  "Sign a single artifact using the configured tool (cosign or gpg).
   Returns the signature record map on success, or nil on skip/failure."
  [system artifact config]
  (let [tool (get config :tool "cosign")
        key-ref (get config :key-ref)
        timeout (get config :timeout 60000)
        ds (:db system)]
    (if-not key-ref
      (do (log/warn "No signing key-ref configured, skipping artifact" (:filename artifact))
          nil)
      (let [artifact-path (:path artifact)
            sig-output-path (str artifact-path ".sig")
            command (case tool
                      "cosign" (build-cosign-command key-ref artifact-path sig-output-path)
                      "gpg"    (build-gpg-command key-ref artifact-path sig-output-path)
                      (do (log/warn "Unknown signing tool:" tool)
                          nil))]
        (when command
          (let [result (process/execute-command {:command command
                                                 :timeout timeout})]
            (cond
              (zero? (:exit-code result))
              (let [sig-value (try (slurp sig-output-path)
                                   (catch Exception _ ""))
                    digest (compute-digest artifact-path)
                    sig-record {:artifact-id (:id artifact)
                                :build-id (:build-id artifact)
                                :job-id (:job-id artifact)
                                :org-id (or (:org-id artifact) "default-org")
                                :signer tool
                                :key-reference key-ref
                                :signature-value sig-value
                                :target-digest digest}]
                (signature-store/create-signature! ds sig-record)
                (log/info "Signed artifact" (:filename artifact) "with" tool)
                sig-record)

              (= 127 (:exit-code result))
              (do (log/warn "Signing tool not found:" tool
                            "- ensure it is installed on the build agent")
                  nil)

              :else
              (do (log/warn "Signing failed for" (:filename artifact)
                            "exit-code:" (:exit-code result)
                            "stderr:" (:stderr result))
                  nil))))))))

(defn sign-artifacts!
  "Sign all artifacts from a build result. Gated by :artifact-signing feature flag.
   Returns a list of signature records created."
  [system build-result]
  (if-not (feature-flags/enabled? (:config system) :artifact-signing)
    (do (log/debug "Artifact signing disabled, skipping")
        [])
    (let [config (get-in system [:config :signing])
          artifacts (:artifacts build-result)]
      (if (empty? artifacts)
        (do (log/debug "No artifacts to sign")
            [])
        (do (log/info "Signing" (count artifacts) "artifacts")
            (vec (keep #(sign-single-artifact! system % config) artifacts)))))))

;; ---------------------------------------------------------------------------
;; Verification
;; ---------------------------------------------------------------------------

(defn verify-signature!
  "Verify a stored signature record. Builds a verify command based on signer tool,
   runs it, and marks as verified in DB if successful.
   Returns {:verified? bool :error \"msg-if-failed\"}."
  [system signature-record]
  (let [tool (:signer signature-record)
        key-ref (:key-reference signature-record)
        artifact-id (:artifact-id signature-record)
        sig-id (:id signature-record)
        timeout (get-in system [:config :signing :timeout] 60000)
        ;; Write signature to a temp file for verification
        sig-file (File/createTempFile "chengis-verify-" ".sig")
        sig-path (.getAbsolutePath sig-file)]
    (try
      (spit sig-path (:signature-value signature-record))
      (let [;; Build the artifact path — for verify we need the original artifact
            ;; In practice the caller ensures the artifact is accessible
            artifact-path (or (:artifact-path signature-record)
                              (str "/tmp/chengis-artifact-" artifact-id))
            command (case tool
                      "cosign" (str "cosign verify-blob --key " key-ref
                                    " --signature " sig-path
                                    " " artifact-path)
                      "gpg"    (str "gpg --batch --verify " sig-path " " artifact-path)
                      nil)
            ds (:db system)]
        (if-not command
          {:verified? false :error (str "Unknown signing tool: " tool)}
          (let [result (process/execute-command {:command command :timeout timeout})]
            (if (zero? (:exit-code result))
              (do (signature-store/verify-signature! ds sig-id)
                  {:verified? true})
              {:verified? false
               :error (str "Verification failed (exit " (:exit-code result) "): "
                           (:stderr result))}))))
      (catch Exception e
        (log/warn "Signature verification error" {:id sig-id :error (.getMessage e)})
        {:verified? false :error (.getMessage e)})
      (finally
        (try (.delete sig-file) (catch Exception _ nil))))))
