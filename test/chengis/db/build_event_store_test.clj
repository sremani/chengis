(ns chengis.db.build-event-store-test
  "Tests for durable build event persistence: persist, list, pagination,
   filtering, count, and cleanup."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.build-event-store :as event-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-event-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

(deftest persist-and-retrieve-events-test
  (testing "persist events and retrieve them in order"
    (let [ds (conn/create-datasource test-db-path)
          build-id "test-build-001"]
      ;; Persist a few events (time-ordered IDs guarantee ordering)
      (event-store/persist-event! ds {:build-id build-id
                                       :event-type :build-started
                                       :data {:message "Build started"}})
      (event-store/persist-event! ds {:build-id build-id
                                       :event-type :stage-started
                                       :stage-name "Build"
                                       :data {:stage "Build"}})
      (event-store/persist-event! ds {:build-id build-id
                                       :event-type :step-completed
                                       :stage-name "Build"
                                       :step-name "Compile"
                                       :data {:exit-code 0}})
      (let [events (event-store/list-events ds build-id)]
        (is (= 3 (count events))
            "Should retrieve all 3 events")
        (is (= [:build-started :stage-started :step-completed]
               (mapv :event-type events))
            "Events should be in ascending order")
        (is (= "Build" (:stage-name (second events)))
            "Stage name should be preserved")
        (is (= "Compile" (:step-name (last events)))
            "Step name should be preserved")
        (is (= {:exit-code 0} (:data (last events)))
            "Data should be deserialized correctly")))))

(deftest events-ordered-by-id-test
  (testing "events are returned in insertion order via time-ordered IDs"
    (let [ds (conn/create-datasource test-db-path)
          build-id "test-build-order"]
      (doseq [i (range 5)]
        (event-store/persist-event! ds {:build-id build-id
                                         :event-type :step-output
                                         :data {:line i}}))
      (let [events (event-store/list-events ds build-id)
            data-lines (mapv #(get-in % [:data :line]) events)]
        (is (= [0 1 2 3 4] data-lines)
            "Events should be in insertion order")))))

(deftest after-id-pagination-test
  (testing "after-id returns only events after the given event"
    (let [ds (conn/create-datasource test-db-path)
          build-id "test-build-pagination"]
      ;; Insert 4 events (time-ordered IDs guarantee ordering)
      (let [id1 (event-store/persist-event! ds {:build-id build-id
                                                 :event-type :build-started
                                                 :data {:n 1}})
            id2 (event-store/persist-event! ds {:build-id build-id
                                                 :event-type :stage-started
                                                 :data {:n 2}})
            _id3 (event-store/persist-event! ds {:build-id build-id
                                                  :event-type :step-completed
                                                  :data {:n 3}})
            _id4 (event-store/persist-event! ds {:build-id build-id
                                                  :event-type :build-completed
                                                  :data {:n 4}})]
        ;; Get events after id2
        (let [events (event-store/list-events ds build-id :after-id id2)]
          (is (= 2 (count events))
              "Should return 2 events after id2")
          (is (= [3 4] (mapv #(get-in % [:data :n]) events))
              "Should return events 3 and 4"))
        ;; Get events after id1
        (let [events (event-store/list-events ds build-id :after-id id1)]
          (is (= 3 (count events))
              "Should return 3 events after id1"))))))

(deftest event-type-filtering-test
  (testing "event-type filter returns only matching events"
    (let [ds (conn/create-datasource test-db-path)
          build-id "test-build-filter"]
      (event-store/persist-event! ds {:build-id build-id :event-type :build-started})
      (event-store/persist-event! ds {:build-id build-id :event-type :step-output})
      (event-store/persist-event! ds {:build-id build-id :event-type :step-output})
      (event-store/persist-event! ds {:build-id build-id :event-type :build-completed})
      ;; Filter by :step-output
      (let [events (event-store/list-events ds build-id :event-type :step-output)]
        (is (= 2 (count events))
            "Should return 2 step-output events")
        (is (every? #(= :step-output (:event-type %)) events)
            "All returned events should be step-output"))
      ;; Filter by :build-completed
      (let [events (event-store/list-events ds build-id :event-type :build-completed)]
        (is (= 1 (count events))
            "Should return 1 build-completed event")))))

(deftest count-events-test
  (testing "count-events returns correct count"
    (let [ds (conn/create-datasource test-db-path)
          build-id "test-build-count"
          other-id "test-build-other"]
      (is (= 0 (event-store/count-events ds build-id))
          "Empty build should have 0 events")
      (dotimes [_ 5]
        (event-store/persist-event! ds {:build-id build-id :event-type :step-output}))
      (dotimes [_ 3]
        (event-store/persist-event! ds {:build-id other-id :event-type :step-output}))
      (is (= 5 (event-store/count-events ds build-id))
          "Should count only events for the given build")
      (is (= 3 (event-store/count-events ds other-id))
          "Other build should have its own count"))))
