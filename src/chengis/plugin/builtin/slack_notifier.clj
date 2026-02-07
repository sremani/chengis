(ns chengis.plugin.builtin.slack-notifier
  "Builtin Slack webhook notification plugin.
   Sends build notifications to Slack using Block Kit formatting."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Formatting helpers
;; ---------------------------------------------------------------------------

(defn- status-emoji [build-status]
  (case build-status :success "✅" :failure "❌" :aborted "⚠️" "❓"))

(defn- status-color [build-status]
  (case build-status :success "#36a64f" :failure "#dc3545" :aborted "#ffc107" "#6c757d"))

(defn- slack-payload
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

;; ---------------------------------------------------------------------------
;; Slack Notifier
;; ---------------------------------------------------------------------------

(defrecord SlackNotifier []
  proto/Notifier
  (send-notification [_this build-result config]
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
            {:status :failed :details (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the Slack notifier plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "slack-notifier" "0.1.0" "Slack webhook notifier"
      :provides #{:notifier}))
  (registry/register-notifier! :slack (->SlackNotifier)))
