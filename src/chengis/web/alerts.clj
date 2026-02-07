(ns chengis.web.alerts
  "In-process alert checking for the Chengis dashboard.
   Returns alert maps based on simple threshold checks on build status."
  (:require [chengis.db.build-store :as build-store]
            [chengis.engine.build-runner :as build-runner]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; HTML escaping (prevent XSS in fragment handler)
;; ---------------------------------------------------------------------------

(defn- escape-html
  "Escape HTML special characters to prevent XSS."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#x27;")))

;; ---------------------------------------------------------------------------
;; Alert thresholds
;; ---------------------------------------------------------------------------

(def ^:private failure-rate-critical 0.5)  ;; >50% of recent builds failed
(def ^:private max-concurrent-builds 4)    ;; thread pool size
(def ^:private long-running-minutes 30)    ;; warn if build exceeds this

;; ---------------------------------------------------------------------------
;; Alert checks
;; ---------------------------------------------------------------------------

(defn- check-failure-rate
  "Check if the recent build failure rate exceeds the critical threshold."
  [ds]
  (try
    (let [builds (build-store/list-builds ds)
          recent (take 20 builds)
          total (count recent)]
      (when (>= total 5) ;; Need at least 5 builds to be meaningful
        (let [failed (count (filter #(= :failure (:status %)) recent))
              rate (/ (double failed) total)]
          (when (> rate failure-rate-critical)
            {:level :critical
             :metric "build-failure-rate"
             :value (Math/round (* rate 100.0))
             :message (str (Math/round (* rate 100.0)) "% of recent builds failed ("
                           failed "/" total ")")}))))
    (catch Exception e
      (log/warn "Failed to check failure rate:" (.getMessage e))
      nil)))

(defn- check-build-queue
  "Check if the build queue is at capacity."
  []
  (try
    (let [active-count (count (build-runner/get-active-build-ids))]
      (when (>= active-count max-concurrent-builds)
        {:level :warning
         :metric "build-queue-full"
         :value active-count
         :message (str "Build queue at capacity (" active-count "/" max-concurrent-builds ")")}))
    (catch Exception e
      (log/warn "Failed to check build queue:" (.getMessage e))
      nil)))

(defn- check-long-running-builds
  "Check for builds that have been running too long."
  [ds]
  (try
    (let [builds (build-store/list-builds ds)
          running (filter #(= :running (:status %)) builds)
          now-ms (System/currentTimeMillis)]
      (seq
        (keep (fn [build]
                (when-let [created (:created-at build)]
                  (try
                    (let [created-ms (.toEpochMilli (java.time.Instant/parse (str created)))
                          elapsed-min (/ (double (- now-ms created-ms)) 60000.0)]
                      (when (> elapsed-min long-running-minutes)
                        {:level :warning
                         :metric "long-running-build"
                         :value (Math/round elapsed-min)
                         :message (str "Build " (:id build) " running for "
                                       (Math/round elapsed-min) " minutes")}))
                    (catch Exception _ nil))))
              running)))
    (catch Exception e
      (log/warn "Failed to check long-running builds:" (.getMessage e))
      nil)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn check-alerts
  "Run all alert checks and return a vector of alert maps.
   Each alert: {:level :warning/:critical :metric string :value number :message string}"
  [system]
  (let [ds (:db system)]
    (filterv some?
      (concat
        [(check-failure-rate ds)
         (check-build-queue)]
        (check-long-running-builds ds)))))

(defn alerts-handler
  "Ring handler that returns current alerts as JSON.
   Used for htmx polling on the dashboard."
  [system]
  (fn [_req]
    (let [alerts (check-alerts system)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:alerts alerts})})))

(defn alerts-fragment-handler
  "Ring handler that returns alerts as an HTML fragment for htmx swap.
   Used by hx-get for live alert updates."
  [system]
  (fn [_req]
    (let [alerts (check-alerts system)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str
               (if (empty? alerts)
                 ""
                 (apply str
                   (map (fn [{:keys [level message]}]
                          (let [color (if (= :critical level) "red" "yellow")]
                            (str "<div class=\"flex items-center gap-2 px-4 py-2 rounded-lg bg-"
                                 color "-50 border border-" color "-200 mb-2\">"
                                 "<span class=\"inline-block w-2 h-2 rounded-full bg-"
                                 color "-500\"></span>"
                                 "<span class=\"text-sm text-" color "-800\">"
                                 (escape-html message) "</span></div>")))
                        alerts))))})))
