(ns chengis.logging
  "Structured logging configuration.
   Supports text (default) and JSON output formats, configurable log levels,
   and optional file logging."
  (:require [taoensso.timbre :as timbre]
            [timbre-json-appender.core :as json-appender]))

(defn configure-logging!
  "Configure Timbre logging based on the application config map.
   Config keys used:
     :log :level   — Timbre log level keyword (default :info)
     :log :format  — :text (default println) or :json (structured JSON)
     :log :file    — optional file path for log output"
  [config]
  (let [level (get-in config [:log :level] :info)
        fmt   (get-in config [:log :format] :text)
        file  (get-in config [:log :file])]
    (case fmt
      ;; JSON structured logging — replaces all appenders with JSON output
      :json
      (do
        (json-appender/install {:min-level level :inline-args? true})
        ;; Add file appender alongside JSON stdout if file path specified
        (when file
          (timbre/merge-config!
            {:appenders {:spit (timbre/spit-appender {:fname file})}})))

      ;; Text logging (default for :text and any unknown format)
      (:text nil)
      (do
        (timbre/set-min-level! level)
        ;; Add file appender if specified
        (when file
          (timbre/merge-config!
            {:appenders {:spit (timbre/spit-appender {:fname file})}})))

      ;; Unknown format — warn and fall back to text
      (do
        (timbre/set-min-level! level)
        (timbre/warn "Unknown log format" fmt "— falling back to :text")
        (when file
          (timbre/merge-config!
            {:appenders {:spit (timbre/spit-appender {:fname file})}}))))))
