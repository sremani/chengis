(ns chengis.metrics-test
  (:require [clojure.test :refer :all]
            [chengis.metrics :as metrics]
            [iapetos.export :as export]))

;; ---------------------------------------------------------------------------
;; Registry initialization
;; ---------------------------------------------------------------------------

(deftest init-registry-test
  (testing "Registry can be initialized without errors"
    (let [registry (metrics/init-registry)]
      (is (some? registry) "Registry should not be nil")
      ;; Verify we can dump metrics (Prometheus text format)
      (let [dump (export/text-format registry)]
        (is (string? dump))
        (is (pos? (count dump)))
        ;; Should contain JVM metrics
        (is (re-find #"jvm_" dump) "Should contain JVM metrics")
        ;; Should contain our custom metrics
        (is (re-find #"http_request_duration_seconds" dump) "Should contain HTTP histogram")
        (is (re-find #"http_requests_total" dump) "Should contain HTTP counter")
        (is (re-find #"builds_active" dump) "Should contain builds gauge")
        (is (re-find #"builds_total" dump) "Should contain builds counter")
        (is (re-find #"builds_duration_seconds" dump) "Should contain builds histogram")
        (is (re-find #"stages_duration_seconds" dump) "Should contain stages histogram")
        (is (re-find #"steps_duration_seconds" dump) "Should contain steps histogram")
        (is (re-find #"events_published_total" dump) "Should contain events counter")
        (is (re-find #"events_overflow_total" dump) "Should contain overflow counter")
        (is (re-find #"auth_login_total" dump) "Should contain auth login counter")
        (is (re-find #"auth_token_auth_total" dump) "Should contain auth token counter")))))

;; ---------------------------------------------------------------------------
;; No-op on nil registry
;; ---------------------------------------------------------------------------

(deftest nil-registry-noop-test
  (testing "All record functions no-op gracefully with nil registry"
    ;; These should all complete without error
    (is (nil? (metrics/record-http-request! nil "GET" "/" 200 0.1)))
    (is (nil? (metrics/record-build-start! nil)))
    (is (nil? (metrics/record-build-end! nil :success 5.0)))
    (is (nil? (metrics/record-stage-duration! nil "build" :success 3.0)))
    (is (nil? (metrics/record-step-duration! nil "compile" :success 1.0)))
    (is (nil? (metrics/record-event-published! nil)))
    (is (nil? (metrics/record-event-overflow! nil)))
    (is (nil? (metrics/record-login! nil :success)))
    (is (nil? (metrics/record-token-auth! nil :failure)))))

;; ---------------------------------------------------------------------------
;; Recording metrics
;; ---------------------------------------------------------------------------

(deftest record-http-metrics-test
  (testing "HTTP request recording updates metrics"
    (let [registry (metrics/init-registry)]
      (metrics/record-http-request! registry :get "/jobs" 200 0.05)
      (metrics/record-http-request! registry :get "/jobs" 200 0.10)
      (metrics/record-http-request! registry :post "/jobs/test/trigger" 302 0.15)
      (let [dump (export/text-format registry)]
        ;; Counter should show 3 total requests
        (is (re-find #"http_requests_total" dump))))))

(deftest record-build-metrics-test
  (testing "Build lifecycle metrics recording"
    (let [registry (metrics/init-registry)]
      ;; Start a build
      (metrics/record-build-start! registry)
      (let [dump (export/text-format registry)]
        (is (re-find #"builds_active 1\.0" dump) "Active builds should be 1"))

      ;; Start another
      (metrics/record-build-start! registry)
      (let [dump (export/text-format registry)]
        (is (re-find #"builds_active 2\.0" dump) "Active builds should be 2"))

      ;; End one successfully
      (metrics/record-build-end! registry :success 10.5)
      (let [dump (export/text-format registry)]
        (is (re-find #"builds_active 1\.0" dump) "Active builds should be 1 after completion")))))

(deftest record-auth-metrics-test
  (testing "Auth login and token metrics recording"
    (let [registry (metrics/init-registry)]
      (metrics/record-login! registry :success)
      (metrics/record-login! registry :failure)
      (metrics/record-login! registry :failure)
      (metrics/record-token-auth! registry :success)
      (let [dump (export/text-format registry)]
        (is (re-find #"auth_login_total" dump))
        (is (re-find #"auth_token_auth_total" dump))))))

(deftest record-event-metrics-test
  (testing "Event bus metrics recording"
    (let [registry (metrics/init-registry)]
      (metrics/record-event-published! registry)
      (metrics/record-event-published! registry)
      (metrics/record-event-overflow! registry)
      (let [dump (export/text-format registry)]
        (is (re-find #"events_published_total" dump))
        (is (re-find #"events_overflow_total" dump))))))

(deftest record-stage-step-metrics-test
  (testing "Stage and step duration recording"
    (let [registry (metrics/init-registry)]
      (metrics/record-stage-duration! registry "build" :success 5.0)
      (metrics/record-stage-duration! registry "test" :failure 3.0)
      (metrics/record-step-duration! registry "compile" :success 2.0)
      (metrics/record-step-duration! registry "lint" :failure 0.5)
      (let [dump (export/text-format registry)]
        (is (re-find #"stages_duration_seconds" dump))
        (is (re-find #"steps_duration_seconds" dump))))))

;; ---------------------------------------------------------------------------
;; Type-safe label coercion (keyword + string inputs)
;; ---------------------------------------------------------------------------

(deftest webhook-metrics-accept-strings-and-keywords-test
  (testing "webhook metrics work with keyword labels"
    (let [registry (metrics/init-registry)]
      (metrics/record-webhook-received! registry :github :processed)
      (let [dump (export/text-format registry)]
        (is (re-find #"webhooks_received_total" dump)))))

  (testing "webhook metrics work with string labels"
    (let [registry (metrics/init-registry)]
      (metrics/record-webhook-received! registry "github" "processed")
      (let [dump (export/text-format registry)]
        (is (re-find #"webhooks_received_total" dump))))))

(deftest retention-metrics-accept-strings-and-keywords-test
  (testing "retention metrics work with keyword labels"
    (let [registry (metrics/init-registry)]
      (metrics/record-retention-cleaned! registry :audit-logs 1)
      (let [dump (export/text-format registry)]
        (is (re-find #"retention_cleaned_total" dump)))))

  (testing "retention metrics work with string labels"
    (let [registry (metrics/init-registry)]
      (metrics/record-retention-cleaned! registry "audit-logs" 1)
      (let [dump (export/text-format registry)]
        (is (re-find #"retention_cleaned_total" dump))))))

(deftest rate-limit-and-artifact-metrics-accept-strings-test
  (testing "rate-limit metrics work with string endpoint-type"
    (let [registry (metrics/init-registry)]
      (metrics/record-rate-limit-rejected! registry "api")
      (metrics/record-rate-limit-rejected! registry :api)
      (let [dump (export/text-format registry)]
        (is (re-find #"rate_limit_rejected_total" dump)))))

  (testing "artifact transfer metrics work with string result"
    (let [registry (metrics/init-registry)]
      (metrics/record-artifact-transfer! registry "success")
      (metrics/record-artifact-transfer! registry :failure)
      (let [dump (export/text-format registry)]
        (is (re-find #"artifacts_transferred_total" dump))))))

;; ---------------------------------------------------------------------------
;; Metrics handler
;; ---------------------------------------------------------------------------

(deftest metrics-handler-test
  (testing "Metrics handler returns Prometheus text format"
    (let [registry (metrics/init-registry)
          handler (metrics/metrics-handler registry)
          resp (handler {:uri "/metrics" :request-method :get})]
      (is (= 200 (:status resp)))
      (is (= "text/plain; version=0.0.4; charset=utf-8"
             (get-in resp [:headers "Content-Type"])))
      (is (string? (:body resp)))
      (is (re-find #"jvm_" (:body resp)) "Should contain JVM metrics"))))
