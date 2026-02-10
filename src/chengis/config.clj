(ns chengis.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-config
  {:database {:type "sqlite"    ;; "sqlite" or "postgresql"
              :path "chengis.db"  ;; SQLite file path (used when type=sqlite)
              ;; PostgreSQL connection (used when type=postgresql)
              :host "localhost"
              :port 5432
              :dbname "chengis"
              :user "chengis"
              :password nil
              ;; HikariCP pool settings (PostgreSQL only)
              :pool {:minimum-idle 2
                     :maximum-pool-size 10}}
   :workspace {:root "workspaces"}
   :scheduler {:enabled false}
   :server {:port 8080 :host "0.0.0.0"}
   :secrets {:master-key nil
             :backend "local"      ;; "local" (AES-256-GCM in DB) or "vault" (HashiCorp Vault)
             :fallback-to-local false  ;; When true, Vault errors fall back to local store (NOT recommended for production)
             :vault {:url nil      ;; e.g., "http://127.0.0.1:8200" or VAULT_ADDR env
                     :token nil    ;; Vault token or VAULT_TOKEN env
                     :mount "secret"
                     :prefix "chengis/"}}
   :artifacts {:root "artifacts" :retention-builds 10
               :max-size-bytes (* 500 1024 1024)}  ;; 500MB per artifact
   :notifications {:slack {:default-webhook nil}
                   :email {:host nil
                           :port 587
                           :tls true
                           :username nil
                           :password nil
                           :from "chengis@localhost"
                           :default-recipients []}}
   :cleanup {:enabled false :interval-hours 24 :retention-builds 10}
   :plugins {:directory "plugins" :enabled []}
   :docker {:host "unix:///var/run/docker.sock"
            :default-timeout 600000
            :pull-policy :if-not-present}
   :distributed {:enabled false
                 :mode :master
                 :auth-token nil
                 :heartbeat-timeout-ms 90000
                 :agent {:port 9090
                         :labels #{}
                         :max-builds 2}
                 :dispatch {:fallback-local false
                            :queue-enabled false
                            :max-retries 3
                            :retry-backoff-ms 1000
                            :circuit-breaker-threshold 5
                            :circuit-breaker-reset-ms 60000
                            :orphan-check-interval-ms 120000
                            :artifact-transfer true}}
   :auth {:enabled false
          :session-secret nil
          :jwt-secret nil
          :jwt-expiry-hours 24
          :seed-admin-password "admin"
          :session-max-age 86400
          :lockout {:enabled true
                    :max-attempts 5
                    :lockout-minutes 30}}
   :https {:enabled false
           :port 8443
           :keystore nil
           :keystore-password nil
           :hsts true
           :redirect-http true}
   :audit {:enabled true
           :retention-days 90
           :buffer-size 1024}
   :metrics {:enabled false
             :path "/metrics"
             :auth-required false}
   :rate-limit {:enabled false
                :requests-per-minute 60
                :auth-requests-per-minute 10
                :webhook-requests-per-minute 120}
   :security {:cors {:enabled false
                     :allowed-origins ["*"]
                     :allowed-methods ["GET" "POST" "PUT" "DELETE"]
                     :max-age 3600}
              ;; CSP: 'unsafe-inline' is required for Tailwind CDN (generates
              ;; inline styles at runtime) and htmx (inline event handlers).
              ;; To remove 'unsafe-inline', self-host a compiled Tailwind build
              ;; and use htmx nonce-based CSP support.
              :csp {:enabled true
                    :directives {:default-src "'self'"
                                 :script-src "'self' 'unsafe-inline' https://cdn.tailwindcss.com https://unpkg.com"
                                 :style-src "'self' 'unsafe-inline'"
                                 :img-src "'self' data:"
                                 :connect-src "'self'"}}}
   :retention {:enabled false
               :interval-hours 24
               :builds-days 90
               :build-logs-days 30
               :audit-days 90
               :webhook-events-days 30
               :queue-completed-hours 168}
   :scm {:github {:token nil
                  :context "chengis/build"
                  :base-url "https://api.github.com"}
         :gitlab {:token nil
                  :base-url "https://gitlab.com"}}
   :oidc {:enabled false
          :issuer-url nil         ;; e.g., "https://keycloak.example.com/realms/chengis"
          :client-id nil
          :client-secret nil
          :callback-url nil       ;; Explicit callback URL (recommended behind proxy); nil = auto-detect from headers
          :scopes "openid profile email"
          :role-claim nil         ;; e.g., "realm_access.roles" (dot-separated path)
          :role-mapping {}        ;; e.g., {"chengis-admin" "admin", "chengis-dev" "developer"}
          :default-role "viewer"
          :auto-create-users true ;; JIT provision users on first OIDC login
          :provider-name nil}     ;; Display name, e.g., "Okta" (auto-detected if nil)
   :multi-tenancy {:default-org-slug "default"  ;; Slug of the auto-created default org
                   :auto-assign-default true}   ;; Auto-assign new users to default org
   :approvals {:enabled true
               :default-timeout-minutes 1440
               :poll-interval-ms 5000}
   :templates {:enabled true
               :max-depth 3}
   :matrix {:max-combinations 25}
   :parallel-stages {:max-concurrent 4}
   :cache {:root "cache"
           :max-size-gb 10
           :retention-days 30}
   :tracing {:sample-rate 1.0
             :retention-days 7}
   :analytics {:aggregation-interval-hours 6
               :retention-days 365}
   :cost-attribution {:default-cost-per-hour 1.0}
   :flaky-detection {:flakiness-threshold 0.15
                     :min-runs 5
                     :lookback-builds 30}
   :deduplication {:window-minutes 10}
   :ha {:enabled false
        :leader-poll-ms 15000
        :instance-id nil}      ;; defaults to "standalone" if nil; set from K8s pod name via CHENGIS_HA_INSTANCE_ID
   :feature-flags {:policy-engine false
                   :artifact-checksums false
                   :compliance-reports false
                   :distributed-dispatch false
                   :persistent-agents true
                   :parallel-stage-execution false
                   :docker-layer-cache false
                   :artifact-cache false
                   :build-result-cache false
                   :resource-aware-scheduling false
                   :incremental-artifacts false
                   :build-deduplication false
                   ;; Phase 5: Observability & Analytics
                   :tracing false
                   :build-analytics false
                   :browser-notifications false
                   :cost-attribution false
                   :flaky-test-detection false}
   :policies {:evaluation-timeout-ms 5000}
   :log {:level :info
         :format :text
         :file nil}})

