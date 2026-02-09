(ns chengis.engine.events-durability-test
  "Tests for event bus durability: publish! persists to DB, graceful
   degradation when DB fails, and event replay returns correct sequence."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.build-event-store :as event-store]
            [chengis.engine.events :as events]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-events-durability-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (let [ds (conn/create-datasource test-db-path)]
    ;; Set the DB on the event bus
    (events/set-db! ds)
    (f)
    ;; Clean up
    (events/set-db! nil))
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest publish-persists-to-db-test
  (testing "publish! persists events to DB when db-ref is set"
    (let [ds (conn/create-datasource test-db-path)
          ;; Ensure db-ref is set
          _ (events/set-db! ds)
          build-id "durability-test-build-1"
          ;; Subscribe to drain the channel so it doesn't fill up
          sub-ch (events/subscribe build-id)]
      (try
        (events/publish! {:build-id build-id
                          :event-type :build-started
                          :data {:message "started"}})
        (events/publish! {:build-id build-id
                          :event-type :step-output
                          :stage-name "Build"
                          :step-name "Compile"
                          :data {:line "compiling..."}})
        ;; Drain channel
        (async/<!! sub-ch)
        (async/<!! sub-ch)
        ;; Verify events persisted in DB
        (let [events (event-store/list-events ds build-id)]
          (is (= 2 (count events))
              "Both events should be persisted to DB")
          (is (= :build-started (:event-type (first events)))
              "First event type should be build-started")
          (is (= "Build" (:stage-name (second events)))
              "Second event should have stage-name"))
        (finally
          (events/unsubscribe build-id sub-ch))))))

(deftest publish-broadcasts-even-when-db-fails-test
  (testing "publish! still broadcasts to channel even if DB persistence fails"
    (let [build-id "durability-test-build-2"
          ;; Set an invalid datasource to force DB failure
          _ (events/set-db! {:invalid "not-a-real-ds"})
          sub-ch (events/subscribe build-id)]
      (try
        ;; This should log a warning but not throw
        (events/publish! {:build-id build-id
                          :event-type :build-started
                          :data {:message "should still broadcast"}})
        ;; Channel should still receive the event
        (let [event (async/alt!!
                      sub-ch ([v] v)
                      (async/timeout 2000) :timeout)]
          (is (not= :timeout event)
              "Event should be received on channel despite DB failure")
          (is (= build-id (:build-id event))
              "Event should have correct build-id"))
        (finally
          (events/unsubscribe build-id sub-ch)
          ;; Restore to nil so other tests aren't affected
          (events/set-db! nil))))))

(deftest event-replay-returns-correct-sequence-test
  (testing "events replayed from DB match the published sequence"
    (let [ds (conn/create-datasource test-db-path)
          _ (events/set-db! ds)
          build-id "durability-test-build-3"
          sub-ch (events/subscribe build-id)]
      (try
        ;; Publish a sequence of events (time-ordered IDs guarantee ordering)
        (events/publish! {:build-id build-id :event-type :build-started
                          :data {:n 1}})
        (events/publish! {:build-id build-id :event-type :stage-started
                          :stage-name "Build" :data {:n 2}})
        (events/publish! {:build-id build-id :event-type :step-completed
                          :stage-name "Build" :step-name "Compile" :data {:n 3}})
        (events/publish! {:build-id build-id :event-type :build-completed
                          :data {:n 4}})
        ;; Drain channel
        (dotimes [_ 4] (async/<!! sub-ch))
        ;; Replay from DB
        (let [replayed (event-store/list-events ds build-id)]
          (is (= 4 (count replayed))
              "All 4 events should be replayed")
          (is (= [:build-started :stage-started :step-completed :build-completed]
                 (mapv :event-type replayed))
              "Event types should match publish order")
          (is (= [1 2 3 4]
                 (mapv #(get-in % [:data :n]) replayed))
              "Event data should match publish order"))
        (finally
          (events/unsubscribe build-id sub-ch))))))
