(ns chengis.engine.health-check
  "Health check execution engine — HTTP endpoint and command-based checks
   with polling wait loop for deployment verification."
  (:require [chengis.db.health-check-store :as hc-store]
            [clojure.data.json :as json]
            [taoensso.timbre :as log])
  (:import [java.net HttpURLConnection URI]))

(defn- execute-http-check
  "Execute an HTTP health check. Returns result map."
  [config]
  (let [url-str (or (:url config) "http://localhost/health")
        expected-status (or (:expected-status config) 200)
        timeout-ms (or (:timeout-ms config) 5000)
        start-ms (System/currentTimeMillis)]
    (try
      (let [url (.toURL (URI. url-str))
            conn (doto ^HttpURLConnection (.openConnection url)
                   (.setRequestMethod (or (:method config) "GET"))
                   (.setConnectTimeout timeout-ms)
                   (.setReadTimeout timeout-ms))
            status (.getResponseCode conn)
            duration-ms (- (System/currentTimeMillis) start-ms)]
        ;; Drain response stream to allow connection reuse, then disconnect
        (try (slurp (.getInputStream conn)) (catch Exception _))
        (.disconnect conn)
        {:status (if (= expected-status status) "healthy" "unhealthy")
         :response-time-ms duration-ms
         :output (str "HTTP " status)})
      (catch java.net.SocketTimeoutException _
        {:status "timeout"
         :response-time-ms (- (System/currentTimeMillis) start-ms)
         :output "Connection timed out"})
      (catch Exception e
        {:status "error"
         :response-time-ms (- (System/currentTimeMillis) start-ms)
         :output (.getMessage e)}))))

(defn- execute-command-check
  "Execute a command-based health check. Returns result map."
  [config]
  (let [command (or (:command config) "echo ok")
        timeout-ms (or (:timeout-ms config) 5000)
        expected-exit (or (:expected-exit-code config) 0)
        start-ms (System/currentTimeMillis)]
    (try
      (let [pb (doto (ProcessBuilder. ["sh" "-c" command])
                 (.redirectErrorStream true))
            proc (.start pb)
            ;; Read output concurrently to avoid deadlock on large outputs
            output-future (future (try (slurp (.getInputStream proc)) (catch Exception _ "")))
            finished (.waitFor proc timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
            duration-ms (- (System/currentTimeMillis) start-ms)]
        (if finished
          (let [exit-code (.exitValue proc)
                output (deref output-future 1000 "")]
            {:status (if (= expected-exit exit-code) "healthy" "unhealthy")
             :response-time-ms duration-ms
             :output (subs output 0 (min (count output) 1000))})
          (do (.destroyForcibly proc)
              (future-cancel output-future)
              {:status "timeout"
               :response-time-ms duration-ms
               :output "Command timed out"})))
      (catch Exception e
        {:status "error"
         :response-time-ms (- (System/currentTimeMillis) start-ms)
         :output (.getMessage e)}))))

(defn execute-health-check
  "Execute a single health check based on its type and config.
   Returns {:status 'healthy'|'unhealthy'|'timeout'|'error' :response-time-ms N :output str}."
  [check-def]
  (let [config (if (map? (:config check-def))
                 (:config check-def)
                 (when-let [cj (:config-json check-def)]
                   (try (json/read-str cj :key-fn keyword) (catch Exception _ {}))))]
    (case (:check-type check-def)
      "http"    (execute-http-check config)
      "command" (execute-command-check config)
      {:status "error" :response-time-ms 0 :output (str "Unknown check type: " (:check-type check-def))})))

(defn run-environment-health-checks!
  "Run all enabled health checks for an environment and save results.
   Returns vector of result maps."
  [ds environment-id & {:keys [deployment-id]}]
  (let [checks (hc-store/list-health-checks ds environment-id :enabled-only true)]
    (mapv (fn [check]
            (let [result (execute-health-check check)
                  saved (hc-store/save-health-check-result! ds
                          (merge result
                            {:health-check-id (:id check)
                             :deployment-id deployment-id}))]
              (merge check result {:result-id (:id saved)})))
          checks)))

(defn wait-for-healthy!
  "Poll health checks until all pass or timeout.
   Returns {:healthy true/false :results [...] :reason str}."
  [ds environment-id & {:keys [deployment-id timeout-ms interval-ms retries]
                         :or {timeout-ms 300000 interval-ms 10000 retries 3}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [attempt 0]
      (if (> (System/currentTimeMillis) deadline)
        {:healthy false :results [] :reason "Health check timeout exceeded"}
        (let [results (run-environment-health-checks! ds environment-id
                        :deployment-id deployment-id)
              all-healthy? (every? #(= "healthy" (:status %)) results)]
          (if all-healthy?
            {:healthy true :results results}
            (if (>= attempt retries)
              {:healthy false :results results
               :reason (str "Health checks failed after " (inc retries) " attempts")}
              (do (Thread/sleep interval-ms)
                  (recur (inc attempt))))))))))