;; ---------------------------------------------------------------------------
;; Environment variable configuration
;; ---------------------------------------------------------------------------

(def ^:private env-key-map
  "Explicit mapping of environment variable names to config paths.
   Only variables listed here are recognized — no automatic wildcard scanning."
  {"CHENGIS_DATABASE_TYPE"                       [:database :type]
   "CHENGIS_DATABASE_PATH"                      [:database :path]
   "CHENGIS_DATABASE_HOST"                      [:database :host]
   "CHENGIS_DATABASE_PORT"                      [:database :port]
   "CHENGIS_DATABASE_NAME"                      [:database :dbname]
   "CHENGIS_DATABASE_USER"                      [:database :user]
   "CHENGIS_DATABASE_PASSWORD"                  [:database :password]
   "CHENGIS_WORKSPACE_ROOT"                     [:workspace :root]
   "CHENGIS_ARTIFACTS_ROOT"                     [:artifacts :root]
   "CHENGIS_SERVER_PORT"                        [:server :port]
   "CHENGIS_SERVER_HOST"                        [:server :host]
   "CHENGIS_AUTH_ENABLED"                       [:auth :enabled]
   "CHENGIS_AUTH_JWT_SECRET"                    [:auth :jwt-secret]
   "CHENGIS_AUTH_SESSION_SECRET"                [:auth :session-secret]
   "CHENGIS_AUTH_SEED_ADMIN_PASSWORD"           [:auth :seed-admin-password]
   "CHENGIS_SECRETS_MASTER_KEY"                 [:secrets :master-key]
   "CHENGIS_SECRETS_BACKEND"                    [:secrets :backend]
   "CHENGIS_SECRETS_VAULT_URL"                  [:secrets :vault :url]
   "CHENGIS_SECRETS_VAULT_TOKEN"                [:secrets :vault :token]
   "CHENGIS_SECRETS_VAULT_MOUNT"                [:secrets :vault :mount]
   "CHENGIS_SECRETS_VAULT_PREFIX"               [:secrets :vault :prefix]
   "CHENGIS_DISTRIBUTED_ENABLED"                [:distributed :enabled]
   "CHENGIS_DISTRIBUTED_MODE"                   [:distributed :mode]
   "CHENGIS_DISTRIBUTED_AUTH_TOKEN"             [:distributed :auth-token]
   "CHENGIS_DISTRIBUTED_DISPATCH_QUEUE_ENABLED" [:distributed :dispatch :queue-enabled]
   "CHENGIS_DISTRIBUTED_HEARTBEAT_TIMEOUT_MS"  [:distributed :heartbeat-timeout-ms]
   "CHENGIS_DISTRIBUTED_FALLBACK_LOCAL"        [:distributed :dispatch :fallback-local]
   "CHENGIS_FEATURE_DISTRIBUTED_DISPATCH"      [:feature-flags :distributed-dispatch]
   "CHENGIS_FEATURE_PERSISTENT_AGENTS"         [:feature-flags :persistent-agents]
   "CHENGIS_HA_ENABLED"                        [:ha :enabled]
   "CHENGIS_HA_LEADER_POLL_MS"                 [:ha :leader-poll-ms]
   "CHENGIS_HA_INSTANCE_ID"                    [:ha :instance-id]
   "CHENGIS_METRICS_ENABLED"                    [:metrics :enabled]
   "CHENGIS_LOG_LEVEL"                          [:log :level]
   "CHENGIS_LOG_FORMAT"                         [:log :format]
   "CHENGIS_RATE_LIMIT_ENABLED"                 [:rate-limit :enabled]
   "CHENGIS_HTTPS_ENABLED"                      [:https :enabled]
   "CHENGIS_RETENTION_ENABLED"                  [:retention :enabled]
   "CHENGIS_SCM_GITHUB_TOKEN"                   [:scm :github :token]
   "CHENGIS_SCM_GITLAB_TOKEN"                   [:scm :gitlab :token]
   "CHENGIS_NOTIFICATIONS_EMAIL_HOST"           [:notifications :email :host]
   "CHENGIS_NOTIFICATIONS_EMAIL_PORT"           [:notifications :email :port]
   "CHENGIS_NOTIFICATIONS_EMAIL_FROM"           [:notifications :email :from]
   "CHENGIS_NOTIFICATIONS_SLACK_DEFAULT_WEBHOOK" [:notifications :slack :default-webhook]
   "CHENGIS_MATRIX_MAX_COMBINATIONS"            [:matrix :max-combinations]
   "CHENGIS_OIDC_ENABLED"                       [:oidc :enabled]
   "CHENGIS_OIDC_ISSUER_URL"                    [:oidc :issuer-url]
   "CHENGIS_OIDC_CLIENT_ID"                     [:oidc :client-id]
   "CHENGIS_OIDC_CLIENT_SECRET"                 [:oidc :client-secret]
   "CHENGIS_OIDC_CALLBACK_URL"                  [:oidc :callback-url]
   "CHENGIS_OIDC_SCOPES"                        [:oidc :scopes]
   "CHENGIS_OIDC_ROLE_CLAIM"                    [:oidc :role-claim]
   "CHENGIS_OIDC_DEFAULT_ROLE"                  [:oidc :default-role]
   "CHENGIS_OIDC_AUTO_CREATE_USERS"             [:oidc :auto-create-users]
   "CHENGIS_OIDC_PROVIDER_NAME"                 [:oidc :provider-name]
   "CHENGIS_SECRETS_FALLBACK_TO_LOCAL"           [:secrets :fallback-to-local]
   "CHENGIS_FEATURE_POLICY_ENGINE"              [:feature-flags :policy-engine]
   "CHENGIS_FEATURE_ARTIFACT_CHECKSUMS"         [:feature-flags :artifact-checksums]
   "CHENGIS_FEATURE_COMPLIANCE_REPORTS"         [:feature-flags :compliance-reports]
   "CHENGIS_POLICIES_EVALUATION_TIMEOUT_MS"     [:policies :evaluation-timeout-ms]
   ;; Phase 4: Build Performance & Caching
   "CHENGIS_FEATURE_PARALLEL_STAGES"            [:feature-flags :parallel-stage-execution]
   "CHENGIS_PARALLEL_STAGES_MAX"                [:parallel-stages :max-concurrent]
   "CHENGIS_FEATURE_DOCKER_LAYER_CACHE"         [:feature-flags :docker-layer-cache]
   "CHENGIS_FEATURE_ARTIFACT_CACHE"             [:feature-flags :artifact-cache]
   "CHENGIS_CACHE_ROOT"                         [:cache :root]
   "CHENGIS_CACHE_MAX_SIZE_GB"                  [:cache :max-size-gb]
   "CHENGIS_CACHE_RETENTION_DAYS"               [:cache :retention-days]
   "CHENGIS_FEATURE_BUILD_RESULT_CACHE"         [:feature-flags :build-result-cache]
   "CHENGIS_FEATURE_RESOURCE_SCHEDULING"        [:feature-flags :resource-aware-scheduling]
   "CHENGIS_FEATURE_INCREMENTAL_ARTIFACTS"      [:feature-flags :incremental-artifacts]
   "CHENGIS_FEATURE_BUILD_DEDUP"                [:feature-flags :build-deduplication]
   "CHENGIS_DEDUP_WINDOW_MINUTES"               [:deduplication :window-minutes]
   ;; Phase 5: Observability & Analytics
   "CHENGIS_FEATURE_TRACING"                    [:feature-flags :tracing]
   "CHENGIS_TRACING_SAMPLE_RATE"                [:tracing :sample-rate]
   "CHENGIS_TRACING_RETENTION_DAYS"             [:tracing :retention-days]
   "CHENGIS_FEATURE_BUILD_ANALYTICS"            [:feature-flags :build-analytics]
   "CHENGIS_ANALYTICS_INTERVAL_HOURS"           [:analytics :aggregation-interval-hours]
   "CHENGIS_ANALYTICS_RETENTION_DAYS"           [:analytics :retention-days]
   "CHENGIS_FEATURE_BROWSER_NOTIFICATIONS"      [:feature-flags :browser-notifications]
   "CHENGIS_FEATURE_COST_ATTRIBUTION"           [:feature-flags :cost-attribution]
   "CHENGIS_COST_PER_HOUR"                      [:cost-attribution :default-cost-per-hour]
   "CHENGIS_FEATURE_FLAKY_TEST_DETECTION"       [:feature-flags :flaky-test-detection]
   "CHENGIS_FLAKY_THRESHOLD"                    [:flaky-detection :flakiness-threshold]
   "CHENGIS_FLAKY_MIN_RUNS"                     [:flaky-detection :min-runs]
   "CHENGIS_FLAKY_LOOKBACK_BUILDS"              [:flaky-detection :lookback-builds]})

