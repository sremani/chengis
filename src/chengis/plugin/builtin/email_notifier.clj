(ns chengis.plugin.builtin.email-notifier
  "Builtin email notification plugin.
   Sends build notifications via SMTP. Config-gated: requires
   :notifications :email :host to be set."
  (:require [chengis.plugin.protocol :as proto]
            [chengis.plugin.registry :as registry]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.util Properties]
           [javax.mail Session Message$RecipientType Transport]
           [javax.mail.internet MimeMessage InternetAddress]))

;; ---------------------------------------------------------------------------
;; SMTP helpers
;; ---------------------------------------------------------------------------

(defn- make-session
  "Create a javax.mail Session from email config."
  [{:keys [host port tls username password]}]
  (let [props (doto (Properties.)
                (.put "mail.smtp.host" (or host "localhost"))
                (.put "mail.smtp.port" (str (or port 587)))
                (.put "mail.smtp.auth" (str (boolean (and username password))))
                (.put "mail.smtp.starttls.enable" (str (boolean (not (false? tls)))))
                (.put "mail.smtp.connectiontimeout" "10000")
                (.put "mail.smtp.timeout" "10000"))]
    (if (and username password)
      (Session/getInstance props
        (proxy [javax.mail.Authenticator] []
          (getPasswordAuthentication []
            (javax.mail.PasswordAuthentication. username password))))
      (Session/getInstance props))))

;; ---------------------------------------------------------------------------
;; Email body formatting
;; ---------------------------------------------------------------------------

(defn- status-color [build-status]
  (case build-status
    :success "#28a745"
    :failure "#dc3545"
    :aborted "#ffc107"
    "#6c757d"))

(defn- format-duration [duration-ms]
  (when duration-ms
    (if (>= duration-ms 60000)
      (format "%.1f min" (/ duration-ms 60000.0))
      (format "%.1f sec" (/ duration-ms 1000.0)))))

(defn- build-html-body
  "Generate an HTML email body for a build notification."
  [{:keys [job-id build-number build-status build-id duration-ms stage-count]} config]
  (let [base-url (or (:base-url config) "http://localhost:8080")
        build-url (str base-url "/builds/" build-id)
        status-str (str/upper-case (name build-status))
        color (status-color build-status)
        duration-str (format-duration duration-ms)]
    (str
      "<!DOCTYPE html><html><body style='font-family: sans-serif; max-width: 600px; margin: 0 auto;'>"
      "<div style='border-left: 4px solid " color "; padding: 16px; margin: 16px 0; background: #f9f9f9;'>"
      "<h2 style='margin: 0 0 8px 0; color: " color ";'>Build #" build-number " — " status-str "</h2>"
      "<table style='border-collapse: collapse; width: 100%;'>"
      "<tr><td style='padding: 4px 8px; color: #666;'>Job</td><td style='padding: 4px 8px; font-weight: bold;'>" job-id "</td></tr>"
      "<tr><td style='padding: 4px 8px; color: #666;'>Status</td><td style='padding: 4px 8px;'>" status-str "</td></tr>"
      (when duration-str
        (str "<tr><td style='padding: 4px 8px; color: #666;'>Duration</td><td style='padding: 4px 8px;'>" duration-str "</td></tr>"))
      (when stage-count
        (str "<tr><td style='padding: 4px 8px; color: #666;'>Stages</td><td style='padding: 4px 8px;'>" stage-count "</td></tr>"))
      "</table>"
      "<p style='margin: 12px 0 0 0;'><a href='" build-url "' style='color: #0366d6;'>View Build Details</a></p>"
      "</div>"
      "<p style='color: #999; font-size: 12px;'>Sent by Chengis CI</p>"
      "</body></html>")))

(defn- build-subject
  "Generate an email subject line for a build notification."
  [{:keys [job-id build-number build-status]}]
  (let [icon (case build-status :success "✅" :failure "❌" :aborted "⚠️" "❓")]
    (str icon " Build #" build-number " " (name build-status) " — " job-id)))

;; ---------------------------------------------------------------------------
;; Send email
;; ---------------------------------------------------------------------------

(defn send-email!
  "Send an email via SMTP. Returns {:status :sent/:failed :details msg}."
  [email-config to subject html-body]
  (try
    (let [session (make-session email-config)
          from (or (:from email-config) "chengis@localhost")
          msg (doto (MimeMessage. session)
                (.setFrom (InternetAddress. from))
                (.setSubject subject)
                (.setContent html-body "text/html; charset=utf-8"))]
      ;; Add recipients
      (doseq [addr (if (string? to) [to] to)]
        (.addRecipient msg Message$RecipientType/TO (InternetAddress. addr)))
      (Transport/send msg)
      (log/info "Email sent to" (str/join ", " (if (string? to) [to] to)))
      {:status :sent :details (str "Email sent to " (count (if (string? to) [to] to)) " recipient(s)")})
    (catch Exception e
      (log/error "Email send failed:" (.getMessage e))
      {:status :failed :details (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Email Notifier plugin
;; ---------------------------------------------------------------------------

(defrecord EmailNotifier []
  proto/Notifier
  (send-notification [_this build-result config]
    (let [email-config (or (:email config) config)
          host (:host email-config)
          recipients (or (:to config)
                         (:default-recipients email-config)
                         [])]
      (cond
        (str/blank? host)
        (do
          (log/debug "Email notification skipped: no SMTP host configured")
          {:status :failed :details "No SMTP host configured"})

        (empty? recipients)
        (do
          (log/debug "Email notification skipped: no recipients configured")
          {:status :failed :details "No recipients configured"})

        :else
        (let [subject (build-subject build-result)
              html-body (build-html-body build-result config)]
          (send-email! email-config recipients subject html-body))))))

;; ---------------------------------------------------------------------------
;; Plugin lifecycle
;; ---------------------------------------------------------------------------

(defn init!
  "Register the email notifier plugin."
  []
  (registry/register-plugin!
    (proto/plugin-descriptor
      "email-notifier" "0.1.0" "SMTP email notifier"
      :provides #{:notifier}))
  (registry/register-notifier! :email (->EmailNotifier)))
