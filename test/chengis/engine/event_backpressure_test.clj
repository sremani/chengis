(ns chengis.engine.event-backpressure-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [chengis.engine.event-backpressure :as bp]))

(deftest critical-event-detection-test
  (testing "build lifecycle events are critical"
    (is (true? (bp/critical-event? {:event-type :build-started})))
    (is (true? (bp/critical-event? {:event-type :build-completed})))
    (is (true? (bp/critical-event? {:event-type :build-cancelled})))
    (is (true? (bp/critical-event? {:event-type :stage-completed})))
    (is (true? (bp/critical-event? {:event-type :step-completed})))
    (is (true? (bp/critical-event? {:event-type :step-started}))))

  (testing "log-line and other events are non-critical"
    (is (false? (bp/critical-event? {:event-type :log-line})))
    (is (false? (bp/critical-event? {:event-type :heartbeat})))
    (is (false? (bp/critical-event? {:event-type :progress})))
    (is (false? (bp/critical-event? {:event-type :unknown-type})))))

(deftest publish-non-critical-on-open-channel-test
  (testing "non-critical event published successfully on open channel"
    (let [ch (async/chan 10)
          result (bp/publish-with-backpressure! ch
                   {:event-type :log-line :build-id "b1"} 1000)]
      (is (= :published result))
      (is (some? (async/poll! ch)))
      (async/close! ch))))

(deftest publish-non-critical-on-full-channel-test
  (testing "non-critical event dropped when channel is full"
    (let [ch (async/chan 1)]
      ;; Fill the channel
      (async/offer! ch {:event-type :filler})
      (let [result (bp/publish-with-backpressure! ch
                     {:event-type :log-line :build-id "b1"} 1000)]
        (is (= :dropped result)))
      (async/close! ch))))

(deftest publish-critical-on-open-channel-test
  (testing "critical event published on open channel"
    (let [ch (async/chan 10)
          result (bp/publish-with-backpressure! ch
                   {:event-type :build-completed :build-id "b1"} 1000)]
      (is (= :published result))
      (async/close! ch))))

(deftest publish-critical-on-full-channel-timeout-test
  (testing "critical event times out on full channel"
    (let [ch (async/chan 1)]
      ;; Fill the channel
      (async/offer! ch {:event-type :filler})
      (let [result (bp/publish-with-backpressure! ch
                     {:event-type :build-completed :build-id "b1"} 50)]
        (is (= :timeout result)))
      (async/close! ch))))

(deftest publish-critical-on-channel-that-drains-test
  (testing "critical event succeeds when channel drains within timeout"
    (let [ch (async/chan 1)]
      ;; Fill the channel
      (async/offer! ch {:event-type :filler})
      ;; Drain after 20ms
      (async/go
        (async/<! (async/timeout 20))
        (async/<! ch))
      (let [result (bp/publish-with-backpressure! ch
                     {:event-type :build-completed :build-id "b1"} 500)]
        (is (= :published result)))
      (async/close! ch))))

(deftest metrics-fn-called-test
  (testing "metrics function is called with type and result"
    (let [ch (async/chan 10)
          calls (atom [])]
      (bp/publish-with-backpressure! ch
        {:event-type :log-line :build-id "b1"} 1000
        :metrics-fn (fn [type result] (swap! calls conj [type result])))
      (is (= [["non-critical" :published]] @calls))
      (async/close! ch))))

(deftest metrics-fn-on-drop-test
  (testing "metrics function records drops"
    (let [ch (async/chan 1)
          calls (atom [])]
      (async/offer! ch {:event-type :filler})
      (bp/publish-with-backpressure! ch
        {:event-type :log-line :build-id "b1"} 1000
        :metrics-fn (fn [type result] (swap! calls conj [type result])))
      (is (= [["non-critical" :dropped]] @calls))
      (async/close! ch))))

(deftest queue-depth-sampler-test
  (testing "sampler reports channel depth"
    (let [ch (async/chan 100)
          depths (atom [])]
      ;; Put some items in the channel
      (dotimes [_ 5]
        (async/offer! ch {:event-type :test}))
      (let [stop-fn (bp/start-queue-depth-sampler ch 50
                      (fn [depth] (swap! depths conj depth)))]
        (Thread/sleep 150)
        (stop-fn)
        (is (pos? (count @depths)))
        (is (every? #(>= % 5) @depths)))
      (async/close! ch))))

(deftest sliding-buffer-behavior-test
  (testing "sliding buffer drops oldest items"
    (let [ch (async/chan (async/sliding-buffer 3))]
      ;; Put 5 items, buffer holds 3
      (dotimes [i 5]
        (async/>!! ch {:n i}))
      ;; Should get items 2, 3, 4 (oldest dropped)
      (is (= 2 (:n (async/<!! ch))))
      (is (= 3 (:n (async/<!! ch))))
      (is (= 4 (:n (async/<!! ch))))
      (async/close! ch))))

(deftest empty-channel-publish-test
  (testing "publish works on freshly created channel"
    (let [ch (async/chan 100)]
      (is (= :published
             (bp/publish-with-backpressure! ch
               {:event-type :build-started :build-id "b1"} 1000)))
      (is (= :published
             (bp/publish-with-backpressure! ch
               {:event-type :log-line :build-id "b1"} 1000)))
      (async/close! ch))))

(deftest high-volume-non-critical-test
  (testing "high volume of non-critical events"
    (let [ch (async/chan 100)
          published (atom 0)
          dropped (atom 0)]
      ;; Fill channel beyond capacity
      (dotimes [_ 150]
        (let [r (bp/publish-with-backpressure! ch
                  {:event-type :log-line :build-id "b1"} 100)]
          (if (= :published r)
            (swap! published inc)
            (swap! dropped inc))))
      (is (= 100 @published))
      (is (= 50 @dropped))
      (async/close! ch))))

(deftest critical-event-types-set-test
  (testing "all expected event types are in the critical set"
    (is (= #{:build-started :build-completed :build-cancelled
             :stage-started :stage-completed
             :step-started :step-completed}
           bp/critical-event-types))))
