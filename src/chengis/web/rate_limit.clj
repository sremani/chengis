(ns chengis.web.rate-limit
  "Token-bucket rate limiting middleware.
   Config-gated: when :rate-limit :enabled is false, all requests pass through.
   Uses per-IP token buckets stored in an atom. Resets on restart (by design)."
  (:require [clojure.string :as str]
            [chengis.metrics :as metrics]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Token bucket implementation
;; ---------------------------------------------------------------------------

;; Map of client-IP → {:tokens N :last-refill-ns long}
(defonce buckets* (atom {}))

(defn- now-nanos []
  (System/nanoTime))

(defn- refill-tokens
  "Refill tokens based on elapsed time since last refill.
   Returns updated bucket map."
  [{:keys [tokens last-refill-ns] :as bucket} max-tokens refill-rate-per-sec]
  (let [elapsed-ns (- (now-nanos) last-refill-ns)
        elapsed-sec (/ (double elapsed-ns) 1e9)
        new-tokens (min max-tokens (+ tokens (* elapsed-sec refill-rate-per-sec)))]
    (assoc bucket :tokens new-tokens :last-refill-ns (now-nanos))))

(defn- try-consume!
  "Attempt to consume one token from the bucket for the given key.
   The key is a composite [ip endpoint-type] to ensure separate buckets
   per endpoint type (auth endpoints get a lower limit than general).
   Returns true if allowed, false if rate limited."
  [bucket-key max-tokens refill-rate-per-sec]
  (let [result (atom false)]
    (swap! buckets*
      (fn [bkts]
        (let [bucket (get bkts bucket-key {:tokens max-tokens :last-refill-ns (now-nanos)})
              refilled (refill-tokens bucket max-tokens refill-rate-per-sec)]
          (if (>= (:tokens refilled) 1.0)
            (do (reset! result true)
                (assoc bkts bucket-key (update refilled :tokens - 1.0)))
            (do (reset! result false)
                (assoc bkts bucket-key refilled))))))
    @result))

(defn reset-all!
  "Reset all rate limit buckets. For testing."
  []
  (reset! buckets* {}))

;; ---------------------------------------------------------------------------
;; Stale bucket cleanup
;; ---------------------------------------------------------------------------

(def ^:private cleanup-interval-ns
  "Cleanup stale buckets every 5 minutes."
  (* 5 60 1e9))

(def ^:private stale-threshold-ns
  "Remove buckets idle for > 10 minutes."
  (* 10 60 1e9))

(defonce last-cleanup-ns* (atom (now-nanos)))

(defn- maybe-cleanup-stale!
  "Periodically remove stale IP entries from the buckets atom."
  []
  (let [now (now-nanos)]
    (when (> (- now @last-cleanup-ns*) cleanup-interval-ns)
      (reset! last-cleanup-ns* now)
      (swap! buckets*
        (fn [bkts]
          (into {}
            (filter (fn [[_ip {:keys [last-refill-ns]}]]
                      (< (- now last-refill-ns) stale-threshold-ns))
                    bkts)))))))

;; ---------------------------------------------------------------------------
;; Client IP extraction
;; ---------------------------------------------------------------------------

(defn get-client-ip
  "Extract the client IP from the request.
   Checks X-Forwarded-For first (for reverse proxies), falls back to :remote-addr."
  [req]
  (or (when-let [xff (get-in req [:headers "x-forwarded-for"])]
        (-> xff (str/split #",") first str/trim))
      (:remote-addr req)
      "unknown"))

;; ---------------------------------------------------------------------------
;; Endpoint classification
;; ---------------------------------------------------------------------------

(defn- classify-endpoint
  "Classify a request URI into an endpoint type for rate limiting.
   Returns :auth, :webhook, or :general."
  [uri]
  (cond
    (or (= uri "/login")
        (str/starts-with? (or uri "") "/api/auth/"))  :auth
    (= uri "/api/webhook")                             :webhook
    :else                                              :general))

;; ---------------------------------------------------------------------------
;; Rate limit response
;; ---------------------------------------------------------------------------

(defn- rate-limit-response
  "Return a 429 Too Many Requests response."
  [req retry-after-seconds]
  (let [uri (or (:uri req) "")]
    (if (or (str/starts-with? uri "/api/")
            (str/includes? (get-in req [:headers "accept"] "") "application/json"))
      {:status 429
       :headers {"Content-Type" "application/json"
                 "Retry-After" (str retry-after-seconds)}
       :body (str "{\"error\":\"Rate limit exceeded\",\"retry-after\":" retry-after-seconds "}")}
      {:status 429
       :headers {"Content-Type" "text/html; charset=utf-8"
                 "Retry-After" (str retry-after-seconds)}
       :body "<h1>429 Too Many Requests</h1><p>Please slow down and try again later.</p>"})))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(defn wrap-rate-limit
  "Ring middleware: rate limit requests by client IP.
   When :rate-limit :enabled is false, passes all requests through.
   Uses different limits for auth endpoints, webhooks, and general requests."
  [handler system]
  (let [config (:config system)
        enabled? (get-in config [:rate-limit :enabled] false)
        general-rpm (get-in config [:rate-limit :requests-per-minute] 60)
        auth-rpm (get-in config [:rate-limit :auth-requests-per-minute] 10)
        webhook-rpm (get-in config [:rate-limit :webhook-requests-per-minute] 120)
        registry (:metrics system)]
    (if-not enabled?
      handler  ;; pass-through when disabled
      (do
        (log/info "Rate limiting enabled:"
                  "general=" general-rpm "rpm,"
                  "auth=" auth-rpm "rpm,"
                  "webhook=" webhook-rpm "rpm")
        (fn [req]
          (maybe-cleanup-stale!)
          (let [ip (get-client-ip req)
                uri (or (:uri req) "")
                endpoint-type (classify-endpoint uri)
                [max-tokens refill-rate] (case endpoint-type
                                           :auth    [auth-rpm    (/ (double auth-rpm) 60.0)]
                                           :webhook [webhook-rpm (/ (double webhook-rpm) 60.0)]
                                           [general-rpm (/ (double general-rpm) 60.0)])]
            (if (try-consume! [ip endpoint-type] max-tokens refill-rate)
              (handler req)
              (do
                (log/debug "Rate limited" ip "on" uri "(" (name endpoint-type) ")")
                (try (metrics/record-rate-limit-rejected! registry endpoint-type)
                     (catch Exception _))
                (rate-limit-response req 60)))))))))
