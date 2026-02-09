(ns chengis.distributed.build-queue-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.build-queue :as bq]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-queue-test.db")
(def ^:dynamic *ds* nil)

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (binding [*ds* (conn/create-datasource test-db-path)]
    (f))
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Enqueue & dequeue
;; ---------------------------------------------------------------------------

(deftest enqueue-test
  (testing "enqueue adds item to queue"
    (let [item (bq/enqueue! *ds* "build-1" "job-1"
                 {:pipeline {:stages []} :build-id "build-1"}
                 #{"linux"})]
      (is (some? (:id item)))
      (is (= "build-1" (:build-id item)))
      (is (= "job-1" (:job-id item)))
      (is (= :pending (:status item)))
      (is (= 0 (:retry-count item)))))

  (testing "enqueue with custom max-retries"
    (let [item (bq/enqueue! *ds* "build-2" "job-1"
                 {:pipeline {:stages []}} nil
                 {:max-retries 5})]
      (is (some? (:id item))))))

(deftest dequeue-test
  (testing "dequeue returns oldest pending item"
    (bq/enqueue! *ds* "build-a" "job-1" {:a 1} nil)
    (Thread/sleep 10) ;; ensure ordering
    (bq/enqueue! *ds* "build-b" "job-1" {:b 2} nil)
    (let [item (bq/dequeue-next! *ds*)]
      (is (some? item))
      (is (= "build-a" (:build-id item)))
      (is (= :dispatching (:status item))))) ;; status transitions to dispatching

  (testing "dequeue returns nil on empty queue"
    ;; Dequeue remaining items
    (bq/dequeue-next! *ds*)
    (is (nil? (bq/dequeue-next! *ds*))))

  (testing "dequeue skips items in dispatching status"
    ;; The first dequeue already changed build-a to dispatching
    ;; Enqueue a fresh item
    (bq/enqueue! *ds* "build-c" "job-1" {:c 3} nil)
    (let [item (bq/dequeue-next! *ds*)]
      (is (some? item))
      (is (= "build-c" (:build-id item))))))

;; ---------------------------------------------------------------------------
;; Mark dispatched / completed
;; ---------------------------------------------------------------------------

(deftest mark-dispatched-test
  (testing "marks item as dispatched with agent-id"
    (let [item (bq/enqueue! *ds* "build-d" "job-1" {:d 4} nil)
          _ (bq/dequeue-next! *ds*)]
      (bq/mark-dispatched! *ds* (:id item) "agent-42")
      (let [found (bq/get-queue-item-by-build-id *ds* "build-d")]
        (is (= :dispatched (:status found)))
        (is (= "agent-42" (:agent-id found)))
        (is (some? (:dispatched-at found)))))))

(deftest mark-completed-test
  (testing "marks item as completed"
    (let [item (bq/enqueue! *ds* "build-e" "job-1" {:e 5} nil)]
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item) "agent-1")
      (bq/mark-completed! *ds* (:id item))
      (let [found (bq/get-queue-item-by-build-id *ds* "build-e")]
        (is (= :completed (:status found)))
        (is (some? (:completed-at found)))))))

(deftest mark-completed-by-build-id-test
  (testing "marks item completed by build-id"
    (let [item (bq/enqueue! *ds* "build-f" "job-1" {:f 6} nil)]
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item) "agent-1")
      (bq/mark-completed-by-build-id! *ds* "build-f")
      (let [found (bq/get-queue-item-by-build-id *ds* "build-f")]
        (is (= :completed (:status found)))))))

;; ---------------------------------------------------------------------------
;; Retry & dead-letter
;; ---------------------------------------------------------------------------

(deftest retry-test
  (testing "failed item gets re-queued with incremented retry count"
    (let [item (bq/enqueue! *ds* "build-r1" "job-1" {:r 1} nil
                 {:max-retries 3})]
      (bq/dequeue-next! *ds*)
      (let [result (bq/mark-failed! *ds* (:id item) "connection timeout")]
        (is (= :retrying result))
        (let [found (bq/get-queue-item-by-build-id *ds* "build-r1")]
          (is (= :pending (:status found)))
          (is (= 1 (:retry-count found)))
          (is (= "connection timeout" (:error found)))
          (is (some? (:next-retry-at found))))))))

(deftest dead-letter-test
  (testing "item moves to dead-letter after exhausting retries"
    (let [item (bq/enqueue! *ds* "build-dl" "job-1" {:dl 1} nil
                 {:max-retries 1})]
      ;; First attempt
      (bq/dequeue-next! *ds*)
      (let [result (bq/mark-failed! *ds* (:id item) "agent down")]
        (is (= :dead-letter result))
        (let [found (bq/get-queue-item-by-build-id *ds* "build-dl")]
          (is (= :dead_letter (:status found)))
          (is (some? (:completed-at found)))))))

  (testing "dead letter items are queryable"
    (let [items (bq/get-dead-letter-items *ds*)]
      (is (>= (count items) 1))
      (is (= :dead_letter (:status (first items)))))))

