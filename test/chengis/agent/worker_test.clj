(ns chengis.agent.worker-test
  (:require [clojure.test :refer :all]
            [chengis.agent.worker :as worker]))

(deftest current-build-count-test
  (testing "initial build count is zero"
    (is (= 0 (worker/current-build-count)))))
