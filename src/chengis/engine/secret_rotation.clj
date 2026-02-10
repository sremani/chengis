(ns chengis.engine.secret-rotation
  "Automatic secret rotation scheduler.
   Periodically checks for secrets due for rotation and executes rotation.
   Feature-flag gated via :secret-rotation."
  (:require [chengis.db.rotation-store :as rotation-store]
            [chengis.db.secret-store :as secret-store]
            [chengis.feature-flags :as ff]
            [taoensso.timbre :as log]
            [chime.core :as chime])
  (:import [java.time Instant Duration]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private rotation-scheduler (atom nil))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn hash-value
  "SHA-256 hash of a string. Returns hex-encoded string.
   Used to record previous value hash without storing the actual secret."
  [value]
  (when value
    (let [digest (MessageDigest/getInstance "SHA-256")
          hash-bytes (.digest digest (.getBytes ^String value "UTF-8"))]
      (apply str (map #(format "%02x" (bit-and % 0xff)) hash-bytes)))))

(defn- generate-random-secret
  "Generate a random secret value: 32 random bytes, base64-encoded."
  []
  (let [bytes (byte-array 32)
        rng (SecureRandom.)]
    (.nextBytes rng bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

;; ---------------------------------------------------------------------------
;; Rotation execution
;; ---------------------------------------------------------------------------

(defn rotate-secret!
  "Execute rotation for a single secret.
   1. Look up current secret value via the local secret store
   2. Record the current value's hash as previous-value-hash
   3. Generate new random secret value (32 bytes, base64)
   4. Store new secret via secret store
   5. Record version in secret_versions
   6. Update policy with mark-rotated!
   7. Cleanup old versions per max-versions
   8. Log the rotation

   Note: This is a 'generate random' rotation. Real-world rotation would
   call external APIs. This provides the framework."
  [system policy]
  (let [ds (:db system)
        config (:config system)
        {:keys [id org-id secret-name secret-scope max-versions]} policy]
    (try
      (log/info "Rotating secret:" secret-name "scope:" secret-scope "org:" org-id)
      ;; 1. Look up current secret value
      (let [current-value (secret-store/get-secret ds config secret-name
                            :scope (or secret-scope "global")
                            :org-id org-id)
            ;; 2. Hash the current value (if it exists)
            prev-hash (when current-value (hash-value current-value))
            ;; 3. Generate new random secret
            new-value (generate-random-secret)
            ;; 4. Store new secret
            _ (secret-store/set-secret! ds config secret-name new-value
                :scope (or secret-scope "global")
                :org-id org-id)
            ;; 5. Record version
            next-ver (inc (rotation-store/latest-version ds org-id secret-name
                            (or secret-scope "global")))]
        (rotation-store/record-version! ds
          {:org-id org-id
           :secret-name secret-name
           :secret-scope (or secret-scope "global")
           :version next-ver
           :rotated-by "system/rotation-scheduler"
           :rotation-reason "scheduled"
           :previous-value-hash prev-hash})
        ;; 6. Mark policy as rotated
        (rotation-store/mark-rotated! ds id)
        ;; 7. Cleanup old versions
        (when max-versions
          (rotation-store/cleanup-old-versions! ds org-id secret-name
            (or secret-scope "global") max-versions))
        (log/info "Secret rotated successfully:" secret-name "version:" next-ver)
        {:status :success :secret-name secret-name :version next-ver})
      (catch Exception e
        (log/error "Failed to rotate secret:" secret-name "-" (.getMessage e))
        {:status :error :secret-name secret-name :error (.getMessage e)}))))

;; ---------------------------------------------------------------------------
;; Periodic check functions
;; ---------------------------------------------------------------------------

(defn check-and-rotate!
  "Check all due policies and rotate. Called periodically."
  [system]
  (let [config (:config system)]
    (if-not (ff/enabled? config :secret-rotation)
      (do
        (log/debug "Secret rotation feature disabled, skipping check")
        {:status :disabled})
      (try
        (log/info "Checking for secrets due for rotation...")
        (let [ds (:db system)
              due-policies (rotation-store/policies-due-for-rotation ds)
              results (mapv #(rotate-secret! system %) due-policies)]
          (log/info "Rotation check complete:" (count due-policies) "policies processed")
          {:status :success
           :policies-checked (count due-policies)
           :results results})
        (catch Exception e
          (log/error "Rotation check failed:" (.getMessage e))
          {:status :error :error (.getMessage e)})))))

(defn check-notifications!
  "Check policies nearing rotation and log warnings."
  [system]
  (let [config (:config system)]
    (when (ff/enabled? config :secret-rotation)
      (try
        (let [ds (:db system)
              upcoming (rotation-store/policies-due-for-notification ds)]
          (doseq [policy upcoming]
            (log/warn "Secret rotation upcoming:"
              (:secret-name policy) "scope:" (:secret-scope policy)
              "org:" (:org-id policy)
              "next-rotation:" (:next-rotation-at policy)))
          {:status :success :upcoming-count (count upcoming)})
        (catch Exception e
          (log/error "Notification check failed:" (.getMessage e))
          {:status :error :error (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Scheduler lifecycle
;; ---------------------------------------------------------------------------

(defn start-rotation-scheduler!
  "Start a Chime periodic schedule for secret rotation.
   Interval from :secret-rotation :check-interval-hours config (default 6).
   Returns a closeable."
  [system]
  (when-let [existing @rotation-scheduler]
    (try (.close existing) (catch Exception _))
    (reset! rotation-scheduler nil))
  (let [interval-hours (get-in system [:config :secret-rotation :check-interval-hours] 6)
        interval-ms (* interval-hours 3600000)
        start-time (.plus (Instant/now) (Duration/ofMillis interval-ms))
        times (chime/periodic-seq start-time (Duration/ofMillis interval-ms))
        sched (chime/chime-at
                times
                (fn [_time]
                  (try
                    (check-notifications! system)
                    (check-and-rotate! system)
                    (catch Exception e
                      (log/error "Rotation scheduler error:" (.getMessage e))))))]
    (reset! rotation-scheduler sched)
    (log/info "Secret rotation scheduler started: running every" interval-hours "hours")
    sched))

(defn stop-rotation-scheduler!
  "Stop the rotation scheduler if running."
  []
  (when-let [sched @rotation-scheduler]
    (.close sched)
    (reset! rotation-scheduler nil)
    (log/info "Secret rotation scheduler stopped")))

(defn running?*
  "Check if the rotation scheduler is currently running."
  []
  (some? @rotation-scheduler))
