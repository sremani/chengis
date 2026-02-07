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
