(ns chengis.properties.notify-properties-test
  "Property-based tests for chengis.engine.notify.
   Verifies status-emoji/status-color mappings, build-summary formatting,
   and slack-payload structural invariants."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [chengis.generators :as cgen]
            [chengis.engine.notify]))

;; Access private functions via var references
(def status-emoji   #'chengis.engine.notify/status-emoji)
(def status-color   #'chengis.engine.notify/status-color)
(def build-summary  #'chengis.engine.notify/build-summary)
(def slack-payload  #'chengis.engine.notify/slack-payload)

;; ---------------------------------------------------------------------------
;; status-emoji — known statuses produce known emojis
;; ---------------------------------------------------------------------------

(defspec status-emoji-known-statuses 100
  (prop/for-all [status cgen/gen-build-status-keyword]
    (let [emoji (status-emoji status)]
      (and (string? emoji)
           (contains? #{"✅" "❌" "⚠️"} emoji)))))

;; ---------------------------------------------------------------------------
;; status-color — known statuses produce hex color strings
;; ---------------------------------------------------------------------------

(defspec status-color-known-statuses 100
  (prop/for-all [status cgen/gen-build-status-keyword]
    (let [color (status-color status)]
      (and (string? color)
           (str/starts-with? color "#")
           (contains? #{"#36a64f" "#dc3545" "#ffc107"} color)))))

;; ---------------------------------------------------------------------------
;; build-summary — contains build number and job-id
;; ---------------------------------------------------------------------------

(defspec build-summary-contains-key-info 100
  (prop/for-all [notif cgen/gen-build-notification]
    (let [summary (build-summary notif)]
      (and (string? summary)
           (str/includes? summary (str (:build-number notif)))
           (str/includes? summary (:job-id notif))
           (str/includes? summary (name (:build-status notif)))))))

;; ---------------------------------------------------------------------------
;; build-summary — duration formatting (min vs sec)
;; ---------------------------------------------------------------------------

(defspec build-summary-duration-format 100
  (prop/for-all [notif cgen/gen-build-notification]
    (let [summary (build-summary notif)
          duration-ms (:duration-ms notif)]
      (if (>= duration-ms 60000)
        (str/includes? summary "min")
        (str/includes? summary "sec")))))

;; ---------------------------------------------------------------------------
;; slack-payload — has required Slack structure
;; ---------------------------------------------------------------------------

(defspec slack-payload-structure 100
  (prop/for-all [notif cgen/gen-build-notification]
    (let [payload (slack-payload notif {})]
      (and (map? payload)
           (= "Chengis CI" (:username payload))
           (string? (:channel payload))
           (vector? (:attachments payload))
           (pos? (count (:attachments payload)))))))

;; ---------------------------------------------------------------------------
;; slack-payload — channel defaults to #builds or uses config
;; ---------------------------------------------------------------------------

(defspec slack-payload-channel-from-config 100
  (prop/for-all [notif cgen/gen-build-notification
                 channel (gen/not-empty gen/string-alphanumeric)]
    (let [payload-default (slack-payload notif {})
          payload-custom  (slack-payload notif {:channel channel})]
      (and (= "#builds" (:channel payload-default))
           (= channel (:channel payload-custom))))))
