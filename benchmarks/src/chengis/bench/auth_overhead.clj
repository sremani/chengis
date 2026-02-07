(ns chengis.bench.auth-overhead
  "Benchmark: Auth & Security Overhead.
   Measures the latency cost of the security middleware stack:
   - bcrypt password hashing/verification
   - JWT sign/verify
   - Role hierarchy check
   - Input validation
   - Full auth middleware chain (session, JWT, API token paths)
   - Audit middleware overhead"
  (:require [chengis.bench.stats :as stats]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.user-store :as user-store]
            [chengis.web.auth :as auth]
            [chengis.web.audit :as audit]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Micro-benchmarks
;; ---------------------------------------------------------------------------

(defn- bench-n
  "Run f n times, return vector of elapsed-ns for each call."
  [f n]
  (mapv (fn [_]
          (let [start (System/nanoTime)
                _ (f)
                end (System/nanoTime)]
            (- end start)))
        (range n)))

(defn- ns->ms [ns-val] (/ (double ns-val) 1e6))

(defn- summarize-ns
  "Summarize a vec of nanosecond timings as milliseconds."
  [ns-vals]
  (stats/summarize (mapv ns->ms ns-vals)))

(defn run-bcrypt-bench
  "Benchmark bcrypt hash + verify cycle."
  [iterations]
  (log/info "  bcrypt hash+verify benchmark (" iterations "iterations)")
  (let [password "benchmark-password-12345"
        ;; Hash benchmark
        hash-times (bench-n #(user-store/hash-password password) iterations)
        ;; Verify benchmark (hash once, verify many times)
        hashed (user-store/hash-password password)
        verify-times (bench-n #(user-store/check-password password hashed) iterations)]
    {:hash   (summarize-ns hash-times)
     :verify (summarize-ns verify-times)}))

(defn run-jwt-bench
  "Benchmark JWT sign + verify cycle."
  [iterations]
  (log/info "  JWT sign+verify benchmark (" iterations "iterations)")
  (let [config {:auth {:jwt-secret "bench-jwt-secret-32-chars-minimum!"
                       :jwt-expiry-hours 24}}
        user {:id "bench-user" :username "alice" :role "admin"}
        ;; Sign benchmark
        sign-times (bench-n #(auth/generate-jwt user config) iterations)
        ;; Verify benchmark
        token (auth/generate-jwt user config)
        verify-times (bench-n #(auth/verify-jwt token config) iterations)]
    {:sign   (summarize-ns sign-times)
     :verify (summarize-ns verify-times)}))

(defn run-role-check-bench
  "Benchmark role hierarchy check."
  [iterations]
  (log/info "  Role check benchmark (" iterations "iterations)")
  (let [times (bench-n #(do (auth/role-sufficient? :admin :viewer)
                            (auth/role-sufficient? :developer :admin)
                            (auth/role-sufficient? :viewer :viewer))
                       iterations)]
    {:role-check (summarize-ns times)}))

(defn run-validation-bench
  "Benchmark input validation functions."
  [iterations]
  (log/info "  Input validation benchmark (" iterations "iterations)")
  (let [username-times (bench-n #(do (auth/valid-username? "alice")
                                     (auth/valid-username? "<script>alert(1)</script>")
                                     (auth/valid-username? nil))
                                iterations)
        password-times (bench-n #(do (auth/valid-password? "longpassword")
                                     (auth/valid-password? "short")
                                     (auth/valid-password? nil))
                                iterations)
        role-val-times (bench-n #(do (auth/valid-role? "admin")
                                     (auth/valid-role? "hacker")
                                     (auth/valid-role? nil))
                                iterations)]
    {:username-validation (summarize-ns username-times)
     :password-validation (summarize-ns password-times)
     :role-validation     (summarize-ns role-val-times)}))

;; ---------------------------------------------------------------------------
;; Middleware integration benchmarks
;; ---------------------------------------------------------------------------

(defn run-middleware-bench
  "Benchmark full auth middleware chain with different auth methods."
  [iterations]
  (log/info "  Middleware chain benchmark (" iterations "iterations)")
  (let [db-path "/tmp/chengis-bench-auth.db"
        _ (let [f (java.io.File. db-path)]
            (when (.exists f) (.delete f)))
        _ (migrate/migrate! db-path)
        ds (conn/create-datasource db-path)

        config-disabled {:auth {:enabled false}}
        config-enabled {:auth {:enabled true
                               :jwt-secret "bench-jwt-secret-32-chars-minimum!"
                               :jwt-expiry-hours 24}}

        ;; Create test user + API token
        test-user (user-store/create-user! ds {:username "benchuser"
                                                :password "benchpass123"
                                                :role "developer"})
        token-result (user-store/create-api-token! ds {:user-id (:id test-user)
                                                        :name "bench-token"})

        ;; Handlers
        ok-handler (fn [req] {:status 200 :body "ok"})

        ;; Wrapped handlers
        disabled-handler (auth/wrap-auth ok-handler {:config config-disabled :db ds})
        enabled-handler (auth/wrap-auth ok-handler {:config config-enabled :db ds})

        ;; JWT token for bearer auth
        jwt-token (auth/generate-jwt test-user config-enabled)

        ;; Request templates
        anon-req {:uri "/" :request-method :get :headers {}}
        session-req {:uri "/" :request-method :get :headers {}
                     :session {:user {:id (:id test-user)
                                      :username "benchuser"
                                      :role :developer}}}
        jwt-req {:uri "/api/jobs" :request-method :get
                 :headers {"authorization" (str "Bearer " jwt-token)}}
        api-token-req {:uri "/api/jobs" :request-method :get
                       :headers {"authorization" (str "Bearer " (:token token-result))}}
        public-req {:uri "/health" :request-method :get :headers {}}

        ;; Run benchmarks
        disabled-times (bench-n #(disabled-handler anon-req) iterations)
        public-times   (bench-n #(enabled-handler public-req) iterations)
        session-times  (bench-n #(enabled-handler session-req) iterations)
        jwt-times      (bench-n #(enabled-handler jwt-req) iterations)
        api-token-times (bench-n #(enabled-handler api-token-req) iterations)]

    ;; Cleanup
    (try
      (.delete (java.io.File. db-path))
      (catch Exception _))

    {:auth-disabled  (summarize-ns disabled-times)
     :public-path    (summarize-ns public-times)
     :session-auth   (summarize-ns session-times)
     :jwt-auth       (summarize-ns jwt-times)
     :api-token-auth (summarize-ns api-token-times)}))

(defn run-audit-bench
  "Benchmark audit middleware overhead (channel write, not DB persist)."
  [iterations]
  (log/info "  Audit middleware benchmark (" iterations "iterations)")
  (let [db-path "/tmp/chengis-bench-audit.db"
        _ (let [f (java.io.File. db-path)]
            (when (.exists f) (.delete f)))
        _ (migrate/migrate! db-path)
        ds (conn/create-datasource db-path)
        config {:audit {:enabled true :buffer-size 4096}}

        ;; Start audit writer
        audit-writer (audit/start-audit-writer! ds config)

        ;; Wrap a handler with audit middleware
        ok-handler (fn [req] {:status 200 :body "ok"})
        audited-handler (audit/wrap-audit ok-handler audit-writer)
        plain-handler ok-handler

        ;; POST request that triggers audit
        post-req {:uri "/jobs/test/trigger"
                  :request-method :post
                  :headers {}
                  :auth/user {:id "u1" :username "bench" :role :developer}}
        ;; GET request (no audit)
        get-req {:uri "/jobs" :request-method :get :headers {}}

        ;; Benchmark
        plain-times    (bench-n #(plain-handler post-req) iterations)
        audited-times  (bench-n #(audited-handler post-req) iterations)
        get-plain      (bench-n #(plain-handler get-req) iterations)
        get-audited    (bench-n #(audited-handler get-req) iterations)]

    ;; Stop audit writer and wait for channel to drain
    ((:stop-fn audit-writer))
    (Thread/sleep 100)

    ;; Cleanup
    (try
      (.delete (java.io.File. db-path))
      (catch Exception _))

    {:post-without-audit (summarize-ns plain-times)
     :post-with-audit    (summarize-ns audited-times)
     :get-without-audit  (summarize-ns get-plain)
     :get-with-audit     (summarize-ns get-audited)}))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-auth-overhead-benchmark
  "Run the complete auth overhead benchmark suite.
   Returns a map of all benchmark results."
  [config]
  (let [{:keys [iterations warm-up]} (get config :auth-overhead
                                           {:iterations 500 :warm-up 50})]
    (log/info "=== Auth overhead benchmark ===")
    (log/info (str "  Iterations: " iterations ", warm-up: " warm-up))

    ;; Warm-up
    (log/info "  Warming up...")
    (let [_ (run-bcrypt-bench warm-up)]

      {:bcrypt     (run-bcrypt-bench iterations)
       :jwt        (run-jwt-bench iterations)
       :role-check (run-role-check-bench iterations)
       :validation (run-validation-bench iterations)
       :middleware (run-middleware-bench iterations)
       :audit      (run-audit-bench iterations)})))
