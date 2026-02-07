(ns chengis.plugin.builtin.console-notifier
  "Builtin console notification plugin.
   Logs build results to the console."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Formatting helpers (extracted from notify.clj)
;; ---------------------------------------------------------------------------

(defn- status-emoji [build-status]
  (case build-status
    :success "✅"
    :failure "❌"
    :aborted "⚠️"
    "❓"))

(defn- build-summary
  [{:keys [job-id build-number build-status duration-ms stage-count]}]
  (let [duration-str (when duration-ms
                       (if (>= duration-ms 60000)
                         (format "%.1f min" (/ duration-ms 60000.0))
                         (format "%.1f sec" (/ duration-ms 1000.0))))]
    (str (status-emoji build-status) " "
         "Build #" build-number " for **" job-id "**: "
         (name build-status)
         (when duration-str (str " (" duration-str ")"))
         (when stage-count (str " — " stage-count " stages")))))

;; ---------------------------------------------------------------------------
;; Console Notifier
;; ---------------------------------------------------------------------------

(defrecord ConsoleNotifier []
  proto/Notifier
  (send-notification [_this build-result _config]
    (let [msg (build-summary build-result)]
      (case (:build-status build-result)
        :success (log/info "📢 BUILD NOTIFICATION:" msg)
        :failure (log/error "📢 BUILD NOTIFICATION:" msg)
        :aborted (log/warn "📢 BUILD NOTIFICATION:" msg)
        (log/info "📢 BUILD NOTIFICATION:" msg))
      {:status :sent :details msg})))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the console notifier plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "console-notifier" "0.1.0" "Built-in console log notifier"
      :provides #{:notifier}))
  (registry/register-notifier! :console (->ConsoleNotifier)))
