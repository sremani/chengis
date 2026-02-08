(ns chengis.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.config :as config]))

(deftest coerce-env-value-test
  (testing "boolean coercion"
    (is (true? (config/coerce-env-value "true")))
    (is (false? (config/coerce-env-value "false"))))

  (testing "numeric coercion"
    (is (= 8080 (config/coerce-env-value "8080")))
    (is (= 0 (config/coerce-env-value "0")))
    (is (= 9999 (config/coerce-env-value "9999"))))

  (testing "keyword coercion"
    (is (= :info (config/coerce-env-value ":info")))
    (is (= :master (config/coerce-env-value ":master")))
    (is (= :json (config/coerce-env-value ":json"))))

  (testing "string passthrough"
    (is (= "hello" (config/coerce-env-value "hello")))
    (is (= "/data/chengis.db" (config/coerce-env-value "/data/chengis.db")))
    (is (= "smtp.example.com" (config/coerce-env-value "smtp.example.com")))
    ;; Not pure digits — stays as string
    (is (= "8080a" (config/coerce-env-value "8080a")))
    ;; Empty string
    (is (= "" (config/coerce-env-value "")))))

(deftest deep-merge-test
  (testing "basic map merge"
    (is (= {:a 1 :b 2}
           (config/deep-merge {:a 1} {:b 2}))))

  (testing "nested map merge"
    (is (= {:server {:port 9090 :host "localhost"}}
           (config/deep-merge {:server {:port 8080 :host "localhost"}}
                              {:server {:port 9090}}))))

  (testing "deeply nested merge"
    (is (= {:distributed {:enabled true
                          :dispatch {:queue-enabled true
                                     :max-retries 3}}}
           (config/deep-merge {:distributed {:enabled false
                                             :dispatch {:queue-enabled false
                                                        :max-retries 3}}}
                              {:distributed {:enabled true
                                             :dispatch {:queue-enabled true}}}))))

  (testing "non-map value overwrites"
    (is (= {:a "new"}
           (config/deep-merge {:a "old"} {:a "new"}))))

  (testing "nil values ignored"
    (is (= {:a 1}
           (config/deep-merge {:a 1} nil)))
    (is (= {:a 1}
           (config/deep-merge nil {:a 1}))))

  (testing "three-way merge"
    (is (= {:a 1 :b 2 :c 3}
           (config/deep-merge {:a 1} {:b 2} {:c 3}))))

  (testing "three-way nested merge with overrides"
    (is (= {:server {:port 9999 :host "0.0.0.0"}}
           (config/deep-merge {:server {:port 8080 :host "0.0.0.0"}}
                              {:server {:port 9090}}
                              {:server {:port 9999}})))))

(deftest load-env-overrides-test
  (testing "no env vars set returns empty map"
    (let [result (config/load-env-overrides (constantly nil))]
      (is (= {} result))))

  (testing "single env var"
    (let [env-fn (fn [k] (when (= k "CHENGIS_SERVER_PORT") "9090"))
          result (config/load-env-overrides env-fn)]
      (is (= 9090 (get-in result [:server :port])))
      (is (nil? (get-in result [:auth :enabled])))))

  (testing "multiple env vars"
    (let [env-fn (fn [k]
                   (case k
                     "CHENGIS_SERVER_PORT" "9090"
                     "CHENGIS_AUTH_ENABLED" "true"
                     "CHENGIS_DATABASE_PATH" "/data/chengis.db"
                     "CHENGIS_LOG_LEVEL" ":debug"
                     nil))
          result (config/load-env-overrides env-fn)]
      (is (= 9090 (get-in result [:server :port])))
      (is (true? (get-in result [:auth :enabled])))
      (is (= "/data/chengis.db" (get-in result [:database :path])))
      (is (= :debug (get-in result [:log :level])))))

  (testing "deeply nested env var"
    (let [env-fn (fn [k]
                   (when (= k "CHENGIS_DISTRIBUTED_DISPATCH_QUEUE_ENABLED") "true"))
          result (config/load-env-overrides env-fn)]
      (is (true? (get-in result [:distributed :dispatch :queue-enabled]))))))

(deftest load-config-precedence-test
  (testing "defaults are present without config.edn or env vars"
    ;; load-config always returns at minimum the defaults
    (let [cfg (config/load-config)]
      (is (= 8080 (get-in cfg [:server :port])))
      (is (false? (get-in cfg [:auth :enabled])))
      (is (= "chengis.db" (get-in cfg [:database :path]))))))

(deftest env-key-map-coverage-test
  (testing "all env-key-map entries produce valid config paths"
    ;; Verify that every mapped path produces a non-error assoc-in
    (let [env-fn (fn [_k] "test-value")
          result (config/load-env-overrides env-fn)]
      ;; Should have entries for every key in the map
      (is (= "test-value" (get-in result [:database :path])))
      (is (= "test-value" (get-in result [:server :host])))
      (is (= "test-value" (get-in result [:secrets :master-key])))
      (is (= "test-value" (get-in result [:distributed :auth-token])))
      (is (= "test-value" (get-in result [:scm :github :token])))
      (is (= "test-value" (get-in result [:scm :gitlab :token])))
      (is (= "test-value" (get-in result [:notifications :email :host])))
      (is (= "test-value" (get-in result [:notifications :email :from])))
      (is (= "test-value" (get-in result [:notifications :slack :default-webhook]))))))

(deftest matrix-config-defaults-test
  (testing "matrix max-combinations has default"
    (let [cfg (config/load-config)]
      (is (= 25 (get-in cfg [:matrix :max-combinations]))))))
