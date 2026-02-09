(ns chengis.web.rate-limit-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.web.rate-limit :as rl]))

(use-fixtures :each
  (fn [f]
    (rl/reset-all!)
    (f)
    (rl/reset-all!)))

;; ---------------------------------------------------------------------------
;; Client IP extraction
;; ---------------------------------------------------------------------------

(deftest get-client-ip-test
  (testing "extracts IP from x-forwarded-for when trust-proxy is true"
    (is (= "1.2.3.4"
           (rl/get-client-ip {:headers {"x-forwarded-for" "1.2.3.4"}} true))))

  (testing "uses first IP from x-forwarded-for chain when trust-proxy is true"
    (is (= "1.2.3.4"
           (rl/get-client-ip {:headers {"x-forwarded-for" "1.2.3.4, 5.6.7.8"}} true))))

  (testing "ignores x-forwarded-for when trust-proxy is false (default)"
    (is (= "10.0.0.1"
           (rl/get-client-ip {:headers {"x-forwarded-for" "1.2.3.4"}
                              :remote-addr "10.0.0.1"})))
    (is (= "10.0.0.1"
           (rl/get-client-ip {:headers {"x-forwarded-for" "1.2.3.4"}
                              :remote-addr "10.0.0.1"} false))))

  (testing "falls back to remote-addr"
    (is (= "10.0.0.1"
           (rl/get-client-ip {:remote-addr "10.0.0.1"}))))

  (testing "returns unknown when no IP available"
    (is (= "unknown"
           (rl/get-client-ip {})))))

;; ---------------------------------------------------------------------------
;; Rate limiting middleware
;; ---------------------------------------------------------------------------

(deftest wrap-rate-limit-disabled-test
  (testing "passes all requests when disabled"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled false}}}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; Should always pass
      (dotimes [_ 100]
        (is (= 200 (:status (wrapped {:remote-addr "1.1.1.1"
                                       :uri "/api/agents"
                                       :request-method :get}))))))))

(deftest wrap-rate-limit-general-test
  (testing "allows requests within limit then rejects"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 5
                                        :auth-requests-per-minute 3
                                        :webhook-requests-per-minute 10}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; First 5 requests should succeed (burst capacity)
      (dotimes [_ 5]
        (is (= 200 (:status (wrapped {:remote-addr "2.2.2.2"
                                       :uri "/api/agents"
                                       :request-method :get})))))
      ;; 6th request should be rate limited
      (let [resp (wrapped {:remote-addr "2.2.2.2"
                            :uri "/api/agents"
                            :request-method :get})]
        (is (= 429 (:status resp)))
        (is (contains? (:headers resp) "Retry-After"))))))

(deftest wrap-rate-limit-auth-endpoint-test
  (testing "uses auth limit for login endpoint"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 100
                                        :auth-requests-per-minute 3
                                        :webhook-requests-per-minute 100}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; First 3 auth requests succeed
      (dotimes [_ 3]
        (is (= 200 (:status (wrapped {:remote-addr "3.3.3.3"
                                       :uri "/login"
                                       :request-method :post})))))
      ;; 4th should be rate limited
      (is (= 429 (:status (wrapped {:remote-addr "3.3.3.3"
                                     :uri "/login"
                                     :request-method :post})))))))

(deftest wrap-rate-limit-webhook-endpoint-test
  (testing "uses webhook limit for /api/webhook"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 3
                                        :auth-requests-per-minute 3
                                        :webhook-requests-per-minute 5}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; Webhook has its own higher limit
      (dotimes [_ 5]
        (is (= 200 (:status (wrapped {:remote-addr "4.4.4.4"
                                       :uri "/api/webhook"
                                       :request-method :post})))))
      ;; 6th should be rate limited
      (is (= 429 (:status (wrapped {:remote-addr "4.4.4.4"
                                     :uri "/api/webhook"
                                     :request-method :post})))))))

(deftest wrap-rate-limit-per-ip-isolation-test
  (testing "different IPs have separate buckets"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 3
                                        :auth-requests-per-minute 3
                                        :webhook-requests-per-minute 10}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; Exhaust IP-A's budget
      (dotimes [_ 3]
        (wrapped {:remote-addr "5.5.5.5" :uri "/api/foo" :request-method :get}))
      (is (= 429 (:status (wrapped {:remote-addr "5.5.5.5"
                                     :uri "/api/foo"
                                     :request-method :get}))))
      ;; IP-B should still have budget
      (is (= 200 (:status (wrapped {:remote-addr "6.6.6.6"
                                     :uri "/api/foo"
                                     :request-method :get})))))))

(deftest wrap-rate-limit-429-response-format-test
  (testing "API requests get JSON 429 response"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 1
                                        :auth-requests-per-minute 1
                                        :webhook-requests-per-minute 1}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; Exhaust
      (wrapped {:remote-addr "7.7.7.7" :uri "/api/foo" :request-method :get})
      (let [resp (wrapped {:remote-addr "7.7.7.7"
                            :uri "/api/foo"
                            :request-method :get})]
        (is (= 429 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"])))
        (is (clojure.string/includes? (:body resp) "Rate limit exceeded")))))

  (testing "HTML requests get HTML 429 response"
    (let [handler (fn [_] {:status 200})
          system {:config {:rate-limit {:enabled true
                                        :requests-per-minute 1
                                        :auth-requests-per-minute 1
                                        :webhook-requests-per-minute 1}}
                  :metrics nil}
          wrapped (rl/wrap-rate-limit handler system)]
      ;; Exhaust
      (wrapped {:remote-addr "8.8.8.8" :uri "/dashboard" :request-method :get})
      (let [resp (wrapped {:remote-addr "8.8.8.8"
                            :uri "/dashboard"
                            :request-method :get})]
        (is (= 429 (:status resp)))
        (is (clojure.string/includes? (get-in resp [:headers "Content-Type"]) "text/html"))))))
