(ns chengis.web.sse
  "Server-Sent Events endpoint for streaming build updates to the browser.
   Uses http-kit async channels + core.async event bus."
  (:require [org.httpkit.server :as http]
            [chengis.engine.events :as events]
            [chengis.web.views.components :as c]
            [clojure.core.async :as async]
            [hiccup2.core :as h]
            [hiccup.util :refer [escape-html]]
            [taoensso.timbre :as log]))

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

    ;; Default: empty
    ""))

(defn- format-sse
  "Format as an SSE message string."
  [event-name data-str]
  (str "event: " event-name "\n"
       "data: " data-str "\n\n"))

(defn sse-handler
  "Returns a Ring handler that opens an SSE stream for a build's events."
  [system build-id]
  (fn [req]
    ;; Atom to store the event channel for cleanup on disconnect
    (let [event-ch-atom (atom nil)]
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
                       (http/close channel))
                     (recur)))
                 ;; Channel closed (unsubscribed)
                 (http/close channel)))))

         :on-close
         (fn [channel status]
           (log/info "SSE client disconnected for build:" build-id "status:" status)
           ;; Clean up event subscription if still active
           (when-let [ch @event-ch-atom]
             (log/debug "SSE: cleaning up orphaned event channel for" build-id)
             (events/unsubscribe build-id ch)
             (reset! event-ch-atom nil)))}))))
