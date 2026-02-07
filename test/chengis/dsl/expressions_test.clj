(ns chengis.dsl.expressions-test
  (:require [clojure.test :refer :all]
            [chengis.dsl.expressions :as expr]))

(deftest parse-expression-test
  (testing "parses parameter expressions"
    (is (= {:type :parameters :name "environment"}
           (expr/parse-expression "parameters.environment"))))

  (testing "parses secret expressions"
    (is (= {:type :secrets :name "SLACK_WEBHOOK"}
           (expr/parse-expression "secrets.SLACK_WEBHOOK"))))

  (testing "parses env expressions"
    (is (= {:type :env :name "CI"}
           (expr/parse-expression "env.CI"))))

  (testing "returns nil for unknown expressions"
    (is (nil? (expr/parse-expression "unknown.thing")))
    (is (nil? (expr/parse-expression "nope")))))

(deftest resolve-expression-test
  (testing "resolves parameters from context"
    (let [parsed {:type :parameters :name "env"}
          ctx {:parameters {:env "production"}}]
      (is (= "production" (expr/resolve-expression parsed ctx)))))

  (testing "deferred parameter resolution returns env var format"
    (let [parsed {:type :parameters :name "deploy-target"}]
      (is (= "${PARAM_DEPLOY_TARGET}" (expr/resolve-expression parsed {})))))

  (testing "resolves secrets from context"
    (let [parsed {:type :secrets :name "API_KEY"}
          ctx {:secrets {"API_KEY" "sk-1234"}}]
      (is (= "sk-1234" (expr/resolve-expression parsed ctx)))))

  (testing "deferred secret resolution"
    (let [parsed {:type :secrets :name "TOKEN"}]
      (is (= "${TOKEN}" (expr/resolve-expression parsed {}))))))

(deftest resolve-string-test
  (testing "resolves single expression in string"
    (is (= "production"
           (expr/resolve-string "${{ parameters.env }}"
                                {:parameters {:env "production"}}))))

  (testing "resolves multiple expressions in string"
    (is (= "deploy to production via sk-123"
           (expr/resolve-string "deploy to ${{ parameters.env }} via ${{ secrets.KEY }}"
                                {:parameters {:env "production"}
                                 :secrets {"KEY" "sk-123"}}))))

  (testing "leaves unrecognized expressions"
    (is (= "${{unknown.stuff}}"
           (expr/resolve-string "${{ unknown.stuff }}" {}))))

  (testing "handles non-string input"
    (is (= 42 (expr/resolve-string 42 {})))
    (is (= nil (expr/resolve-string nil {})))))

(deftest resolve-expressions-test
  (testing "resolves expressions in nested map"
    (let [data {:env {"TOKEN" "${{ secrets.TOKEN }}"
                      "ENV" "${{ parameters.environment }}"}
                :steps [{:name "deploy"
                         :run "echo ${{ parameters.environment }}"}]}
          ctx {:parameters {:environment "staging"}
               :secrets {"TOKEN" "abc123"}}
          result (expr/resolve-expressions data ctx)]
      (is (= "abc123" (get-in result [:env "TOKEN"])))
      (is (= "staging" (get-in result [:env "ENV"])))
      (is (= "echo staging" (get-in result [:steps 0 :run])))))

  (testing "handles empty data"
    (is (= {} (expr/resolve-expressions {} {})))
    (is (= [] (expr/resolve-expressions [] {})))))

(deftest has-expressions-test
  (testing "detects expressions"
    (is (true? (expr/has-expressions? "${{ parameters.env }}")))
    (is (true? (expr/has-expressions? "start ${{ secrets.KEY }} end"))))

  (testing "returns false for non-expression strings"
    (is (false? (expr/has-expressions? "no expressions here")))
    (is (false? (expr/has-expressions? ""))))

  (testing "returns nil for non-strings"
    (is (nil? (expr/has-expressions? 42)))
    (is (nil? (expr/has-expressions? nil)))))
