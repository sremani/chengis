(ns chengis.engine.events
  "Event bus for real-time build updates.
   Uses core.async pub/sub keyed by build-id.
   Events are persisted to DB (durable) before broadcasting via channel (ephemeral SSE)."
  (:require [clojure.core.async :as async :refer [chan pub sub unsub close!]]
            [chengis.db.build-event-store :as build-event-store]
            [chengis.engine.event-backpressure :as bp]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log]))

;; Buffered channel that all build events flow through
(defonce event-chan (chan 4096))

;; Publication that routes events by :build-id
(defonce event-pub (pub event-chan :build-id))

;; Metrics registry reference (set during server startup)
(defonce ^:private metrics-registry (atom nil))

;; Database datasource reference (set during server startup)
(defonce ^:private db-ref (atom nil))

;; Backpressure configuration (set during server startup)
(defonce backpressure-enabled? (atom false))
(defonce critical-timeout-ms (atom 5000))

(defn set-metrics-registry!
  "Set the Prometheus metrics registry for event bus instrumentation."
  [registry]
  (reset! metrics-registry registry))

(defn set-db!
  "Set the database datasource for durable event persistence.
   When set, publish! will persist events to DB before broadcasting."
  [ds]
  (reset! db-ref ds))

(defn set-backpressure!
  "Enable/disable backpressure mode. Called at startup from server.clj."
  [enabled? timeout-ms]
  (reset! backpressure-enabled? enabled?)
  (reset! critical-timeout-ms (or timeout-ms 5000)))

(defn publish!
  "Publish a build event. Event must contain :build-id and :event-type.
   First persists to DB (durable) when db-ref is set, then broadcasts
   via core.async channel (ephemeral SSE).
   Persistence failures are logged but do not prevent channel broadcast."
  [event]
  ;; 1. Persist to DB (durable) — best-effort, never blocks channel publish
  (when-let [ds @db-ref]
    (try
      (build-event-store/persist-event! ds event)
      (catch Exception e
        (log/warn "Event persistence failed for build" (:build-id event) "-"
                  (.getMessage e)))))
  ;; 2. Broadcast via channel (ephemeral SSE)
  (if @backpressure-enabled?
    ;; Backpressure path: critical events block with timeout, non-critical use offer!
    (let [result (bp/publish-with-backpressure! event-chan event @critical-timeout-ms)]
      (case result
        :published (do
                     (try (metrics/record-event-published! @metrics-registry)
                          (catch Exception _))
                     (when (= :build-completed (:event-type event))
                       (bp/publish-with-backpressure! event-chan (assoc event :build-id :global)
                                                      @critical-timeout-ms)))
        :timeout   (do
                     (try (metrics/record-event-overflow! @metrics-registry)
                          (catch Exception _))
                     (log/error "Event bus backpressure timeout:" (:event-type event)
                                "for build:" (:build-id event)))
        :dropped   (do
                     (try (metrics/record-event-overflow! @metrics-registry)
                          (catch Exception _))
                     (log/debug "Event bus backpressure drop:" (:event-type event)
                                "for build:" (:build-id event)))))
    ;; Original path: non-blocking offer! for all events
    (if (async/offer! event-chan event)
      (do
        (try (metrics/record-event-published! @metrics-registry)
             (catch Exception _))
        ;; 3. Also broadcast build-completed events to the :global topic
        ;;    for org-wide browser notifications
        (when (= :build-completed (:event-type event))
          (async/offer! event-chan (assoc event :build-id :global))))
      (do
        (try (metrics/record-event-overflow! @metrics-registry)
             (catch Exception _))
        (log/warn "Event bus full, dropping event:" (:event-type event) "for build:" (:build-id event))))))

(defn subscribe
  "Subscribe to events for a specific build-id.
   Returns a channel that receives all events for that build."
  [build-id]
  (let [ch (chan (async/sliding-buffer 512))]
    (sub event-pub build-id ch)
    ch))

(defn unsubscribe
  "Unsubscribe a channel from a build-id topic and close it."
  [build-id ch]
  (unsub event-pub build-id ch)
  (close! ch))
