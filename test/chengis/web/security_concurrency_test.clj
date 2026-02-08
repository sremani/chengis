(ns chengis.web.security-concurrency-test
  "Concurrency tests for account lockout and rate limiting.
   Validates atomic counter behavior under concurrent login bursts."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.account-lockout :as lockout]
            [chengis.web.rate-limit :as rate-limit]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-security-concurrency.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (rate-limit/reset-all!)
  (f)
  (rate-limit/reset-all!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Test 1: Concurrent failed logins trigger lockout at threshold
;; ---------------------------------------------------------------------------

(deftest concurrent-failed-logins-trigger-lockout-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "target" :password "password1" :role "developer"})
        lockout-config {:enabled true :max-attempts 5 :lockout-minutes 30}]

    (testing "5 concurrent failed logins trigger lockout"
      ;; Launch 5 concurrent failed-attempt recordings
      (let [futures (mapv (fn [_]
                            (let [fresh-user (user-store/get-user-by-username ds "target")]
                              (future (lockout/record-failed-attempt! ds fresh-user lockout-config nil))))
                          (range 5))
            _ (doall (map deref futures))
            final-user (user-store/get-user-by-username ds "target")]
        ;; With atomic increment, all 5 should be counted
        (is (= 5 (:failed-attempts final-user))
            "All 5 concurrent failed attempts should be counted atomically")
        (is (some? (:locked-until final-user))
            "Account should be locked after 5 failures")))))

;; ---------------------------------------------------------------------------
;; Test 2: Successful login resets counter after contention
;; ---------------------------------------------------------------------------

(deftest successful-login-resets-after-contention-test
  (let [ds (conn/create-datasource test-db-path)
        user (user-store/create-user! ds {:username "resetter" :password "password1" :role "developer"})
        lockout-config {:enabled true :max-attempts 5 :lockout-minutes 30}]

    ;; Record some failures
    (dotimes [_ 3]
      (let [u (user-store/get-user-by-username ds "resetter")]
        (lockout/record-failed-attempt! ds u lockout-config nil)))

    (testing "failed attempts accumulate"
      (let [u (user-store/get-user-by-username ds "resetter")]
        (is (= 3 (:failed-attempts u)))))

    ;; Reset on successful login
    (lockout/reset-failed-attempts! ds (:id user) lockout-config)

    (testing "counter resets after successful login"
      (let [u (user-store/get-user-by-username ds "resetter")]
        (is (= 0 (:failed-attempts u)))
        (is (nil? (:locked-until u)))))))

;; ---------------------------------------------------------------------------
;; Test 3: Rate limit buckets are isolated per IP
;; ---------------------------------------------------------------------------

(deftest rate-limit-bucket-isolated-per-ip-test
  (let [system {:config {:rate-limit {:enabled true
                                       :requests-per-minute 3
                                       :auth-requests-per-minute 3
                                       :webhook-requests-per-minute 3}}}
        handler (fn [_] {:status 200 :body "ok"})
        wrapped (rate-limit/wrap-rate-limit handler system)]

    (testing "different IPs have independent rate limits"
      ;; IP-1: exhaust limit
      (dotimes [_ 3]
        (wrapped {:uri "/api/test" :request-method :get
                  :remote-addr "10.0.0.1" :headers {}}))
      ;; IP-1 should be limited
      (let [resp (wrapped {:uri "/api/test" :request-method :get
                           :remote-addr "10.0.0.1" :headers {}})]
        (is (= 429 (:status resp))))

      ;; IP-2 should still work
      (let [resp (wrapped {:uri "/api/test" :request-method :get
                           :remote-addr "10.0.0.2" :headers {}})]
        (is (= 200 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Test 4: Rate limit shared per IP across threads
;; ---------------------------------------------------------------------------

(deftest rate-limit-shared-per-ip-across-threads-test
  (let [system {:config {:rate-limit {:enabled true
                                       :requests-per-minute 5
                                       :auth-requests-per-minute 5
                                       :webhook-requests-per-minute 5}}}
        handler (fn [_] {:status 200 :body "ok"})
        wrapped (rate-limit/wrap-rate-limit handler system)]

    (testing "concurrent requests from same IP share a budget"
      ;; Send 10 requests from same IP concurrently
      (let [futures (mapv (fn [_]
                            (future
                              (let [resp (wrapped {:uri "/api/test" :request-method :get
                                                   :remote-addr "10.0.0.3" :headers {}})]
                                (:status resp))))
                          (range 10))
            statuses (mapv deref futures)
            ok-count (count (filter #(= 200 %) statuses))
            limited-count (count (filter #(= 429 %) statuses))]
        ;; At most 5 should succeed (rpm=5), rest should be 429
        (is (<= ok-count 5) "At most 5 requests should succeed")
        (is (pos? limited-count) "Some requests should be rate-limited")))))
