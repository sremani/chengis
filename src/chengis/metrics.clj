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
                             :labels [:result]}))

      ;; Phase 4: Rate limiting metrics
      (prometheus/register
        (prometheus/counter :rate-limit/rejected-total
                            {:description "Requests rejected by rate limiter"
                             :labels [:endpoint-type]}))

      ;; Phase 4: Webhook metrics
      (prometheus/register
        (prometheus/counter :webhooks/received-total
                            {:description "Webhook events received"
                             :labels [:provider :status]}))
      (prometheus/register
        (prometheus/histogram :webhooks/processing-seconds
                              {:description "Webhook processing duration in seconds"
                               :buckets [0.01 0.05 0.1 0.25 0.5 1.0 2.5 5.0]}))

      ;; Phase 4: Token management metrics
      (prometheus/register
        (prometheus/counter :tokens/generated-total
                            {:description "API tokens generated"}))
      (prometheus/register
        (prometheus/counter :tokens/revoked-total
                            {:description "API tokens revoked"}))

      ;; Phase 4: Retention metrics
      (prometheus/register
        (prometheus/counter :retention/cleaned-total
                            {:description "Records cleaned by retention scheduler"
                             :labels [:resource-type]}))

      ;; Phase 4: Secret access metrics
      (prometheus/register
        (prometheus/counter :secrets/access-total
                            {:description "Secret access events"
                             :labels [:action]}))

      ;; Phase 5: Account lockout metrics
      (prometheus/register
        (prometheus/counter :auth/account-lockouts-total
                            {:description "Account lockout events"}))

      ;; Phase 5: Approval gate metrics
      (prometheus/register
        (prometheus/counter :approvals/requested-total
                            {:description "Approval gates created"}))
      (prometheus/register
        (prometheus/counter :approvals/resolved-total
                            {:description "Approval gates resolved"
                             :labels [:result]}))

      ;; Phase 5: SCM status check metrics
      (prometheus/register
        (prometheus/counter :scm/status-reports-total
                            {:description "SCM status reports sent"
                             :labels [:provider :result]}))

      ;; Phase 6: Artifact integrity metrics
      (prometheus/register
        (prometheus/counter :artifacts/checksum-verified-total
                            {:description "Artifact checksum verifications"
                             :labels [:result]}))

      ;; Phase 6: Compliance reporting metrics
      (prometheus/register
        (prometheus/counter :compliance/reports-generated-total
                            {:description "Compliance reports generated"
                             :labels [:report-type]}))
      (prometheus/register
        (prometheus/counter :compliance/hash-chain-verifications-total
                            {:description "Hash chain integrity verifications"
                             :labels [:result]}))

      ;; Phase 6: Policy engine metrics
      (prometheus/register
        (prometheus/counter :policies/evaluated-total
                            {:description "Policy evaluations"
                             :labels [:policy-type :result]}))
      (prometheus/register
        (prometheus/counter :policies/denied-total
                            {:description "Builds/stages blocked by policy"
                             :labels [:policy-type]}))
      (prometheus/register
        (prometheus/histogram :policies/evaluation-duration-seconds
                              {:description "Policy evaluation duration"
                               :buckets [0.001 0.005 0.01 0.025 0.05 0.1 0.25]}))

      ;; Phase 5 (Observability): Tracing metrics
      (prometheus/register
        (prometheus/counter :tracing/spans-created-total
                            {:description "Total trace spans created"}))
      (prometheus/register
        (prometheus/histogram :tracing/span-duration-seconds
                              {:description "Trace span duration in seconds"
                               :buckets [0.001 0.005 0.01 0.05 0.1 0.5 1.0 5.0 30.0 60.0 300.0]}))

      ;; Phase 5 (Observability): Analytics metrics
      (prometheus/register
        (prometheus/counter :analytics/aggregation-runs-total
                            {:description "Analytics aggregation runs completed"}))
      (prometheus/register
        (prometheus/histogram :analytics/aggregation-duration-seconds
                              {:description "Analytics aggregation duration in seconds"
                               :buckets [0.1 0.5 1.0 5.0 10.0 30.0 60.0]}))))

(defn- as-label
  "Coerce a keyword, symbol, or string to a Prometheus label string.
   Prevents ClassCastException when callers accidentally pass strings."
  [x]
  (if (string? x) x (name x)))

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
    (prometheus/inc (registry :artifacts/transferred-total {:result (as-label result)}))))

(defn record-agent-utilization!
  "Set the agent utilization ratio (active-builds / total-capacity)."
  [registry ratio]
  (when registry
    (prometheus/set (registry :agents/utilization-ratio) (double ratio))))

