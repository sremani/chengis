(ns chengis.web.metrics-middleware-test
  (:require [clojure.test :refer :all]
            [chengis.web.metrics-middleware :as mw]
            [chengis.metrics :as metrics]
            [iapetos.export :as export]))

;; ---------------------------------------------------------------------------
;; Path normalization
;; ---------------------------------------------------------------------------

(deftest normalize-path-test
  (testing "Static paths are unchanged"
    (is (= "/" (mw/normalize-path "/")))
    (is (= "/health" (mw/normalize-path "/health")))
    (is (= "/login" (mw/normalize-path "/login")))
    (is (= "/metrics" (mw/normalize-path "/metrics")))
    (is (= "/admin/audit" (mw/normalize-path "/admin/audit"))))

  (testing "UUID segments are replaced with {id}"
    (is (= "/builds/{id}" (mw/normalize-path "/builds/550e8400-e29b-41d4-a716-446655440000")))
    (is (= "/builds/{id}/log" (mw/normalize-path "/builds/550e8400-e29b-41d4-a716-446655440000/log")))
    (is (= "/api/builds/{id}/events" (mw/normalize-path "/api/builds/550e8400-e29b-41d4-a716-446655440000/events"))))

  (testing "Resource prefix segments trigger ID replacement"
    (is (= "/jobs/{id}" (mw/normalize-path "/jobs/my-build-job")))
    (is (= "/jobs/{id}/trigger" (mw/normalize-path "/jobs/my-build-job/trigger")))
    (is (= "/builds/{id}/cancel" (mw/normalize-path "/builds/build-123/cancel")))
    (is (= "/agents/{id}" (mw/normalize-path "/agents/agent-xyz")))
    (is (= "/admin/users/{id}" (mw/normalize-path "/admin/users/user-abc")))
    (is (= "/admin/users/{id}/password" (mw/normalize-path "/admin/users/user-abc/password"))))

  (testing "API prefix paths"
    (is (= "/api/builds/{id}/agent-events" (mw/normalize-path "/api/builds/build-123/agent-events")))
    (is (= "/api/builds/{id}/result" (mw/normalize-path "/api/builds/build-123/result")))
    (is (= "/api/agents/{id}/heartbeat" (mw/normalize-path "/api/agents/agent-1/heartbeat"))))

  (testing "Nil and root path handling"
    (is (nil? (mw/normalize-path nil)))
    (is (= "/" (mw/normalize-path "/")))))

;; ---------------------------------------------------------------------------
;; Middleware behavior
;; ---------------------------------------------------------------------------

(deftest wrap-http-metrics-nil-registry-test
  (testing "Nil registry returns handler unchanged"
    (let [handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-http-metrics handler nil)]
      ;; Should be the exact same handler (identity pass-through)
      (is (= handler wrapped)))))

(deftest wrap-http-metrics-records-test
  (testing "Middleware records request metrics"
    (let [registry (metrics/init-registry)
          handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-http-metrics handler registry)
          resp (wrapped {:uri "/health" :request-method :get})]
      (is (= 200 (:status resp)))
      (let [dump (export/text-format registry)]
        (is (re-find #"http_requests_total" dump) "Counter should be updated")
        (is (re-find #"http_request_duration_seconds" dump) "Histogram should be updated")))))

(deftest wrap-http-metrics-status-codes-test
  (testing "Different status codes are recorded correctly"
    (let [registry (metrics/init-registry)
          handler-200 (fn [_] {:status 200 :body "ok"})
          handler-404 (fn [_] {:status 404 :body "not found"})
          handler-500 (fn [_] {:status 500 :body "error"})
          wrapped-200 (mw/wrap-http-metrics handler-200 registry)
          wrapped-404 (mw/wrap-http-metrics handler-404 registry)
          wrapped-500 (mw/wrap-http-metrics handler-500 registry)]
      (wrapped-200 {:uri "/health" :request-method :get})
      (wrapped-404 {:uri "/missing" :request-method :get})
      (wrapped-500 {:uri "/error" :request-method :get})
      (let [dump (export/text-format registry)]
        (is (re-find #"status=\"200\"" dump))
        (is (re-find #"status=\"404\"" dump))
        (is (re-find #"status=\"500\"" dump))))))

(deftest wrap-http-metrics-normalized-paths-test
  (testing "Paths are normalized before recording"
    (let [registry (metrics/init-registry)
          handler (fn [_] {:status 200 :body "ok"})
          wrapped (mw/wrap-http-metrics handler registry)]
      ;; Request to a parameterized path
      (wrapped {:uri "/builds/550e8400-e29b-41d4-a716-446655440000" :request-method :get})
      (wrapped {:uri "/builds/aaa-bbb-ccc-ddd-eee" :request-method :get})
      (let [dump (export/text-format registry)]
        ;; Both should map to /builds/{id}, not the actual UUIDs
        (is (re-find #"path=\"/builds/\{id\}\"" dump) "UUID paths should be normalized")
        (is (not (re-find #"550e8400" dump)) "Actual UUID should not appear in metrics")))))
