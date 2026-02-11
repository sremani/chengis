(ns chengis.distributed.dispatcher-test
  (:require [clojure.test :refer :all]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.dispatcher :as dispatcher]))

(use-fixtures :each
  (fn [f]
    (agent-reg/reset-registry!)
    (f)
    (agent-reg/reset-registry!)))

(deftest dispatch-disabled-test
  (testing "returns :local when distributed is disabled"
    (let [system {:config {:distributed {:enabled false}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :local (:mode result)))))

  (testing "returns :local when no distributed config at all"
    (let [system {:config {}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :local (:mode result))))))

(deftest dispatch-no-agent-test
  (testing "falls back to local when no agent available"
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {:fallback-local true}}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :local (:mode result)))
      (is (some? (:fallback-reason result)))))

  (testing "returns :failed when no fallback allowed"
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {:fallback-local false}}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :failed (:mode result))))))

(deftest dispatch-with-labels-test
  (testing "no agent matches required labels → local fallback"
    (agent-reg/register-agent! {:name "a1" :url "http://a1:9090"
                                :labels #{"linux"} :max-builds 2})
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {:fallback-local true}}}}
          result (dispatcher/dispatch-build! system {} #{"windows"})]
      (is (= :local (:mode result))))))

;; ---------------------------------------------------------------------------
;; Phase 1 mutation remediation: test default boolean values in dispatch
;; ---------------------------------------------------------------------------

(deftest dispatch-default-fallback-local-test
  (testing "fallback-local defaults to false when not specified in config"
    ;; dispatch config has no :fallback-local key — should default to false,
    ;; meaning no fallback → :failed when no agent is available
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {}}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :failed (:mode result))
          "Without :fallback-local, dispatch should fail, not fall back to local")))

  (testing "fallback-local explicitly true allows local fallback"
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {:fallback-local true}}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :local (:mode result))))))

(deftest dispatch-default-queue-enabled-test
  (testing "queue-enabled defaults to false when not specified"
    ;; When no agents and no :queue-enabled key, should NOT try to enqueue
    (let [system {:config {:distributed {:enabled true
                                         :dispatch {:fallback-local false}}}}
          result (dispatcher/dispatch-build! system {} #{})]
      (is (= :failed (:mode result))
          "Without :queue-enabled, should not attempt to enqueue"))))

;; ---------------------------------------------------------------------------
;; Phase 3a: Dispatcher missing code path tests
;; ---------------------------------------------------------------------------

(deftest dispatch-remote-http-success-test
  (testing "successful HTTP dispatch returns :remote with agent-id"
    (agent-reg/register-agent! {:name "good-agent" :url "http://good:9090"
                                 :labels #{} :max-builds 5})
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)]
                      (deliver p {:status 200 :body "{\"ok\":true}"})
                      p))]
      (let [system {:config {:distributed {:enabled true
                                            :dispatch {:fallback-local false}}}}
            result (dispatcher/dispatch-build! system {:build-id "b1"} #{})]
        (is (= :remote (:mode result)))
        (is (some? (:agent-id result)))))))

(deftest dispatch-remote-http-failure-fallback-test
  (testing "HTTP failure with fallback-local=true returns :local"
    (agent-reg/register-agent! {:name "bad-agent" :url "http://bad:9090"
                                 :labels #{} :max-builds 5})
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)]
                      (deliver p {:status 500 :body "{\"error\":\"fail\"}"})
                      p))]
      (let [system {:config {:distributed {:enabled true
                                            :dispatch {:fallback-local true}}}}
            result (dispatcher/dispatch-build! system {:build-id "b2"} #{})]
        (is (= :local (:mode result)))
        (is (some? (:fallback-reason result)))))))

(deftest dispatch-remote-http-failure-no-fallback-test
  (testing "HTTP failure with fallback-local=false returns :failed"
    (agent-reg/register-agent! {:name "failing-agent" :url "http://fail:9090"
                                 :labels #{} :max-builds 5})
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (let [p (promise)]
                      (deliver p {:status 503 :body "{}"})
                      p))]
      (let [system {:config {:distributed {:enabled true
                                            :dispatch {:fallback-local false}}}}
            result (dispatcher/dispatch-build! system {:build-id "b3"} #{})]
        (is (= :failed (:mode result)))
        (is (some? (:error result)))))))

(deftest dispatch-remote-exception-test
  (testing "exception during dispatch returns :failed with error message"
    (agent-reg/register-agent! {:name "except-agent" :url "http://except:9090"
                                 :labels #{} :max-builds 5})
    (with-redefs [org.httpkit.client/post
                  (fn [_url _opts]
                    (throw (Exception. "Connection refused")))]
      (let [system {:config {:distributed {:enabled true
                                            :dispatch {:fallback-local false}}}}
            result (dispatcher/dispatch-build! system {:build-id "b4"} #{})]
        (is (= :failed (:mode result)))
        (is (= "Connection refused" (:error result)))))))

(deftest dispatch-queue-mode-test
  (testing "queue mode enqueues and returns :queued with queue-id"
    (let [fake-item (atom nil)]
      (with-redefs [chengis.distributed.build-queue/enqueue!
                    (fn [_ds build-id _job-id _payload _labels & [opts]]
                      (let [item {:id "q-123" :build-id build-id
                                  :max-retries (or (:max-retries opts) 3)}]
                        (reset! fake-item item)
                        item))]
        (let [system {:config {:distributed {:enabled true
                                              :dispatch {:queue-enabled true
                                                         :max-retries 5}}}
                      :db {:datasource "fake"}}
              result (dispatcher/dispatch-build! system {:build-id "bq1" :job-id "j1"} #{"linux"})]
          (is (= :queued (:mode result)))
          (is (= "q-123" (:queue-id result))))))))

(deftest dispatch-increment-builds-called-test
  (testing "successful dispatch increments agent build count"
    (agent-reg/register-agent! {:name "count-agent" :url "http://cnt:9090"
                                 :labels #{} :max-builds 5})
    (let [agents (agent-reg/list-agents)
          agent (first agents)
          initial-builds (:current-builds agent)]
      (with-redefs [org.httpkit.client/post
                    (fn [_url _opts]
                      (let [p (promise)]
                        (deliver p {:status 200 :body "{}"})
                        p))]
        (let [system {:config {:distributed {:enabled true}}}]
          (dispatcher/dispatch-build! system {:build-id "b5"} #{})
          ;; Check builds were incremented
          (let [updated (agent-reg/get-agent (:id agent))]
            (is (= (inc (or initial-builds 0)) (:current-builds updated)))))))))
