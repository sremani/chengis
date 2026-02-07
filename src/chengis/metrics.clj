(ns chengis.metrics
  "Prometheus metrics registry and recording helpers.
   All record-* functions accept a registry as the first argument
   and no-op when nil, making metrics zero-overhead when disabled."
  (:require [iapetos.core :as prometheus]
            [iapetos.collector.jvm :as jvm-collector]
            [iapetos.export :as export]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Registry initialization
;; ---------------------------------------------------------------------------

(defn init-registry
  "Create and return a Prometheus collector registry with all metrics registered.
   Includes JVM metrics (heap, GC, threads) plus custom application metrics."
  []
  (log/info "Initializing Prometheus metrics registry")
  (-> (prometheus/collector-registry)
      ;; JVM metrics — heap, GC, threads, classloader
      (jvm-collector/initialize)

      ;; HTTP metrics
      (prometheus/register
        (prometheus/histogram :http/request-duration-seconds
                              {:description "HTTP request duration in seconds"
                               :labels [:method :path :status]
                               :buckets [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0]}))
      (prometheus/register
        (prometheus/counter :http/requests-total
                            {:description "Total HTTP requests"
                             :labels [:method :path :status]}))

      ;; Build metrics
      (prometheus/register
        (prometheus/gauge :builds/active
                          {:description "Currently executing builds"}))
      (prometheus/register
        (prometheus/counter :builds/total
                            {:description "Total builds completed"
                             :labels [:status]}))
      (prometheus/register
        (prometheus/histogram :builds/duration-seconds
                              {:description "Build wall-clock duration in seconds"
                               :labels [:status]
                               :buckets [1.0 5.0 10.0 30.0 60.0 120.0 300.0 600.0]}))

      ;; Stage/Step metrics
      (prometheus/register
        (prometheus/histogram :stages/duration-seconds
                              {:description "Pipeline stage duration in seconds"
                               :labels [:stage-name :status]
                               :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0 120.0]}))
      (prometheus/register
        (prometheus/histogram :steps/duration-seconds
                              {:description "Pipeline step duration in seconds"
                               :labels [:step-name :status]
                               :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0 120.0]}))

      ;; Event bus metrics
      (prometheus/register
        (prometheus/counter :events/published-total
                            {:description "Total events published to event bus"}))
      (prometheus/register
        (prometheus/counter :events/overflow-total
                            {:description "Events dropped due to channel overflow"}))

      ;; Dispatch metrics (Phase 3 — distributed queue)
      (prometheus/register
        (prometheus/gauge :queue/depth
                          {:description "Pending builds in dispatch queue"}))
      (prometheus/register
        (prometheus/gauge :queue/oldest-pending-seconds
                          {:description "Age of oldest pending queue item in seconds"}))
      (prometheus/register
        (prometheus/counter :dispatch/total
                            {:description "Build dispatch attempts"
                             :labels [:result]}))
      (prometheus/register
        (prometheus/counter :dispatch/orphans-recovered-total
                            {:description "Orphaned builds recovered from dead agents"}))
      (prometheus/register
        (prometheus/gauge :agents/circuit-breaker-open
                          {:description "Number of agents with open circuit breakers"}))
      (prometheus/register
        (prometheus/counter :artifacts/transferred-total
                            {:description "Artifact transfers from agents"
                             :labels [:result]}))
      (prometheus/register
        (prometheus/gauge :agents/utilization-ratio
                          {:description "Active builds / total capacity across all agents"}))

      ;; Auth metrics
      (prometheus/register
        (prometheus/counter :auth/login-total
                            {:description "Login attempts"
                             :labels [:result]}))
      (prometheus/register
        (prometheus/counter :auth/token-auth-total
                            {:description "API token authentication attempts"
                             :labels [:result]}))))

;; ---------------------------------------------------------------------------
;; Record helpers — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-http-request!
  "Record an HTTP request metric (duration histogram + counter)."
  [registry method path status duration-s]
  (when registry
    (let [labels {:method (name method) :path path :status (str status)}]
      (prometheus/observe (registry :http/request-duration-seconds labels) duration-s)
      (prometheus/inc (registry :http/requests-total labels)))))

(defn record-build-start!
  "Increment the active builds gauge."
  [registry]
  (when registry
    (prometheus/inc (registry :builds/active))))

(defn record-build-end!
  "Decrement active builds gauge and record build completion."
  [registry status duration-s]
  (when registry
    (let [status-str (name status)]
      (prometheus/dec (registry :builds/active))
      (prometheus/inc (registry :builds/total {:status status-str}))
      (prometheus/observe (registry :builds/duration-seconds {:status status-str}) duration-s))))

(defn record-stage-duration!
  "Record a stage execution duration."
  [registry stage-name status duration-s]
  (when registry
    (prometheus/observe (registry :stages/duration-seconds
                                  {:stage-name (str stage-name)
                                   :status (name status)})
                        duration-s)))

(defn record-step-duration!
  "Record a step execution duration."
  [registry step-name status duration-s]
  (when registry
    (prometheus/observe (registry :steps/duration-seconds
                                  {:step-name (str step-name)
                                   :status (name status)})
                        duration-s)))

(defn record-event-published!
  "Increment the events published counter."
  [registry]
  (when registry
    (prometheus/inc (registry :events/published-total))))

(defn record-event-overflow!
  "Increment the events overflow counter."
  [registry]
  (when registry
    (prometheus/inc (registry :events/overflow-total))))

(defn record-login!
  "Record a login attempt with result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :auth/login-total {:result (name result)}))))

(defn record-token-auth!
  "Record an API token auth attempt with result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :auth/token-auth-total {:result (name result)}))))

;; ---------------------------------------------------------------------------
;; Phase 3: Dispatch & queue metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-dispatch!
  "Record a build dispatch attempt with result (:success, :failure, :no-agent, :retry)."
  [registry result]
  (when registry
    (prometheus/inc (registry :dispatch/total {:result (name result)}))))

(defn record-queue-depth!
  "Set the current queue depth gauge."
  [registry depth]
  (when registry
    (prometheus/set (registry :queue/depth) (double depth))))

(defn record-queue-oldest-pending!
  "Set the age of the oldest pending queue item in seconds."
  [registry age-seconds]
  (when registry
    (prometheus/set (registry :queue/oldest-pending-seconds) (double age-seconds))))

(defn record-orphan-recovery!
  "Increment the orphan recovery counter."
  [registry count]
  (when registry
    (dotimes [_ count]
      (prometheus/inc (registry :dispatch/orphans-recovered-total)))))

(defn record-circuit-breaker-open!
  "Set the count of agents with open circuit breakers."
  [registry count]
  (when registry
    (prometheus/set (registry :agents/circuit-breaker-open) (double count))))

(defn record-artifact-transfer!
  "Record an artifact transfer result (:success or :failure)."
  [registry result]
  (when registry
    (prometheus/inc (registry :artifacts/transferred-total {:result (name result)}))))

(defn record-agent-utilization!
  "Set the agent utilization ratio (active-builds / total-capacity)."
  [registry ratio]
  (when registry
    (prometheus/set (registry :agents/utilization-ratio) (double ratio))))

;; ---------------------------------------------------------------------------
;; Metrics endpoint handler
;; ---------------------------------------------------------------------------

(defn metrics-handler
  "Ring handler that returns Prometheus metrics in text exposition format."
  [registry]
  (fn [_req]
    {:status 200
     :headers {"Content-Type" "text/plain; version=0.0.4; charset=utf-8"}
     :body (export/text-format registry)}))
