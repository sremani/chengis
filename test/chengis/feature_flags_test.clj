(ns chengis.feature-flags-test
  (:require [clojure.test :refer [deftest is testing]]
            [chengis.feature-flags :as ff]))

(deftest enabled?-test
  (testing "returns true when flag is true"
    (is (true? (ff/enabled? {:feature-flags {:policy-engine true}} :policy-engine))))

  (testing "returns false when flag is false"
    (is (false? (ff/enabled? {:feature-flags {:policy-engine false}} :policy-engine))))

  (testing "returns false when flag is missing"
    (is (false? (ff/enabled? {:feature-flags {}} :policy-engine))))

  (testing "returns false when feature-flags section is missing"
    (is (false? (ff/enabled? {} :policy-engine)))))

(deftest all-flags-test
  (testing "returns the full feature-flags map"
    (let [flags {:policy-engine true :artifact-checksums false}]
      (is (= flags (ff/all-flags {:feature-flags flags})))))

  (testing "returns empty map when missing"
    (is (= {} (ff/all-flags {})))))

(deftest require-flag!-test
  (testing "returns true when flag is enabled"
    (is (true? (ff/require-flag! {:feature-flags {:policy-engine true}} :policy-engine))))

  (testing "throws when flag is disabled"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Feature not enabled: policy-engine"
          (ff/require-flag! {:feature-flags {:policy-engine false}} :policy-engine))))

  (testing "throws when flag is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
          #"Feature not enabled: policy-engine"
          (ff/require-flag! {} :policy-engine))))

  (testing "exception data includes type and flag"
    (try
      (ff/require-flag! {} :compliance-reports)
      (catch clojure.lang.ExceptionInfo e
        (is (= {:type :feature-disabled :flag :compliance-reports}
               (ex-data e)))))))

(deftest config-coercion-test
  (testing "string 'true' is truthy via boolean coercion in config"
    ;; After config coercion, env vars become booleans.
    ;; This test validates that enabled? works with actual booleans.
    (is (true? (ff/enabled? {:feature-flags {:artifact-checksums true}} :artifact-checksums)))
    (is (false? (ff/enabled? {:feature-flags {:artifact-checksums false}} :artifact-checksums)))))
