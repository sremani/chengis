(ns chengis.engine.events-global-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [chengis.engine.events :as events]))

(deftest global-topic-broadcast-test
  (testing "build-completed events are broadcast to :global topic"
    (let [global-ch (events/subscribe :global)]
      (try
        ;; Publish a build-completed event
        (events/publish! {:build-id "test-build-global"
                          :event-type :build-completed
                          :data {:build-status :success
                                 :operation "test-job"}})
        ;; Check that the global channel received the event
        (let [[event _] (async/alts!! [global-ch (async/timeout 2000)])]
          (is (some? event))
          (when event
            (is (= :build-completed (:event-type event)))
            (is (= :global (:build-id event)))))
        (finally
          (events/unsubscribe :global global-ch))))))

(deftest global-topic-ignores-non-completion-test
  (testing "non build-completed events are NOT broadcast to :global topic"
    (let [global-ch (events/subscribe :global)]
      (try
        ;; Publish a log-line event
        (events/publish! {:build-id "test-build-logline"
                          :event-type :log-line
                          :data {:line "hello"}})
        ;; Global channel should NOT receive it (timeout)
        (let [[event ch] (async/alts!! [global-ch (async/timeout 500)])]
          (is (or (nil? event) (not= global-ch ch))))
        (finally
          (events/unsubscribe :global global-ch))))))
