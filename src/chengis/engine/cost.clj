(ns chengis.engine.cost
  "Build cost attribution engine.
   Computes cost based on build duration and configured cost-per-hour.
   Feature-flag gated via :cost-attribution."
  (:require [chengis.db.cost-store :as cost-store]
            [chengis.feature-flags :as ff]
            [taoensso.timbre :as log])
  (:import [java.time Instant Duration]
           [java.time.temporal ChronoUnit]))

(defn compute-build-cost
  "Compute the cost of a build from its duration and cost-per-hour rate.
   Returns a map with :duration-s and :computed-cost."
  [started-at ended-at cost-per-hour]
  (when (and started-at ended-at)
    (try
      (let [start (Instant/parse (str started-at))
            end (Instant/parse (str ended-at))
            duration-ms (.between ChronoUnit/MILLIS start end)
            duration-s (/ (double duration-ms) 1000.0)
            duration-hours (/ duration-s 3600.0)
            cost (* duration-hours (or cost-per-hour 1.0))]
        {:duration-s duration-s
         :computed-cost cost})
      (catch Exception e
        (log/debug "Failed to compute build cost:" (.getMessage e))
        nil))))

(defn record-cost-if-enabled!
  "Record build cost if the :cost-attribution feature flag is enabled.
   Called after build completion."
  [system {:keys [build-id job-id org-id agent-id started-at ended-at]}]
  (when (ff/enabled? (:config system) :cost-attribution)
    (let [cost-per-hour (get-in system [:config :cost-attribution :default-cost-per-hour] 1.0)
          cost-data (compute-build-cost started-at ended-at cost-per-hour)]
      (when cost-data
        (try
          (cost-store/record-build-cost! (:db system)
            {:build-id build-id
             :job-id job-id
             :org-id (or org-id "default-org")
             :agent-id agent-id
             :started-at started-at
             :ended-at ended-at
             :duration-s (:duration-s cost-data)
             :cost-per-hour cost-per-hour
             :computed-cost (:computed-cost cost-data)})
          (log/debug "Recorded cost for build" build-id ":" (:computed-cost cost-data))
          (catch Exception e
            (log/warn "Failed to record build cost:" (.getMessage e))))))))

;; ---------------------------------------------------------------------------
;; Query wrappers
;; ---------------------------------------------------------------------------

(defn get-build-cost
  "Get cost for a specific build."
  [system build-id]
  (when (ff/enabled? (:config system) :cost-attribution)
    (cost-store/get-build-cost (:db system) build-id)))

(defn get-org-cost-summary
  "Get cost summary grouped by job for an org."
  [system & {:keys [org-id limit]}]
  (if-not (ff/enabled? (:config system) :cost-attribution)
    []
    (cost-store/get-org-cost-summary (:db system)
      :org-id org-id :limit (or limit 50))))

(defn get-total-cost
  "Get total cost for an org."
  [system & {:keys [org-id]}]
  (if-not (ff/enabled? (:config system) :cost-attribution)
    0.0
    (cost-store/get-total-cost (:db system) :org-id org-id)))
