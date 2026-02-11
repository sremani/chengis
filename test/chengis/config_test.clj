(ns chengis.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
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

;; ---------------------------------------------------------------------------
;; Phase 1 mutation testing remediation: Assert every boolean default value
;; in default-config to kill true↔false mutation survivors.
;; ---------------------------------------------------------------------------

(deftest default-config-boolean-defaults-test
  (let [cfg config/default-config]

    (testing "top-level service toggles default to disabled"
      (is (false? (get-in cfg [:scheduler :enabled])))
      (is (false? (get-in cfg [:auth :enabled])))
      (is (false? (get-in cfg [:https :enabled])))
      (is (false? (get-in cfg [:metrics :enabled])))
      (is (false? (get-in cfg [:rate-limit :enabled])))
      (is (false? (get-in cfg [:retention :enabled])))
      (is (false? (get-in cfg [:cleanup :enabled])))
      (is (false? (get-in cfg [:oidc :enabled])))
      (is (false? (get-in cfg [:saml :enabled])))
      (is (false? (get-in cfg [:ldap :enabled])))
      (is (false? (get-in cfg [:ha :enabled])))
      (is (false? (get-in cfg [:distributed :enabled])))
      (is (false? (get-in cfg [:security :cors :enabled])))
      (is (false? (get-in cfg [:metrics :auth-required]))))

    (testing "service toggles that default to enabled"
      (is (true? (get-in cfg [:notifications :email :tls])))
      (is (true? (get-in cfg [:audit :enabled])))
      (is (true? (get-in cfg [:security :csp :enabled])))
      (is (true? (get-in cfg [:https :hsts])))
      (is (true? (get-in cfg [:https :redirect-http])))
      (is (true? (get-in cfg [:auth :lockout :enabled])))
      (is (true? (get-in cfg [:approvals :enabled])))
      (is (true? (get-in cfg [:templates :enabled])))
      (is (true? (get-in cfg [:oidc :auto-create-users])))
      (is (true? (get-in cfg [:saml :auto-create-users])))
      (is (true? (get-in cfg [:ldap :auto-create-users])))
      (is (true? (get-in cfg [:multi-tenancy :auto-assign-default])))
      (is (true? (get-in cfg [:auto-merge :require-all-checks])))
      (is (true? (get-in cfg [:container-scanning :ignore-unfixed])))
      (is (true? (get-in cfg [:distributed :dispatch :artifact-transfer])))
      (is (true? (get-in cfg [:iac :terraform :auto-init]))))

    (testing "secrets defaults"
      (is (false? (get-in cfg [:secrets :fallback-to-local]))))

    (testing "distributed dispatch defaults"
      (is (false? (get-in cfg [:distributed :dispatch :fallback-local])))
      (is (false? (get-in cfg [:distributed :dispatch :queue-enabled]))))

    (testing "LDAP defaults"
      (is (false? (get-in cfg [:ldap :use-ssl]))))

    (testing "MFA defaults"
      (is (false? (get-in cfg [:mfa :enforce-for-admins]))))

    (testing "auto-merge defaults"
      (is (false? (get-in cfg [:auto-merge :delete-branch-after]))))

    (testing "deployment defaults"
      (is (false? (get-in cfg [:deployment :rollback :auto-on-health-failure]))))

    (testing "IaC plan defaults"
      (is (false? (get-in cfg [:iac :plan :require-approval]))))))

(deftest default-config-feature-flags-test
  (let [flags (get-in config/default-config [:feature-flags])]

    (testing "persistent-agents is the only flag that defaults to true"
      (is (true? (:persistent-agents flags))))

    (testing "all other feature flags default to false"
      (let [expected-false-flags
            [:policy-engine :artifact-checksums :compliance-reports
             :distributed-dispatch :parallel-stage-execution
             :docker-layer-cache :artifact-cache :build-result-cache
             :resource-aware-scheduling :incremental-artifacts
             :build-deduplication
             ;; Phase 5: Observability
             :tracing :build-analytics :browser-notifications
             :cost-attribution :flaky-test-detection
             ;; Phase 6: Advanced SCM
             :pr-status-checks :branch-overrides :monorepo-filtering
             :build-dependencies :cron-scheduling :webhook-replay :auto-merge
             ;; Phase 7: Supply Chain
             :slsa-provenance :sbom-generation :container-scanning
             :opa-policies :license-scanning :artifact-signing
             :regulatory-dashboards
             ;; Phase 8: Enterprise Identity
             :saml :ldap :fine-grained-rbac :mfa-totp
             :cross-org-sharing :cloud-secret-backends :secret-rotation
             ;; Phase 10: Scale
             :chunked-log-storage :cursor-pagination :db-partitioning
             :read-replicas :agent-connection-pooling
             :event-bus-backpressure :multi-region
             ;; Phase 11: Deployment
             :environment-definitions :release-management
             :artifact-promotion :deployment-strategies
             :deployment-execution :environment-health-checks
             :deployment-dashboard
             ;; Phase 12: IaC
             :infrastructure-as-code :terraform-execution
             :pulumi-execution :cloudformation-execution
             :iac-state-management :iac-cost-estimation
             :iac-policy-enforcement]]
        (doseq [flag expected-false-flags]
          (is (false? (get flags flag))
              (str "Feature flag " flag " should default to false")))))))

(deftest default-config-warn-insecure-defaults-test
  (testing "warn-insecure-defaults detects default admin password"
    (let [cfg (assoc-in config/default-config [:auth :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "default admin password"))))

  (testing "warn-insecure-defaults detects missing JWT secret"
    (let [cfg (assoc-in config/default-config [:auth :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "JWT secret"))))

  (testing "warn-insecure-defaults detects missing distributed auth token"
    (let [cfg (assoc-in config/default-config [:distributed :enabled] true)
          output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults cfg)))]
      (is (str/includes? output "auth-token"))))

  (testing "no warnings when auth is disabled"
    (let [output (with-out-str
                   (binding [*err* *out*]
                     (config/warn-insecure-defaults config/default-config)))]
      (is (= "" output))))

  (testing "warn-insecure-defaults returns the config"
    (is (= config/default-config
           (config/warn-insecure-defaults config/default-config)))))

(deftest sqlite-postgresql-detection-test
  (testing "sqlite? returns true for default config"
    (is (true? (config/sqlite? config/default-config))))

  (testing "postgresql? returns false for default config"
    (is (false? (config/postgresql? config/default-config))))

  (testing "sqlite? returns false for postgresql config"
    (let [cfg (assoc-in config/default-config [:database :type] "postgresql")]
      (is (false? (config/sqlite? cfg)))))

  (testing "postgresql? returns true for postgresql config"
    (let [cfg (assoc-in config/default-config [:database :type] "postgresql")]
      (is (true? (config/postgresql? cfg)))))

  (testing "sqlite? and postgresql? are mutually exclusive"
    (is (not= (config/sqlite? config/default-config)
              (config/postgresql? config/default-config)))))
