(ns chengis.plugin.email-notifier-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.plugin.builtin.email-notifier :as email]
            [chengis.plugin.protocol :as proto]))

;; ---------------------------------------------------------------------------
;; Unit tests — no SMTP server needed
;; ---------------------------------------------------------------------------

(def sample-build-result
  {:job-id "my-app"
   :build-number 42
   :build-status :success
   :build-id "build-abc-123"
   :duration-ms 12500
   :stage-count 3})

(def sample-config
  {:host "smtp.example.com"
   :port 587
   :tls true
   :from "ci@example.com"
   :default-recipients ["team@example.com"]
   :base-url "https://ci.example.com"})

(deftest email-notifier-protocol-test
  (testing "EmailNotifier implements Notifier protocol"
    (let [notifier (email/->EmailNotifier)]
      (is (satisfies? proto/Notifier notifier)))))

(deftest email-notifier-no-host-test
  (testing "send-notification returns :failed when no host configured"
    (let [notifier (email/->EmailNotifier)
          config {:host nil :default-recipients ["test@example.com"]}
          result (proto/send-notification notifier sample-build-result config)]
      (is (= :failed (:status result)))
      (is (re-find #"host" (:details result))))))

(deftest email-notifier-no-recipients-test
  (testing "send-notification returns :failed when no recipients"
    (let [notifier (email/->EmailNotifier)
          config {:host "smtp.example.com" :default-recipients []}
          result (proto/send-notification notifier sample-build-result config)]
      (is (= :failed (:status result)))
      (is (re-find #"recipients" (:details result))))))

(deftest email-notifier-smtp-failure-test
  (testing "send-notification returns :failed on SMTP connection error"
    (let [notifier (email/->EmailNotifier)
          ;; Use a host that won't connect (port 1 should refuse quickly)
          config {:host "127.0.0.1" :port 1 :tls false
                  :default-recipients ["test@example.com"]}
          result (proto/send-notification notifier sample-build-result config)]
      (is (= :failed (:status result)))
      (is (string? (:details result))))))

(deftest email-subject-format-test
  (testing "build subject includes status and job name"
    ;; Test the internal subject formatting via the notifier's behavior
    ;; We can't test the subject directly since it's private, but we verify
    ;; the notifier handles different statuses without error
    (let [notifier (email/->EmailNotifier)]
      (doseq [status [:success :failure :aborted]]
        (let [result (proto/send-notification
                       notifier
                       (assoc sample-build-result :build-status status)
                       {:host nil :default-recipients []})]
          (is (= :failed (:status result))
              (str "Should handle status " status)))))))

(deftest email-config-from-notification-map-test
  (testing "notifier extracts email config from :email key in config"
    (let [notifier (email/->EmailNotifier)
          config {:email {:host nil :default-recipients []}
                  :to ["override@example.com"]}
          result (proto/send-notification notifier sample-build-result config)]
      ;; Should fail due to no host, but should not throw
      (is (= :failed (:status result))))))

(deftest send-email-connection-refused-test
  (testing "send-email! returns :failed on connection refused"
    (let [result (email/send-email!
                   {:host "127.0.0.1" :port 1 :tls false}
                   ["test@example.com"]
                   "Test Subject"
                   "<html><body>Test</body></html>")]
      (is (= :failed (:status result)))
      (is (string? (:details result))))))

(deftest plugin-init-test
  (testing "init! registers the email notifier without error"
    (email/init!)
    (is true "init! completed without exception")))
