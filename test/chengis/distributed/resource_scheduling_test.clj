(ns chengis.distributed.resource-scheduling-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.distributed.agent-registry :as registry]))

(defn clean-registry [f]
  (registry/reset-registry!)
  (registry/set-config! {})
  (f)
  (registry/reset-registry!)
  (registry/set-config! {}))

(use-fixtures :each clean-registry)

;; ---------------------------------------------------------------------------
;; score-agent tests (via find-available-agent behavior)
;; ---------------------------------------------------------------------------

(deftest least-loaded-without-feature-flag-test
  (testing "without resource-aware flag, selects least loaded agent"
    ;; Register two agents with different loads
    (let [a1 (registry/register-agent!
               {:name "agent-1" :url "http://a1:9090" :labels #{"linux"}
                :max-builds 4 :system-info {:cpu-count 2 :memory-gb 4}})
          a2 (registry/register-agent!
               {:name "agent-2" :url "http://a2:9090" :labels #{"linux"}
                :max-builds 4 :system-info {:cpu-count 8 :memory-gb 32}})]
      ;; Give agent-1 some load
      (registry/increment-builds! (:id a1))
      (registry/increment-builds! (:id a1))
      ;; Without feature flag, should prefer least loaded (agent-2)
      (let [selected (registry/find-available-agent #{"linux"})]
        (is (some? selected))
        (is (= (:id a2) (:id selected)))))))

(deftest resource-aware-scoring-test
  (testing "with resource-aware flag, scores by load + cpu + memory"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling true}})
    ;; Agent-1: low spec, no load
    (let [a1 (registry/register-agent!
               {:name "agent-1" :url "http://a1:9090" :labels #{"linux"}
                :max-builds 2 :system-info {:cpu-count 2 :memory-gb 4}})
          ;; Agent-2: high spec, no load
          a2 (registry/register-agent!
               {:name "agent-2" :url "http://a2:9090" :labels #{"linux"}
                :max-builds 4 :system-info {:cpu-count 16 :memory-gb 32}})]
      ;; With resource-aware scoring, agent-2 should be preferred (higher cpu+mem scores)
      (let [selected (registry/find-available-agent #{"linux"})]
        (is (some? selected))
        (is (= (:id a2) (:id selected)))))))

(deftest resource-aware-prefers-less-loaded-test
  (testing "resource-aware scoring prefers less loaded agent when specs similar"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling true}})
    (let [a1 (registry/register-agent!
               {:name "agent-1" :url "http://a1:9090" :labels #{"linux"}
                :max-builds 4 :system-info {:cpu-count 8 :memory-gb 16}})
          a2 (registry/register-agent!
               {:name "agent-2" :url "http://a2:9090" :labels #{"linux"}
                :max-builds 4 :system-info {:cpu-count 8 :memory-gb 16}})]
      ;; Load agent-1 heavily
      (dotimes [_ 3] (registry/increment-builds! (:id a1)))
      ;; Agent-2 should be preferred (less loaded, same specs)
      (let [selected (registry/find-available-agent #{"linux"})]
        (is (= (:id a2) (:id selected)))))))

(deftest resource-filter-excludes-insufficient-agents-test
  (testing "resource requirements filter out agents below minimums"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling true}})
    (let [_small (registry/register-agent!
                   {:name "small" :url "http://s:9090" :labels #{"linux"}
                    :max-builds 4 :system-info {:cpu-count 2 :memory-gb 4}})
          big (registry/register-agent!
                {:name "big" :url "http://b:9090" :labels #{"linux"}
                 :max-builds 4 :system-info {:cpu-count 8 :memory-gb 32}})]
      ;; Request 4 CPUs and 16GB — small agent doesn't qualify
      (let [selected (registry/find-available-agent #{"linux"}
                       :resources {:cpu 4 :memory 16})]
        (is (some? selected))
        (is (= (:id big) (:id selected)))))))

(deftest no-resource-requirements-backward-compat-test
  (testing "no resource requirements → original behavior when flag on"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling true}})
    (let [a1 (registry/register-agent!
               {:name "agent-1" :url "http://a1:9090" :labels #{}
                :max-builds 2})]
      ;; Agent without system-info should still be selectable
      (let [selected (registry/find-available-agent #{})]
        (is (some? selected))
        (is (= (:id a1) (:id selected)))))))

(deftest agent-no-system-info-still-selected-test
  (testing "agent without system-info is selected when no requirements"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling true}})
    (let [a1 (registry/register-agent!
               {:name "bare-agent" :url "http://a:9090" :labels #{"linux"}
                :max-builds 2})]
      (let [selected (registry/find-available-agent #{"linux"})]
        (is (some? selected))
        (is (= (:id a1) (:id selected)))))))

(deftest resource-requirements-with-flag-off-test
  (testing "resource requirements ignored when flag is off"
    (registry/set-config!
      {:feature-flags {:resource-aware-scheduling false}})
    (let [small (registry/register-agent!
                  {:name "small" :url "http://s:9090" :labels #{"linux"}
                   :max-builds 4 :system-info {:cpu-count 1 :memory-gb 1}})]
      ;; Even with high resource requirements, small agent is returned
      ;; because the flag is off and resource filtering is bypassed
      (let [selected (registry/find-available-agent #{"linux"}
                       :resources {:cpu 16 :memory 64})]
        (is (some? selected))
        (is (= (:id small) (:id selected)))))))
