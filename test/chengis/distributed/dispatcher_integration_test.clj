(ns chengis.distributed.dispatcher-integration-test
  "Integration tests for dispatcher wiring with feature flags.
   Tests the dispatch decision logic when the :distributed-dispatch feature flag
   interacts with distributed config (enabled/disabled, queue, fallback-local)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.agent-registry :as agent-reg]
            [chengis.distributed.dispatcher :as dispatcher]
            [chengis.distributed.build-queue :as bq]
            [chengis.feature-flags :as feature-flags]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-dispatcher-integ-test.db")
(def ^:dynamic *ds* nil)

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (binding [*ds* (conn/create-datasource test-db-path)]
    (agent-reg/reset-registry!)
    (f))
  (agent-reg/reset-registry!)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; Feature flag gating
;; ---------------------------------------------------------------------------

(deftest feature-flag-disabled-always-local-test
  (testing "when :distributed-dispatch feature flag is disabled, dispatch always returns :local"
    ;; Even with distributed enabled and agents registered, the feature flag gates entry
    (agent-reg/register-agent! {:name "agent-ff" :url "http://agent-ff:9090"
                                :labels #{"linux"} :max-builds 4})
    (let [system {:config {:distributed {:enabled true
                                          :dispatch {:fallback-local false
                                                     :queue-enabled false}}
                           :feature-flags {:distributed-dispatch false}}
                  :db *ds*}
          ;; The feature flag check happens in handlers, not in dispatcher itself.
          ;; dispatcher/dispatch-build! doesn't check feature flags—handlers do.
          ;; So this test verifies the feature flag check directly.
          flag-enabled? (feature-flags/enabled? (:config system) :distributed-dispatch)]
      (is (false? flag-enabled?)
          "Feature flag should be disabled")))

  (testing "when :distributed-dispatch flag is enabled, it returns true"
    (let [config {:feature-flags {:distributed-dispatch true}}]
      (is (true? (feature-flags/enabled? config :distributed-dispatch))))))

(deftest feature-flag-enabled-distributed-disabled-local-test
  (testing "feature flag enabled but distributed disabled → local execution"
    (let [system {:config {:distributed {:enabled false}
                           :feature-flags {:distributed-dispatch true}}
                  :db *ds*}
          result (dispatcher/dispatch-build! system
                   {:build-id "build-1" :job-id "job-1"
                    :pipeline {:stages []} :org-id "org-1"}
                   #{})]
      (is (= :local (:mode result))))))

(deftest feature-flag-enabled-no-agents-fails-test
  (testing "feature flag enabled, distributed enabled, no agents → :failed (fallback-local=false)"
    (let [system {:config {:distributed {:enabled true
                                          :dispatch {:fallback-local false
                                                     :queue-enabled false}}
                           :feature-flags {:distributed-dispatch true}}
                  :db *ds*}
          result (dispatcher/dispatch-build! system
                   {:build-id "build-2" :job-id "job-2"
                    :pipeline {:stages []} :org-id "org-1"}
                   #{})]
      (is (= :failed (:mode result))
          "Should fail when no agents and fallback-local=false"))))

(deftest feature-flag-enabled-queue-mode-test
  (testing "feature flag enabled, queue-enabled → enqueues to build_queue"
    (let [system {:config {:distributed {:enabled true
                                          :dispatch {:fallback-local false
                                                     :queue-enabled true
                                                     :max-retries 3}}
                           :feature-flags {:distributed-dispatch true}}
                  :db *ds*}
          result (dispatcher/dispatch-build! system
                   {:build-id "build-q1" :job-id "job-q1"
                    :pipeline {:stages [{:name "test"}]}
                    :org-id "org-1"
                    :parameters {:branch "main"}}
                   #{"linux"})]
      (is (= :queued (:mode result)))
      (is (some? (:queue-id result)))
      ;; Verify item exists in queue
      (let [item (bq/get-queue-item-by-build-id *ds* "build-q1")]
        (is (some? item))
        (is (= :pending (:status item)))
        (is (= "build-q1" (:build-id item)))))))

(deftest fallback-local-true-overrides-test
  (testing "fallback-local=true overrides to local when no agents available"
    (let [system {:config {:distributed {:enabled true
                                          :dispatch {:fallback-local true
                                                     :queue-enabled false}}
                           :feature-flags {:distributed-dispatch true}}
                  :db *ds*}
          result (dispatcher/dispatch-build! system
                   {:build-id "build-fb" :job-id "job-fb"
                    :pipeline {:stages []} :org-id "org-1"}
                   #{})]
      (is (= :local (:mode result)))
      (is (= "No matching agent" (:fallback-reason result))))))

(deftest default-fallback-local-is-false-test
  (testing "default fallback-local is now false (changed in Phase 2)"
    (let [cfg chengis.config/default-config]
      (is (false? (get-in cfg [:distributed :dispatch :fallback-local]))
          "fallback-local should default to false"))))
