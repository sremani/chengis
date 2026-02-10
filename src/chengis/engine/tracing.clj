(ns chengis.engine.tracing
  "Custom span-based distributed tracing stored in DB.
   Avoids heavyweight OpenTelemetry Java SDK — uses simple DB-backed spans
   with OTLP-compatible JSON export for Jaeger/Tempo.

   All tracing functions are gated by the :tracing feature flag.
   When disabled, all operations are no-ops."
  (:require [chengis.db.trace-store :as trace-store]
            [chengis.engine.log-context :as log-ctx]
            [chengis.feature-flags :as ff]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]
           [java.time Instant]))

;; ---------------------------------------------------------------------------
;; ID Generation
;; ---------------------------------------------------------------------------

(def ^:private random (SecureRandom.))

(defn generate-trace-id
  "Generate a 32-character hex trace ID."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

(defn generate-span-id
  "Generate a 16-character hex span ID."
  []
  (let [bytes (byte-array 8)]
    (.nextBytes random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;; ---------------------------------------------------------------------------
;; Sampling
;; ---------------------------------------------------------------------------

(defn should-sample?
  "Probabilistic sampling decision based on configured sample rate (0.0-1.0)."
  [config]
  (let [rate (get-in config [:tracing :sample-rate] 1.0)]
    (< (.nextDouble random) rate)))

;; ---------------------------------------------------------------------------
;; Span lifecycle
;; ---------------------------------------------------------------------------

(defn start-span!
  "Create and persist a new trace span. Returns the span map (including :span-id).
   No-ops (returns nil) when tracing is disabled or sampling rejects."
  [system {:keys [trace-id parent-span-id service-name operation kind
                  build-id org-id attributes]}]
  (when (and (:db system)
             (ff/enabled? (:config system) :tracing))
    (try
      (let [span-id (generate-span-id)
            trace-id (or trace-id (generate-trace-id))
            span (trace-store/create-span! (:db system)
                   {:trace-id trace-id
                    :span-id span-id
                    :parent-span-id parent-span-id
                    :service-name (or service-name "chengis-master")
                    :operation operation
                    :kind (or kind "INTERNAL")
                    :status "OK"
                    :started-at (str (Instant/now))
                    :attributes attributes
                    :build-id build-id
                    :org-id org-id})]
        (try
          (metrics/record-tracing-span-created! (:metrics system))
          (catch Exception _))
        span)
      (catch Exception e
        (log/debug "Failed to start span:" (.getMessage e))
        nil))))

(defn end-span!
  "Complete a span with end time, duration, and final status.
   No-ops when span is nil (tracing disabled)."
  [system span & {:keys [status attributes]}]
  (when (and span (:db system))
    (try
      (let [started (Instant/parse (:started-at span))
            ended (Instant/now)
            duration-ms (.toMillis (java.time.Duration/between started ended))]
        (trace-store/update-span! (:db system) (:span-id span)
          {:ended-at (str ended)
           :duration-ms duration-ms
           :status (or status "OK")
           :attributes attributes})
        (try
          (metrics/record-tracing-span-duration! (:metrics system) (/ (double duration-ms) 1000.0))
          (catch Exception _)))
      (catch Exception e
        (log/debug "Failed to end span:" (.getMessage e))))))

(defmacro with-span
  "Execute body within a traced span. Automatically starts and ends the span,
   catching exceptions to set ERROR status. Binds the span to span-sym.

   Usage:
     (with-span [span system {:operation \"build\" :build-id id}]
       (do-work span))"
  [[span-sym system span-opts] & body]
  `(let [~span-sym (start-span! ~system ~span-opts)]
     (try
       (let [result# (do ~@body)]
         (end-span! ~system ~span-sym)
         result#)
       (catch Exception e#
         (end-span! ~system ~span-sym :status "ERROR"
                    :attributes {:error (.getMessage e#)})
         (throw e#)))))

;; ---------------------------------------------------------------------------
;; Query helpers
;; ---------------------------------------------------------------------------

(defn get-trace
  "Get all spans for a trace. Returns nil when tracing disabled."
  [system trace-id]
  (when (and (:db system) (ff/enabled? (:config system) :tracing))
    (trace-store/get-trace (:db system) trace-id)))

(defn get-build-traces
  "Get all spans for a build. Returns nil when tracing disabled."
  [system build-id]
  (when (and (:db system) (ff/enabled? (:config system) :tracing))
    (trace-store/get-build-traces (:db system) build-id)))

(defn list-traces
  "List recent root spans. Returns nil when tracing disabled."
  [system & {:keys [org-id limit]}]
  (when (and (:db system) (ff/enabled? (:config system) :tracing))
    (trace-store/list-traces (:db system) :org-id org-id :limit (or limit 50))))

(defn export-otlp
  "Export a trace in OTLP-compatible JSON format."
  [system trace-id]
  (when (and (:db system) (ff/enabled? (:config system) :tracing))
    (trace-store/export-trace-otlp (:db system) trace-id)))
