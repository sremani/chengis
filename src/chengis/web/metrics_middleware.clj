(ns chengis.web.metrics-middleware
  "Ring middleware for collecting HTTP request metrics.
   Path normalization prevents label cardinality explosion."
  (:require [chengis.metrics :as metrics]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Path normalization
;; ---------------------------------------------------------------------------

(def ^:private uuid-pattern
  "Regex matching UUID strings (standard 8-4-4-4-12 format) and short hex IDs (8-32 chars)."
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|[0-9a-fA-F]{8,32}")

(def ^:private resource-prefixes
  "Path segments after which the next segment is a resource ID."
  #{"builds" "jobs" "agents" "pipelines" "users"})

(defn normalize-path
  "Replace dynamic path segments (UUIDs, resource IDs) with {id}
   to prevent Prometheus label cardinality explosion.

   Examples:
     /builds/abc123-def  -> /builds/{id}
     /jobs/my-job/trigger -> /jobs/{id}/trigger
     /api/builds/abc123/events -> /api/builds/{id}/events
     /admin/users/u-123 -> /admin/users/{id}
     /health -> /health (unchanged)"
  [path]
  (if (or (nil? path) (= "/" path))
    path
    (let [segments (str/split path #"/")
          normalized (loop [idx 0
                            result []
                            replace-next? false]
                       (if (>= idx (count segments))
                         result
                         (let [seg (nth segments idx)]
                           (cond
                             ;; Empty segment (leading /)
                             (str/blank? seg)
                             (recur (inc idx) (conj result seg) false)

                             ;; Previous segment was a resource prefix — replace this one
                             replace-next?
                             (recur (inc idx) (conj result "{id}") false)

                             ;; This segment matches UUID pattern
                             (re-matches uuid-pattern seg)
                             (recur (inc idx) (conj result "{id}") false)

                             ;; This segment is a known resource prefix
                             (contains? resource-prefixes seg)
                             (recur (inc idx) (conj result seg) true)

                             ;; Regular segment
                             :else
                             (recur (inc idx) (conj result seg) false)))))]
      (str/join "/" normalized))))

;; ---------------------------------------------------------------------------
;; Ring middleware
;; ---------------------------------------------------------------------------

(defn wrap-http-metrics
  "Ring middleware that records HTTP request duration and count.
   Should be placed as the outermost middleware to capture full latency.
   No-ops when registry is nil (metrics disabled)."
  [handler registry]
  (if registry
    (fn [req]
      (let [start-ns (System/nanoTime)
            resp (handler req)]
        (try
          (let [duration-s (/ (double (- (System/nanoTime) start-ns)) 1e9)
                method (or (:request-method req) :get)
                path (normalize-path (or (:uri req) "/"))
                status (or (:status resp) 0)]
            (metrics/record-http-request! registry method path status duration-s))
          (catch Exception e
            (log/debug "Failed to record HTTP metric:" (.getMessage e))))
        resp))
    ;; Registry nil — just pass through
    handler))
