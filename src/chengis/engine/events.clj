(ns chengis.engine.events
  "Event bus for real-time build updates.
   Uses core.async pub/sub keyed by build-id.
   Events are persisted to DB (durable) before broadcasting via channel (ephemeral SSE)."
  (:require [clojure.core.async :as async :refer [chan pub sub unsub close!]]
            [chengis.db.build-event-store :as build-event-store]
            [chengis.engine.event-backpressure :as bp]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log]))

;; Default buffer size for the main event channel.
(def ^:private ^:const default-buffer-size 4096)

;; Buffered channel that all build events flow through.
;; Initialized with default buffer; use init-event-bus! to resize from config.
(defonce event-chan (chan default-buffer-size))

;; Publication that routes events by :build-id
(defonce event-pub (pub event-chan :build-id))

(defn init-event-bus!
  "Re-initialize the event channel with a buffer size from config.
   Must be called before any subscribers are active (i.e. at server startup).
   If the configured size differs from the default, replaces the channel and pub."
  [config]
  (let [buffer-size (get-in config [:event-bus :buffer-size] default-buffer-size)]
    (when (not= buffer-size default-buffer-size)
      (let [new-chan (chan buffer-size)
            new-pub (pub new-chan :build-id)]
        (close! event-chan)
        (alter-var-root #'event-chan (constantly new-chan))
        (alter-var-root #'event-pub (constantly new-pub))
        (log/info "Event bus re-initialized with buffer size:" buffer-size)))))

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

;; Subscriber tracking for stale cleanup.
;; {build-id -> #{[ch created-at-ms]}}
(defonce ^:private subscribers (atom {}))

;; Max age for a subscriber before it's considered stale (4 hours).
(def ^:private ^:const subscriber-stale-ms (* 4 3600 1000))

(defn subscribe
  "Subscribe to events for a specific build-id.
   Returns a channel that receives all events for that build."
  [build-id]
  (let [ch (chan (async/sliding-buffer 512))
        now (System/currentTimeMillis)]
    (sub event-pub build-id ch)
    (swap! subscribers update build-id (fnil conj #{}) [ch now])
    ch))

(defn unsubscribe
  "Unsubscribe a channel from a build-id topic and close it."
  [build-id ch]
  (unsub event-pub build-id ch)
  (close! ch)
  (swap! subscribers update build-id
         (fn [subs] (disj (or subs #{}) (first (filter #(= ch (first %)) subs))))))

(defn cleanup-stale-subscribers!
  "Remove subscriber channels older than subscriber-stale-ms.
   Called periodically from the server's scheduler."
  []
  (let [cutoff (- (System/currentTimeMillis) subscriber-stale-ms)
        stale-count (atom 0)]
    (doseq [[build-id subs] @subscribers
            [ch created-at] subs]
      (when (< created-at cutoff)
        (swap! stale-count inc)
        (try
          (unsub event-pub build-id ch)
          (close! ch)
          (catch Exception _))
        (swap! subscribers update build-id
               (fn [s] (disj (or s #{}) [ch created-at])))))
    ;; Clean up empty build-id entries
    (swap! subscribers (fn [m] (into {} (filter (fn [[_ s]] (seq s)) m))))
    (let [count @stale-count]
      (when (pos? count)
        (log/info "Cleaned up" count "stale event subscribers"))
      count)))

(defn subscriber-count
  "Return total number of active subscribers. Useful for monitoring."
  []
  (reduce + 0 (map (comp count val) @subscribers)))
