(ns chengis.engine.webhook-replay-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.webhook-log :as webhook-log]
            [chengis.engine.webhook-replay :as replay]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; DB fixture
;; ---------------------------------------------------------------------------

(def test-db-path "/tmp/chengis-webhook-replay-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Replay tests
;; ---------------------------------------------------------------------------

(deftest replay-event-not-found
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}]
    (testing "replaying non-existent event returns error"
      (let [result (replay/replay-webhook! system "nonexistent" identity)]
        (is (= :error (:status result)))
        (is (= "Webhook event not found" (:details result)))))))

(deftest replay-event-no-payload
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}]
    (testing "replaying event without stored payload returns error"
      (let [event-id (webhook-log/log-webhook-event! ds
                       {:provider :github :event-type "push"
                        :status :processed :repo-url "https://github.com/o/r"})]
        (let [result (replay/replay-webhook! system event-id identity)]
          (is (= :error (:status result)))
          (is (= "No stored payload for replay" (:details result))))))))

(deftest replay-event-success
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}
        handler-called (atom false)]
    (testing "replaying event with payload calls handler"
      (let [payload "{\"ref\":\"refs/heads/main\"}"
            event-id (webhook-log/log-webhook-event! ds
                       {:provider :github :event-type "push"
                        :status :processed :repo-url "https://github.com/o/r"
                        :payload-body payload})
            mock-handler (fn [req]
                           (reset! handler-called true)
                           {:status 200 :body "{\"triggered\":1}"})]
        (let [result (replay/replay-webhook! system event-id mock-handler)]
          (is (= :replayed (:status result)))
          (is (true? @handler-called))
          ;; Check replay count updated
          (let [event (webhook-log/get-webhook-event ds event-id)]
            (is (= 1 (:replay-count event)))))))))

(deftest replay-feature-flag-disabled
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay false}}}]
    (testing "replay throws when feature flag disabled"
      (is (thrown? clojure.lang.ExceptionInfo
            (replay/replay-webhook! system "any-id" identity))))))

(deftest list-replayable-events
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}]
    (testing "lists only events with stored payloads"
      ;; Event with payload
      (webhook-log/log-webhook-event! ds
        {:provider :github :event-type "push" :status :processed
         :payload-body "{\"test\":true}"})
      ;; Event without payload
      (webhook-log/log-webhook-event! ds
        {:provider :github :event-type "push" :status :processed})

      (let [events (replay/list-replayable system)]
        (is (= 1 (count events)))))))

(deftest replay-increments-count
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}]
    (testing "multiple replays increment the count"
      (let [event-id (webhook-log/log-webhook-event! ds
                       {:provider :github :event-type "push"
                        :status :processed
                        :payload-body "{\"ref\":\"refs/heads/main\"}"})
            mock-handler (fn [_] {:status 200 :body "ok"})]
        (replay/replay-webhook! system event-id mock-handler)
        (replay/replay-webhook! system event-id mock-handler)
        (let [event (webhook-log/get-webhook-event ds event-id)]
          (is (= 2 (:replay-count event))))))))

(deftest replay-gitlab-event
  (let [ds (conn/create-datasource test-db-path)
        system {:db ds :config {:feature-flags {:webhook-replay true}}}
        received-headers (atom nil)]
    (testing "replay correctly sets GitLab headers"
      (let [event-id (webhook-log/log-webhook-event! ds
                       {:provider :gitlab :event-type "Push Hook"
                        :status :processed
                        :payload-body "{\"ref\":\"refs/heads/main\"}"})
            mock-handler (fn [req]
                           (reset! received-headers (:headers req))
                           {:status 200 :body "ok"})]
        (replay/replay-webhook! system event-id mock-handler)
        (is (= "Push Hook" (get @received-headers "x-gitlab-event")))))))
