(ns chengis.web.account-lockout-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [chengis.web.account-lockout :as lockout]
            [clojure.java.io :as io])
  (:import [java.time Instant Duration]))

(def test-db-path "/tmp/chengis-lockout-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file)
      (.delete db-file))))

(use-fixtures :each setup-db)

(def lockout-config
  {:enabled true :max-attempts 3 :lockout-minutes 15})

(def lockout-disabled
  {:enabled false :max-attempts 3 :lockout-minutes 15})

;; ---------------------------------------------------------------------------
;; check-lockout tests
;; ---------------------------------------------------------------------------

(deftest check-lockout-test
  (testing "returns nil when lockout is disabled"
    (let [user {:id "u1" :username "alice" :locked-until (str (.plus (Instant/now) (Duration/ofMinutes 10)))}]
      (is (nil? (lockout/check-lockout user lockout-disabled)))))

  (testing "returns nil when user is not locked"
    (let [user {:id "u1" :username "alice" :locked-until nil :failed-attempts 0}]
      (is (nil? (lockout/check-lockout user lockout-config)))))

  (testing "returns nil when lock has expired"
    (let [user {:id "u1" :username "alice"
                :locked-until (str (.minus (Instant/now) (Duration/ofMinutes 5)))}]
      (is (nil? (lockout/check-lockout user lockout-config)))))

  (testing "returns error when account is locked (future lock time)"
    (let [lock-time (.plus (Instant/now) (Duration/ofMinutes 10))
          user {:id "u1" :username "alice" :locked-until (str lock-time)}
          result (lockout/check-lockout user lockout-config)]
      (is (true? (:locked result)))
      (is (string? (:error result)))
      (is (pos? (:minutes-remaining result))))))

;; ---------------------------------------------------------------------------
;; record-failed-attempt! and reset tests
;; ---------------------------------------------------------------------------

(deftest failed-attempt-tracking-test
  (testing "failed attempts increment and trigger lockout at threshold"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "bob" :password "password1" :role "viewer"})
          user (user-store/get-user-by-username ds "bob")]

      ;; First failure — should not lock
      (lockout/record-failed-attempt! ds user lockout-config nil)
      (let [updated (user-store/get-user-by-username ds "bob")]
        (is (= 1 (:failed-attempts updated)))
        (is (nil? (:locked-until updated))))

      ;; Second failure — should not lock
      (let [updated (user-store/get-user-by-username ds "bob")]
        (lockout/record-failed-attempt! ds updated lockout-config nil)
        (let [updated2 (user-store/get-user-by-username ds "bob")]
          (is (= 2 (:failed-attempts updated2)))
          (is (nil? (:locked-until updated2)))))

      ;; Third failure — should lock (max-attempts = 3)
      (let [updated (user-store/get-user-by-username ds "bob")]
        (lockout/record-failed-attempt! ds updated lockout-config nil)
        (let [updated3 (user-store/get-user-by-username ds "bob")]
          (is (= 3 (:failed-attempts updated3)))
          (is (some? (:locked-until updated3)))
          ;; Locked until should be in the future
          (is (.isBefore (Instant/now) (Instant/parse (:locked-until updated3)))))))))

(deftest reset-failed-attempts-test
  (testing "reset clears failed_attempts and locked_until on successful login"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "carol" :password "password1" :role "viewer"})
          user (user-store/get-user-by-username ds "carol")]

      ;; Simulate failures
      (lockout/record-failed-attempt! ds user lockout-config nil)
      (lockout/record-failed-attempt! ds (user-store/get-user-by-username ds "carol") lockout-config nil)

      ;; Verify failures recorded
      (let [updated (user-store/get-user-by-username ds "carol")]
        (is (= 2 (:failed-attempts updated))))

      ;; Reset
      (lockout/reset-failed-attempts! ds (:id user) lockout-config)
      (let [reset-user (user-store/get-user-by-username ds "carol")]
        (is (= 0 (:failed-attempts reset-user)))
        (is (nil? (:locked-until reset-user)))))))

