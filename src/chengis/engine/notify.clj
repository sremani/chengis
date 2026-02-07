(ns chengis.engine.notify
  "Build notification system.
   Supports console logging and Slack webhook notifications.
   Dispatches based on :type in notification config."
  (:require [chengis.db.notification-store :as notification-store]
            [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as plugin-reg]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Notification formatting
;; ---------------------------------------------------------------------------

(defn- status-emoji [build-status]
  (case build-status
    :success "✅"
    :failure "❌"
    :aborted "⚠️"
    "❓"))

(defn- status-color [build-status]
  (case build-status
    :success "#36a64f"
    :failure "#dc3545"
    :aborted "#ffc107"
    "#6c757d"))

(defn- build-summary
  "Create a human-readable build summary string."
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
;; Console notifier
;; ---------------------------------------------------------------------------

(defn- send-console!
  "Log the build result to the console (always on)."
  [build-result _config]
  (let [msg (build-summary build-result)]
    (case (:build-status build-result)
      :success (log/info "📢 BUILD NOTIFICATION:" msg)
      :failure (log/error "📢 BUILD NOTIFICATION:" msg)
      :aborted (log/warn "📢 BUILD NOTIFICATION:" msg)
      (log/info "📢 BUILD NOTIFICATION:" msg))
    {:status :sent :details msg}))

;; ---------------------------------------------------------------------------
;; Slack notifier
;; ---------------------------------------------------------------------------

(defn- slack-payload
  "Build a Slack Block Kit message payload."
  [{:keys [job-id build-number build-status build-id duration-ms stage-count]} config]
  (let [base-url (or (:base-url config) "http://localhost:8080")
        build-url (str base-url "/builds/" build-id)
        duration-str (when duration-ms
                       (if (>= duration-ms 60000)
                         (format "%.1f min" (/ duration-ms 60000.0))
                         (format "%.1f sec" (/ duration-ms 1000.0))))]
    {:channel (or (:channel config) "#builds")
     :username "Chengis CI"
     :icon_emoji ":construction_worker:"
     :attachments
     [{:color (status-color build-status)
       :blocks
       [{:type "section"
         :text {:type "mrkdwn"
                :text (str (status-emoji build-status) " *Build #" build-number
                           "* for *" job-id "* — "
                           (str/upper-case (name build-status)))}}
        {:type "section"
         :fields
         (cond-> [{:type "mrkdwn" :text (str "*Status:*\n" (name build-status))}
                  {:type "mrkdwn" :text (str "*Build:*\n#" build-number)}]
           duration-str (conj {:type "mrkdwn" :text (str "*Duration:*\n" duration-str)})
           stage-count  (conj {:type "mrkdwn" :text (str "*Stages:*\n" stage-count)}))}
        {:type "actions"
         :elements
         [{:type "button"
           :text {:type "plain_text" :text "View Build"}
           :url build-url}]}]}]}))

(defn- send-slack!
  "Send a Slack webhook notification."
  [build-result config]
  (let [webhook-url (:webhook-url config)]
    (if (str/blank? webhook-url)
      (do
        (log/warn "Slack notification skipped: no webhook-url configured")
        {:status :failed :details "No webhook URL configured"})
      (try
        (let [payload (slack-payload build-result config)
              resp @(http/post webhook-url
                      {:headers {"Content-Type" "application/json"}
                       :body (json/write-str payload)
                       :timeout 10000})]
          (if (< (:status resp 500) 300)
            (do
              (log/info "Slack notification sent for build #" (:build-number build-result))
              {:status :sent :details (str "Slack webhook: " (:status resp))})
            (do
              (log/warn "Slack notification failed:" (:status resp) (:body resp))
              {:status :failed :details (str "HTTP " (:status resp) ": " (:body resp))})))
        (catch Exception e
          (log/error "Slack notification error:" (.getMessage e))
          {:status :failed :details (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defmulti send-notification!
  "Send a notification of the given type. Dispatches on (:type config)."
  (fn [_build-result config] (:type config)))

(defmethod send-notification! :console
  [build-result config]
  (send-console! build-result config))

(defmethod send-notification! "console"
  [build-result config]
  (send-console! build-result config))

(defmethod send-notification! :slack
  [build-result config]
  (send-slack! build-result config))

(defmethod send-notification! "slack"
  [build-result config]
  (send-slack! build-result config))

(defmethod send-notification! :default
  [_build-result config]
  (log/warn "Unknown notification type:" (:type config))
  {:status :failed :details (str "Unknown type: " (:type config))})

(defn dispatch-notifications!
  "Send all configured notifications for a build result.
   Persists notification records to the database if `ds` is provided.

   Arguments:
     ds             - database datasource (or nil to skip persistence)
     build-result   - map with :build-id :job-id :build-number :build-status etc.
     notify-configs - seq of notification config maps [{:type :slack :webhook-url ...}]
     system-config  - system :config map (for global notification settings)"
  [ds build-result notify-configs system-config]
  (let [;; Always add a console notifier
        all-configs (into [{:type :console}]
                          (or notify-configs []))
        ;; Enrich build-result with timing info
        enriched (cond-> build-result
                   (and (:started-at build-result) (:completed-at build-result))
                   (assoc :duration-ms
                          (try
                            (- (.toEpochMilli (java.time.Instant/parse (:completed-at build-result)))
                               (.toEpochMilli (java.time.Instant/parse (:started-at build-result))))
                            (catch Exception _ nil)))
                   (:stage-results build-result)
                   (assoc :stage-count (count (:stage-results build-result))))]
    (doseq [config all-configs]
      (let [;; Merge global notification settings (e.g., base-url)
            merged-config (merge (get-in system-config [:notifications (:type config)])
                                 config)
            ;; Record notification attempt
            record (when ds
                     (notification-store/save-notification! ds
                       {:build-id (:build-id enriched)
                        :type (:type config)
                        :status :pending}))
            ;; Send it — try plugin registry first, fall back to multimethod
            notifier-type (keyword (:type config))
            plugin-notifier (plugin-reg/get-notifier notifier-type)
            result (if plugin-notifier
                     (proto/send-notification plugin-notifier enriched merged-config)
                     (send-notification! enriched merged-config))]
        ;; Update record with result
        (when (and ds record)
          (notification-store/update-notification-status!
            ds (:id record) (:status result)
            :details (:details result)))))))
