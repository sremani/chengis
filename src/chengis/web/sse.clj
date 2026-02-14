(ns chengis.web.sse
  "Server-Sent Events endpoint for streaming build updates to the browser.
   Uses http-kit async channels + core.async event bus."
  (:require [org.httpkit.server :as http]
            [chengis.engine.events :as events]
            [chengis.web.views.components :as c]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Connection tracking and limits
;; ---------------------------------------------------------------------------

(defonce ^:private connections-per-build (atom {}))  ;; build-id -> count

(def ^:private ^:const default-max-sse-per-build 10)

(defn- try-acquire-connection!
  "Try to acquire an SSE connection slot for a build-id.
   Returns true if under the limit, false if at capacity."
  [build-id max-per-build]
  (let [acquired? (atom false)]
    (swap! connections-per-build
      (fn [m]
        (let [current (get m build-id 0)]
          (if (< current max-per-build)
            (do (reset! acquired? true)
                (assoc m build-id (inc current)))
            (do (reset! acquired? false)
                m)))))
    @acquired?))

(defn- release-connection!
  "Release an SSE connection slot for a build-id."
  [build-id]
  (swap! connections-per-build
    (fn [m]
      (let [current (get m build-id 0)
            new-count (max 0 (dec current))]
        (if (zero? new-count)
          (dissoc m build-id)
          (assoc m build-id new-count))))))

(defn active-connection-count
  "Return the current count of active SSE connections across all builds."
  []
  (reduce + (vals @connections-per-build)))

;; ---------------------------------------------------------------------------
;; Event rendering
;; ---------------------------------------------------------------------------

(defn- event->html
  "Convert a build event to an HTML fragment for htmx to swap in."
  [event]
  (case (:event-type event)
    :log-line
    (let [{:keys [line source stage-name step-name]} (:data event)]
      (str (h/html
             [:div {:class (str "py-0.5 " (when (= source :stderr) "text-red-400"))}
              (escape-html (str line))])))

    :step-completed
    (let [{:keys [step-name step-status stdout stderr stage-name]} (:data event)]
      (str (h/html
             [:div {:class "mb-3"}
              [:div {:class "text-blue-400 font-bold text-xs mb-1"}
               (str "--- " stage-name " / " step-name " ---")]
              (when (seq stdout)
                [:pre {:class "whitespace-pre-wrap text-green-300"} (escape-html stdout)])
              (when (seq stderr)
                [:pre {:class "whitespace-pre-wrap text-red-400"} (escape-html stderr)])])))

    :build-completed
    (let [{:keys [build-status]} (:data event)]
      (str (h/html
             [:div {:class "text-center py-4 text-gray-400 border-t border-gray-700 mt-4"}
              "Build completed: "
              (c/status-badge build-status)])))

    :build-cancelled
    (str (h/html
           [:div {:class "text-center py-4 text-orange-400 border-t border-gray-700 mt-4"}
            "Build cancelled "
            (c/status-badge :aborted)]))

    ;; Default: empty
    ""))

(defn- format-sse
  "Format as an SSE message string."
  [event-name data-str]
  (str "event: " event-name "\n"
       "data: " data-str "\n\n"))

(defn sse-handler
  "Returns a Ring handler that opens an SSE stream for a build's events.
   Limits the number of concurrent SSE connections per build-id."
  [system build-id]
  (let [max-per-build (get-in system [:config :event-bus :max-sse-per-build]
                              default-max-sse-per-build)]
    (fn [req]
      (if-not (try-acquire-connection! build-id max-per-build)
        ;; Too many SSE connections for this build
        (do
          (log/warn "SSE connection limit reached for build:" build-id
                    "(max:" max-per-build ")")
          {:status 429
           :headers {"Content-Type" "application/json"
                     "Retry-After" "10"}
           :body (str "{\"error\":\"Too many SSE connections for this build\"}")})
        ;; Connection acquired — proceed
        (let [event-ch-atom (atom nil)
              connection-released? (atom false)]
          (http/as-channel req
            {:on-open
             (fn [channel]
               (log/info "SSE client connected for build:" build-id)
               ;; Set SSE headers
               (http/send! channel
                 {:status 200
                  :headers {"Content-Type" "text/event-stream"
                            "Cache-Control" "no-cache"
                            "Connection" "keep-alive"
                            "X-Accel-Buffering" "no"}}
                 false)
               ;; Subscribe to events
               (let [event-ch (events/subscribe build-id)]
                 (reset! event-ch-atom event-ch)
                 (http/send! channel (format-sse "connected" "{}") false)
                 ;; Pump events from core.async -> SSE
                 (async/go-loop []
                   (if-let [event (async/<! event-ch)]
                     (let [html (event->html event)
                           event-name (name (or (:event-type event) :unknown))]
                       (when (seq html)
                         (try
                           (http/send! channel (format-sse event-name html) false)
                           (catch Exception e
                             (log/debug "SSE send failed:" (.getMessage e)))))
                       (if (= :build-completed (:event-type event))
                         (do
                           (log/info "SSE: build completed, closing stream for" build-id)
                           (events/unsubscribe build-id event-ch)
                           (reset! event-ch-atom nil)
                           (when (compare-and-set! connection-released? false true)
                             (release-connection! build-id))
                           (http/close channel))
                         (recur)))
                     ;; Channel closed (unsubscribed)
                     (do
                       (when (compare-and-set! connection-released? false true)
                         (release-connection! build-id))
                       (http/close channel))))))

             :on-close
             (fn [channel status]
               (log/info "SSE client disconnected for build:" build-id "status:" status)
               ;; Release connection slot
               (when (compare-and-set! connection-released? false true)
                 (release-connection! build-id))
               ;; Clean up event subscription if still active
               (when-let [ch @event-ch-atom]
                 (log/debug "SSE: cleaning up orphaned event channel for" build-id)
                 (events/unsubscribe build-id ch)
                 (reset! event-ch-atom nil)))}))))))


;; ---------------------------------------------------------------------------
;; Global event SSE endpoint (for browser notifications)
;; ---------------------------------------------------------------------------

(defn global-sse-handler
  "Returns a Ring handler that opens a global SSE stream.
   Subscribes to the :global topic for org-wide build-completed events.
   Used by browser notifications to show OS-level alerts."
  [_system]
  (fn [req]
    (let [event-ch-atom (atom nil)]
      (http/as-channel req
        {:on-open
         (fn [channel]
           (log/info "Global SSE client connected")
           (http/send! channel
             {:status 200
              :headers {"Content-Type" "text/event-stream"
                        "Cache-Control" "no-cache"
                        "Connection" "keep-alive"
                        "X-Accel-Buffering" "no"}}
             false)
           (let [event-ch (events/subscribe :global)]
             (reset! event-ch-atom event-ch)
             (http/send! channel (format-sse "connected" "{}") false)
             (async/go-loop []
               (if-let [event (async/<! event-ch)]
                 (do
                   (when (= :build-completed (:event-type event))
                     (let [data {:buildId (:build-id event)
                                 :operation (get-in event [:data :operation] "Build")
                                 :status (name (or (get-in event [:data :build-status]) :unknown))}]
                       (try
                         (http/send! channel
                           (format-sse "build-completed" (json/write-str data))
                           false)
                         (catch Exception e
                           (log/debug "Global SSE send failed:" (.getMessage e))))))
                   (recur))
                 (http/close channel)))))

         :on-close
         (fn [channel status]
           (log/info "Global SSE client disconnected, status:" status)
           (when-let [ch @event-ch-atom]
             (events/unsubscribe :global ch)
             (reset! event-ch-atom nil)))}))))