;; ---------------------------------------------------------------------------
;; Phase 4: Rate limiting, webhook, token, retention, secret metrics
;; ---------------------------------------------------------------------------

(defn record-rate-limit-rejected!
  "Record a rate-limited request rejection."
  [registry endpoint-type]
  (when registry
    (prometheus/inc (registry :rate-limit/rejected-total
                              {:endpoint-type (as-label endpoint-type)}))))

(defn record-webhook-received!
  "Record a received webhook event."
  [registry provider status]
  (when registry
    (prometheus/inc (registry :webhooks/received-total
                              {:provider (as-label provider) :status (as-label status)}))))

(defn record-webhook-processing!
  "Record webhook processing duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :webhooks/processing-seconds) duration-s)))

(defn record-token-generated!
  "Record an API token generation."
  [registry]
  (when registry
    (prometheus/inc (registry :tokens/generated-total))))

(defn record-token-revoked!
  "Record an API token revocation."
  [registry]
  (when registry
    (prometheus/inc (registry :tokens/revoked-total))))

(defn record-retention-cleaned!
  "Record retention cleanup count for a resource type."
  [registry resource-type count]
  (when registry
    (dotimes [_ count]
      (prometheus/inc (registry :retention/cleaned-total
                                {:resource-type (as-label resource-type)})))))

(defn record-secret-access!
  "Record a secret access event."
  [registry action]
  (when registry
    (prometheus/inc (registry :secrets/access-total {:action (name action)}))))

;; ---------------------------------------------------------------------------
;; Phase 5: Account lockout metrics
;; ---------------------------------------------------------------------------

(defn record-account-lockout!
  "Record an account lockout event."
  [registry]
  (when registry
    (prometheus/inc (registry :auth/account-lockouts-total))))

(defn record-approval-requested!
  "Record an approval gate creation."
  [registry]
  (when registry
    (prometheus/inc (registry :approvals/requested-total))))

(defn record-approval-resolved!
  "Record an approval gate resolution."
  [registry result]
  (when registry
    (prometheus/inc (registry :approvals/resolved-total {:result (or result "unknown")}))))

(defn record-scm-status-report!
  "Record an SCM status report."
  [registry provider result]
  (when registry
    (prometheus/inc (registry :scm/status-reports-total
                              {:provider (or provider "unknown")
                               :result (or result "unknown")}))))

;; ---------------------------------------------------------------------------
;; Phase 6: Artifact, compliance, and policy metrics
;; ---------------------------------------------------------------------------

(defn record-artifact-checksum!
  "Record an artifact checksum verification result (:match, :mismatch, :skipped)."
  [registry result]
  (when registry
    (prometheus/inc (registry :artifacts/checksum-verified-total
                              {:result (as-label result)}))))

(defn record-compliance-report!
  "Record a compliance report generation."
  [registry report-type]
  (when registry
    (prometheus/inc (registry :compliance/reports-generated-total
                              {:report-type (as-label report-type)}))))

(defn record-hash-chain-verification!
  "Record a hash chain integrity verification result (:valid or :invalid)."
  [registry result]
  (when registry
    (prometheus/inc (registry :compliance/hash-chain-verifications-total
                              {:result (as-label result)}))))

(defn record-policy-evaluation!
  "Record a policy evaluation with type and result."
  [registry policy-type result]
  (when registry
    (prometheus/inc (registry :policies/evaluated-total
                              {:policy-type (as-label policy-type)
                               :result (as-label result)}))))

(defn record-policy-denial!
  "Record a build/stage blocked by policy."
  [registry policy-type]
  (when registry
    (prometheus/inc (registry :policies/denied-total
                              {:policy-type (as-label policy-type)}))))

(defn record-policy-duration!
  "Record policy evaluation duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :policies/evaluation-duration-seconds) duration-s)))

;; ---------------------------------------------------------------------------
;; Phase 5: Tracing and analytics metrics — all no-op when registry is nil
;; ---------------------------------------------------------------------------

(defn record-tracing-span-created!
  "Record a trace span creation."
  [registry]
  (when registry
    (prometheus/inc (registry :tracing/spans-created-total))))

(defn record-tracing-span-duration!
  "Record a trace span duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :tracing/span-duration-seconds) duration-s)))

(defn record-analytics-aggregation-run!
  "Record an analytics aggregation run."
  [registry]
  (when registry
    (prometheus/inc (registry :analytics/aggregation-runs-total))))

(defn record-analytics-aggregation-duration!
  "Record an analytics aggregation duration in seconds."
  [registry duration-s]
  (when registry
    (prometheus/observe (registry :analytics/aggregation-duration-seconds) duration-s)))

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