(defn coerce-env-value
  "Coerce a string environment variable value to the appropriate type.
   Rules: \"true\"/\"false\" → boolean, pure digits → long,
   colon-prefixed → keyword, else string."
  [v]
  (cond
    (= v "true")  true
    (= v "false") false
    (re-matches #"\d+" v) (Long/parseLong v)
    (str/starts-with? v ":") (keyword (subs v 1))
    :else v))

(defn load-env-overrides
  "Read CHENGIS_* environment variables and return a partial config map.
   Only variables in env-key-map are recognized. Values are type-coerced."
  ([]
   (load-env-overrides (fn [k] (System/getenv k))))
  ([env-fn]
   (reduce-kv (fn [m env-key config-path]
                (if-let [v (env-fn env-key)]
                  (assoc-in m config-path (coerce-env-value v))
                  m))
              {} env-key-map)))

(defn deep-merge
  "Recursively merge maps. When both values are maps, merge them.
   Otherwise the later value wins. nil values are ignored."
  [& maps]
  (reduce (fn [result m]
            (if (nil? m)
              result
              (reduce-kv (fn [acc k v]
                           (let [existing (get acc k)]
                             (if (and (map? existing) (map? v))
                               (assoc acc k (deep-merge existing v))
                               (assoc acc k v))))
                         result m)))
          {} maps))

(defn load-config
  "Load configuration from config.edn on the classpath, merged with defaults,
   then overlaid with CHENGIS_* environment variables.
   Precedence: env vars > config.edn > defaults."
  ([]
   (let [file-config (when-let [resource (io/resource "config.edn")]
                       (edn/read-string {:readers {}} (slurp resource)))
         env-config (load-env-overrides)]
     (deep-merge default-config file-config env-config)))
  ([path]
   (let [file-config (edn/read-string {:readers {}} (slurp path))
         env-config (load-env-overrides)]
     (deep-merge default-config file-config env-config))))

(defn resolve-path
  "Resolve a potentially relative path against a base directory."
  [base path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      (.getAbsolutePath f)
      (.getAbsolutePath (io/file base path)))))

