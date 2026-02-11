(ns chengis.engine.event-backpressure
  "Adaptive backpressure for the build event bus.
   Critical events (build lifecycle) use blocking put with timeout.
   Non-critical events (log lines) use non-blocking offer."
  (:require [clojure.core.async :as async]
            [taoensso.timbre :as log]))

(def critical-event-types
  "Event types that MUST NOT be silently dropped."
  #{:build-started :build-completed :build-cancelled
    :stage-started :stage-completed
    :step-started :step-completed})

(defn critical-event?
  "Returns true for events that should use blocking put with timeout."
  [event]
  (contains? critical-event-types (:event-type event)))

(defn publish-with-backpressure!
  "Publish an event with adaptive backpressure.
   Critical events: blocking put with timeout (never silently dropped).
   Non-critical events (log-line, etc.): offer! (drop if full).
   metrics-fn is an optional function called with (type result) for metrics.
   Returns :published, :dropped, or :timeout."
  [event-chan event timeout-ms & {:keys [metrics-fn]}]
  (if (critical-event? event)
    ;; Critical: use alt!! with timeout
    (let [result (async/alt!!
                   [[event-chan event]] :published
                   (async/timeout timeout-ms) :timeout)]
      (when (= :timeout result)
        (log/error "Critical event TIMEOUT: could not publish"
                   (:event-type event) "for build" (:build-id event)
                   "within" timeout-ms "ms"))
      (when metrics-fn (metrics-fn "critical" result))
      result)
    ;; Non-critical: offer! (non-blocking)
    (if (async/offer! event-chan event)
      (do (when metrics-fn (metrics-fn "non-critical" :published))
          :published)
      (do (log/debug "Non-critical event dropped:" (:event-type event)
                     "for build" (:build-id event))
          (when metrics-fn (metrics-fn "non-critical" :dropped))
          :dropped))))

(defn start-queue-depth-sampler
  "Start a background thread that periodically samples event channel buffer depth.
   Returns a stop function (call it to stop sampling).
   sample-fn is called with the depth value each interval.
   Uses reflection to access internal channel buffer (best-effort;
   gracefully returns 0 if the channel implementation changes)."
  [event-chan interval-ms sample-fn]
  (let [running (atom true)]
    (async/thread
      (while @running
        (try
          (let [depth (try
                        (let [buf (.buf event-chan)]
                          (if (and buf (instance? clojure.lang.Counted buf))
                            (.count ^clojure.lang.Counted buf)
                            0))
                        (catch Exception _ 0))]
            (sample-fn depth))
          (catch Exception e
            (log/debug "Queue depth sampling error:" (.getMessage e))))
        (Thread/sleep ^long interval-ms)))
    (fn [] (reset! running false))))
