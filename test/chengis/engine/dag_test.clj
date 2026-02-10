(ns chengis.engine.dag-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.engine.dag :as dag]))

(deftest has-dag?-test
  (testing "returns false when no stages have :depends-on"
    (is (false? (dag/has-dag? [{:stage-name "A" :steps []}
                                {:stage-name "B" :steps []}]))))

  (testing "returns true when any stage has :depends-on"
    (is (true? (dag/has-dag? [{:stage-name "A" :steps []}
                               {:stage-name "B" :depends-on ["A"] :steps []}]))))

  (testing "returns false for empty stages"
    (is (false? (dag/has-dag? [])))))

(deftest build-dag-test
  (testing "builds DAG with no dependencies"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :steps []}]
          dag (dag/build-dag stages)]
      (is (= #{"A" "B"} (set (keys dag))))
      (is (= #{} (get dag "A")))
      (is (= #{} (get dag "B")))))

  (testing "builds DAG with linear dependencies (A→B→C)"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["B"] :steps []}]
          dag (dag/build-dag stages)]
      (is (= #{} (get dag "A")))
      (is (= #{"A"} (get dag "B")))
      (is (= #{"B"} (get dag "C")))))

  (testing "builds diamond DAG (A→B,C→D)"
    (let [stages [{:stage-name "A" :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}
                  {:stage-name "C" :depends-on ["A"] :steps []}
                  {:stage-name "D" :depends-on ["B" "C"] :steps []}]
          dag (dag/build-dag stages)]
      (is (= #{} (get dag "A")))
      (is (= #{"A"} (get dag "B")))
      (is (= #{"A"} (get dag "C")))
      (is (= #{"B" "C"} (get dag "D")))))

  (testing "detects cycle and throws"
    (let [stages [{:stage-name "A" :depends-on ["B"] :steps []}
                  {:stage-name "B" :depends-on ["A"] :steps []}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cycle detected"
            (dag/build-dag stages)))))

  (testing "detects self-dependency and throws"
    (let [stages [{:stage-name "A" :depends-on ["A"] :steps []}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"depends on itself"
            (dag/build-dag stages)))))

  (testing "detects unknown dependency and throws"
    (let [stages [{:stage-name "A" :depends-on ["Z"] :steps []}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown stage"
            (dag/build-dag stages))))))

(deftest topological-sort-test
  (testing "sorts linear chain correctly"
    (let [dag {"A" #{} "B" #{"A"} "C" #{"B"}}
          sorted (dag/topological-sort dag)]
      (is (= 3 (count sorted)))
      ;; A must come before B, B before C
      (is (< (.indexOf sorted "A") (.indexOf sorted "B")))
      (is (< (.indexOf sorted "B") (.indexOf sorted "C")))))

  (testing "sorts diamond correctly"
    (let [dag {"A" #{} "B" #{"A"} "C" #{"A"} "D" #{"B" "C"}}
          sorted (dag/topological-sort dag)]
      (is (= 4 (count sorted)))
      ;; A must come before B and C, both before D
      (is (< (.indexOf sorted "A") (.indexOf sorted "B")))
      (is (< (.indexOf sorted "A") (.indexOf sorted "C")))
      (is (< (.indexOf sorted "B") (.indexOf sorted "D")))
      (is (< (.indexOf sorted "C") (.indexOf sorted "D")))))

  (testing "sorts independent nodes"
    (let [dag {"A" #{} "B" #{} "C" #{}}
          sorted (dag/topological-sort dag)]
      (is (= 3 (count sorted)))
      (is (= #{"A" "B" "C"} (set sorted))))))

(deftest ready-stages-test
  (testing "all stages ready when no dependencies"
    (let [dag {"A" #{} "B" #{} "C" #{}}]
      (is (= #{"A" "B" "C"} (dag/ready-stages dag #{})))))

  (testing "only root stages ready initially"
    (let [dag {"A" #{} "B" #{"A"} "C" #{"A"}}]
      (is (= #{"A"} (dag/ready-stages dag #{})))
      (is (= #{"B" "C"} (dag/ready-stages dag #{"A"})))))

  (testing "diamond: D ready only when both B and C complete"
    (let [dag {"A" #{} "B" #{"A"} "C" #{"A"} "D" #{"B" "C"}}]
      (is (= #{"A"} (dag/ready-stages dag #{})))
      (is (= #{"B" "C"} (dag/ready-stages dag #{"A"})))
      (is (= #{"D"} (dag/ready-stages dag #{"A" "B" "C"})))))

  (testing "completed stages not returned"
    (let [dag {"A" #{} "B" #{}}]
      (is (= #{"B"} (dag/ready-stages dag #{"A"})))
      (is (= #{} (dag/ready-stages dag #{"A" "B"}))))))

(deftest stage-by-name-test
  (testing "finds stage by name"
    (let [stages [{:stage-name "A" :steps [1]}
                  {:stage-name "B" :steps [2]}]]
      (is (= {:stage-name "A" :steps [1]} (dag/stage-by-name stages "A")))
      (is (= {:stage-name "B" :steps [2]} (dag/stage-by-name stages "B")))))

  (testing "returns nil for unknown name"
    (is (nil? (dag/stage-by-name [{:stage-name "A"}] "Z")))))