(defn warn-insecure-defaults
  "Check a loaded config for insecure default values and print warnings.
   Called at server startup to alert operators of production risks."
  [cfg]
  (when (get-in cfg [:auth :enabled])
    (when (= "admin" (get-in cfg [:auth :seed-admin-password]))
      (binding [*out* *err*]
        (println "[SECURITY WARNING] Using default admin password 'admin'. Change :auth :seed-admin-password or set CHENGIS_AUTH_SEED_ADMIN_PASSWORD before production use!")))
    (when (str/blank? (str (get-in cfg [:auth :jwt-secret])))
      (binding [*out* *err*]
        (println "[SECURITY WARNING] No JWT secret configured. Set :auth :jwt-secret or CHENGIS_AUTH_JWT_SECRET for production!"))))
  (when (and (get-in cfg [:distributed :enabled])
             (str/blank? (str (get-in cfg [:distributed :auth-token]))))
    (binding [*out* *err*]
      (println "[SECURITY WARNING] Distributed mode enabled but :distributed :auth-token is not set. Agent communication is unauthenticated!")))
  cfg)

(defn sqlite?
  "Returns true if the database config specifies SQLite (the default)."
  [cfg]
  (not= "postgresql" (get-in cfg [:database :type])))

(defn postgresql?
  "Returns true if the database config specifies PostgreSQL."
  [cfg]
  (= "postgresql" (get-in cfg [:database :type])))