(deftest reset-is-noop-when-disabled-test
  (testing "reset does nothing when lockout is disabled"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "dave" :password "password1" :role "viewer"})
          user (user-store/get-user-by-username ds "dave")]
      ;; No-op when disabled — should not throw
      (lockout/reset-failed-attempts! ds (:id user) lockout-disabled)
      (is true "no exception thrown"))))

;; ---------------------------------------------------------------------------
;; unlock-account! tests
;; ---------------------------------------------------------------------------

(deftest unlock-account-test
  (testing "admin unlock clears lockout state"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "eve" :password "password1" :role "viewer"})
          user (user-store/get-user-by-username ds "eve")]

      ;; Lock the account (3 failures)
      (lockout/record-failed-attempt! ds user lockout-config nil)
      (lockout/record-failed-attempt! ds (user-store/get-user-by-username ds "eve") lockout-config nil)
      (lockout/record-failed-attempt! ds (user-store/get-user-by-username ds "eve") lockout-config nil)

      ;; Verify locked
      (let [locked (user-store/get-user-by-username ds "eve")]
        (is (some? (:locked-until locked)))
        (is (= 3 (:failed-attempts locked))))

      ;; Admin unlock
      (lockout/unlock-account! ds (:id user))
      (let [unlocked (user-store/get-user-by-username ds "eve")]
        (is (= 0 (:failed-attempts unlocked)))
        (is (nil? (:locked-until unlocked)))))))

;; ---------------------------------------------------------------------------
;; login-attempt logging tests
;; ---------------------------------------------------------------------------

(deftest log-login-attempt-test
  (testing "login attempt logging doesn't throw"
    (let [ds (conn/create-datasource test-db-path)]
      ;; Should not throw even on success or failure
      (lockout/log-login-attempt! ds "alice" "192.168.1.1" true)
      (lockout/log-login-attempt! ds "alice" "192.168.1.1" false)
      (is true "no exception thrown"))))

;; ---------------------------------------------------------------------------
;; Integration with login! function
;; ---------------------------------------------------------------------------

(deftest login-with-lockout-integration-test
  (testing "login! respects lockout config — locks after max-attempts"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "frank" :password "correctpwd" :role "viewer"})]

      ;; Fail 3 times
      (dotimes [_ 3]
        (let [result (auth/login! ds "frank" "wrongpwd" nil lockout-config)]
          (is (false? (:success result)))))

      ;; 4th attempt should be rejected due to lockout (even with correct password)
      (let [result (auth/login! ds "frank" "correctpwd" nil lockout-config)]
        (is (false? (:success result)))
        (is (re-find #"locked" (:error result))))))

  (testing "login! resets counter on successful login"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "grace" :password "goodpwd123" :role "viewer"})]

      ;; Fail once
      (auth/login! ds "grace" "badpwd" nil lockout-config)
      (let [user (user-store/get-user-by-username ds "grace")]
        (is (= 1 (:failed-attempts user))))

      ;; Succeed — counter should reset
      (let [result (auth/login! ds "grace" "goodpwd123" nil lockout-config)]
        (is (true? (:success result))))
      (let [user (user-store/get-user-by-username ds "grace")]
        (is (= 0 (:failed-attempts user))))))

  (testing "login! works normally when lockout is disabled"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "hank" :password "hankpass1" :role "viewer"})]

      ;; Fail many times — should never lock
      (dotimes [_ 10]
        (auth/login! ds "hank" "wrongpwd" nil lockout-disabled))

      ;; Should still be able to log in
      (let [result (auth/login! ds "hank" "hankpass1" nil lockout-disabled)]
        (is (true? (:success result))))))

  (testing "login! works with nil lockout-config (backward compat)"
    (let [ds (conn/create-datasource test-db-path)
          _ (user-store/create-user! ds {:username "iris" :password "irispass1" :role "viewer"})]

      ;; Old 4-arity still works
      (let [result (auth/login! ds "iris" "irispass1" nil)]
        (is (true? (:success result))))

      ;; Old 3-arity still works
      (let [result (auth/login! ds "iris" "irispass1")]
        (is (true? (:success result)))))))