;; ---------------------------------------------------------------------------
;; Agent-based queries
;; ---------------------------------------------------------------------------

(deftest dispatched-for-agent-test
  (testing "returns items dispatched to a specific agent"
    (let [item1 (bq/enqueue! *ds* "build-g1" "job-1" {:g 1} nil)
          item2 (bq/enqueue! *ds* "build-g2" "job-1" {:g 2} nil)]
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item1) "agent-A")
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item2) "agent-B")
      (let [agent-a-items (bq/get-dispatched-for-agent *ds* "agent-A")]
        (is (= 1 (count agent-a-items)))
        (is (= "build-g1" (:build-id (first agent-a-items))))))))

(deftest requeue-for-agent-test
  (testing "re-queues items for offline agent"
    (let [item1 (bq/enqueue! *ds* "build-h1" "job-1" {:h 1} nil {:max-retries 3})
          item2 (bq/enqueue! *ds* "build-h2" "job-1" {:h 2} nil {:max-retries 3})]
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item1) "agent-dead")
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item2) "agent-dead")
      (let [count (bq/requeue-for-agent! *ds* "agent-dead")]
        (is (= 2 count))
        ;; Both should be back to pending
        (let [found1 (bq/get-queue-item-by-build-id *ds* "build-h1")
              found2 (bq/get-queue-item-by-build-id *ds* "build-h2")]
          (is (= :pending (:status found1)))
          (is (= :pending (:status found2)))
          (is (= 1 (:retry-count found1)))
          (is (nil? (:agent-id found1))))))))

;; ---------------------------------------------------------------------------
;; Queue depth & age
;; ---------------------------------------------------------------------------

(deftest queue-depth-test
  (testing "counts pending items"
    (let [initial-depth (bq/get-queue-depth *ds*)]
      (bq/enqueue! *ds* "build-q1" "job-1" {:q 1} nil)
      (bq/enqueue! *ds* "build-q2" "job-1" {:q 2} nil)
      (is (= (+ initial-depth 2) (bq/get-queue-depth *ds*)))
      ;; Dequeue one
      (bq/dequeue-next! *ds*)
      (is (= (+ initial-depth 1) (bq/get-queue-depth *ds*))))))

(deftest oldest-pending-age-test
  (testing "returns age of oldest pending item"
    (bq/enqueue! *ds* "build-age" "job-1" {:age 1} nil)
    (Thread/sleep 50)
    (let [age (bq/get-oldest-pending-age-ms *ds*)]
      (is (>= age 0)))))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(deftest cleanup-completed-test
  (testing "cleanup removes old completed items"
    (let [item (bq/enqueue! *ds* "build-old" "job-1" {:old 1} nil)]
      (bq/dequeue-next! *ds*)
      (bq/mark-dispatched! *ds* (:id item) "agent-1")
      (bq/mark-completed! *ds* (:id item))
      ;; Cleanup with 0 hours retention should remove it
      (let [deleted (bq/cleanup-completed! *ds* 0)]
        (is (>= deleted 1))))))

;; ---------------------------------------------------------------------------
;; Concurrency safety
;; ---------------------------------------------------------------------------

(deftest concurrent-dequeue-no-double-claim-test
  (testing "concurrent dequeue never claims the same item twice"
    ;; Enqueue several items
    (let [ds *ds*  ;; capture dynamic binding for use in spawned threads
          n 10
          _ (doseq [i (range n)]
              (bq/enqueue! ds (str "conc-build-" i) "job-1"
                           {:i i} nil))
          ;; Launch N threads that all try to dequeue simultaneously.
          ;; SQLite can throw SQLITE_BUSY under contention — retry with backoff,
          ;; just as a real production consumer would.
          results (atom [])
          latch (java.util.concurrent.CountDownLatch. 1)
          threads (mapv (fn [_]
                          (Thread.
                            (fn []
                              (.await latch)
                              (loop [retries 0]
                                (let [item (try (bq/dequeue-next! ds)
                                                (catch org.sqlite.SQLiteException _
                                                  ;; SQLITE_BUSY — back off and retry
                                                  (Thread/sleep (+ 5 (rand-int 20)))
                                                  ::retry))]
                                  (cond
                                    (= item ::retry)
                                    (when (< retries 100) (recur (inc retries)))

                                    (some? item)
                                    (do (swap! results conj (:build-id item))
                                        (recur 0))

                                    ;; nil = queue empty, done
                                    :else nil))))))
                        (range 4))]
      ;; Start all threads, then release them at once
      (doseq [^Thread t threads] (.start t))
      (Thread/sleep 50)  ;; let all threads park on latch
      (.countDown latch)
      (doseq [^Thread t threads] (.join t 10000))
      ;; Every item must be claimed exactly once
      (is (= n (count @results))
          "Each item should be dequeued exactly once")
      (is (= n (count (set @results)))
          "No duplicate build-ids should appear"))))
