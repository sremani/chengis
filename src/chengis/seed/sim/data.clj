(ns chengis.seed.sim.data
  "Static data definitions for simulation seeder: jobs, users, commit messages, log templates.")

;; ---------------------------------------------------------------------------
;; Team members
;; ---------------------------------------------------------------------------

(def users
  [{:username "admin"   :password "admin123!"    :role "admin"}
   {:username "jchen"   :password "jchen456!"    :role "admin"}
   {:username "sarah.k" :password "sarahK789!"   :role "developer"}
   {:username "mike.r"  :password "mikeR101!"    :role "developer"}
   {:username "priya.s" :password "priyaS202!"   :role "developer"}
   {:username "alex.m"  :password "alexM303!"    :role "developer"}
   {:username "dani.l"  :password "daniL404!"    :role "viewer"}
   {:username "bot-ci"  :password "botCI505!"    :role "developer"}])

(def developer-usernames
  ["sarah.k" "mike.r" "priya.s" "alex.m" "bot-ci"])

(def admin-usernames
  ["admin" "jchen"])

;; ---------------------------------------------------------------------------
;; Job definitions — pipelines with stages
;; ---------------------------------------------------------------------------

(def jobs
  [{:name             "platform-api"
    :builds-per-day   4.0
    :success-rate     0.78
    :stages           ["Checkout" "Build" "Test" "Security Scan" "Deploy Staging" "Deploy Prod"]
    :fail-stage-weights {"Test" 50 "Build" 25 "Security Scan" 15 "Deploy Staging" 10}
    :language         "Java/Spring/Maven"
    :triggers         {:webhook {:provider "github" :events ["push" "pull_request"]}
                       :schedule {:cron "0 2 * * *"}}}

   {:name             "web-frontend"
    :builds-per-day   5.0
    :success-rate     0.82
    :stages           ["Checkout" "Install" "Lint" "Test" "Build" "Deploy Staging" "Deploy Prod"]
    :fail-stage-weights {"Test" 40 "Lint" 20 "Build" 20 "Install" 10 "Deploy Staging" 10}
    :language         "React/Node/npm"
    :triggers         {:webhook {:provider "github" :events ["push" "pull_request"]}}}

   {:name             "payment-service"
    :builds-per-day   2.5
    :success-rate     0.88
    :stages           ["Checkout" "Build" "Test" "Lint" "Docker Build" "Deploy"]
    :fail-stage-weights {"Test" 45 "Build" 25 "Docker Build" 20 "Lint" 10}
    :language         "Go"
    :triggers         {:webhook {:provider "github" :events ["push"]}
                       :schedule {:cron "0 2 * * *"}}}

   {:name             "data-pipeline"
    :builds-per-day   1.5
    :success-rate     0.75
    :stages           ["Checkout" "Install" "Lint" "Test" "Deploy"]
    :fail-stage-weights {"Test" 50 "Install" 20 "Lint" 15 "Deploy" 15}
    :language         "Python/Airflow"
    :triggers         {:webhook {:provider "github" :events ["push"]}}}

   {:name             "ml-model-server"
    :builds-per-day   1.0
    :success-rate     0.72
    :stages           ["Checkout" "Install" "Train Validation" "Test" "Docker Build" "Deploy"]
    :fail-stage-weights {"Train Validation" 35 "Test" 30 "Docker Build" 20 "Install" 15}
    :language         "Python/FastAPI"
    :triggers         {:webhook {:provider "github" :events ["push"]}}}

   {:name             "ios-app"
    :builds-per-day   1.5
    :success-rate     0.70
    :stages           ["Checkout" "Install Deps" "Build" "Test" "Archive" "Upload TestFlight"]
    :fail-stage-weights {"Build" 35 "Test" 35 "Archive" 15 "Install Deps" 10 "Upload TestFlight" 5}
    :language         "Swift/Xcode"
    :triggers         {:webhook {:provider "github" :events ["push" "pull_request"]}}}

   {:name             "infra-terraform"
    :builds-per-day   0.8
    :success-rate     0.85
    :stages           ["Checkout" "Init" "Validate" "Plan" "Apply"]
    :fail-stage-weights {"Plan" 40 "Validate" 30 "Apply" 20 "Init" 10}
    :language         "Terraform/HCL"
    :triggers         {:webhook {:provider "github" :events ["push"]}
                       :schedule {:cron "0 2 * * *"}}}

   {:name             "docs-site"
    :builds-per-day   0.5
    :success-rate     0.92
    :stages           ["Checkout" "Install" "Build" "Deploy"]
    :fail-stage-weights {"Build" 50 "Install" 30 "Deploy" 20}
    :language         "Ruby/Jekyll"
    :triggers         {:webhook {:provider "github" :events ["push"]}}}])

;; ---------------------------------------------------------------------------
;; Git metadata
;; ---------------------------------------------------------------------------

(def branch-weights
  "Weighted branch distribution for random selection."
  [["main"                  30]
   ["develop"               15]
   ["feature/auth-refactor"  8]
   ["feature/new-dashboard"  7]
   ["feature/api-v2"         5]
   ["feature/notifications"  5]
   ["fix/login-timeout"      6]
   ["fix/memory-leak"        5]
   ["fix/null-pointer"       4]
   ["fix/css-overflow"       4]
   ["chore/deps-update"      4]
   ["chore/ci-config"        3]
   ["release/v2.1"           2]
   ["release/v2.0"           2]])

(def commit-prefixes
  "Conventional commit prefixes with weights."
  [["feat"     30]
   ["fix"      25]
   ["refactor" 12]
   ["test"     10]
   ["docs"      8]
   ["chore"     8]
   ["perf"      4]
   ["ci"        3]])

(def commit-subjects
  "Subject lines by prefix type."
  {"feat"     ["add user profile endpoint"
               "implement search autocomplete"
               "add dark mode support"
               "implement webhook retry logic"
               "add batch processing endpoint"
               "implement rate limiting"
               "add CSV export functionality"
               "implement SSO integration"
               "add real-time notifications"
               "implement file upload service"]
   "fix"      ["resolve null pointer in auth flow"
               "fix memory leak in connection pool"
               "correct timezone handling in scheduler"
               "fix race condition in queue consumer"
               "resolve CSS overflow on mobile"
               "fix pagination off-by-one error"
               "correct CORS headers for preflight"
               "fix session timeout not clearing"
               "resolve deadlock in batch processor"
               "fix incorrect error status code"]
   "refactor" ["extract common validation logic"
               "simplify database query builder"
               "reorganize middleware chain"
               "decouple notification service"
               "streamline error handling"
               "consolidate duplicate test helpers"
               "modularize configuration loading"
               "improve type definitions"]
   "test"     ["add integration tests for auth"
               "improve coverage for payment flow"
               "add load testing scenarios"
               "fix flaky timezone test"
               "add edge case tests for parser"
               "improve mock setup for external APIs"
               "add contract tests for API v2"]
   "docs"     ["update API reference"
               "add deployment guide"
               "improve README quickstart"
               "document retry configuration"
               "add architecture decision record"
               "update changelog for v2.1"]
   "chore"    ["update dependencies"
               "bump node version to 20"
               "clean up unused imports"
               "update CI configuration"
               "remove deprecated endpoints"
               "upgrade database driver"]
   "perf"     ["optimize database queries"
               "add response caching"
               "reduce bundle size"
               "improve startup time"]
   "ci"       ["update build matrix"
               "add security scanning step"
               "fix deployment script"]})

;; ---------------------------------------------------------------------------
;; Trigger types
;; ---------------------------------------------------------------------------

(def trigger-weights
  "Build trigger distribution."
  [["webhook"   70]
   ["manual"    15]
   ["schedule"  10]
   ["retry"      5]])

;; ---------------------------------------------------------------------------
;; Build log templates
;; ---------------------------------------------------------------------------

(def stage-log-templates
  "Log messages per stage type."
  {"Checkout"         [{:level "info"  :msg "Cloning repository..."}
                       {:level "info"  :msg "Checked out commit %s on branch %s"}]
   "Build"            [{:level "info"  :msg "Starting build..."}
                       {:level "info"  :msg "Compiling source files..."}
                       {:level "info"  :msg "Build completed successfully"}]
   "Test"             [{:level "info"  :msg "Running test suite..."}
                       {:level "info"  :msg "Executing %d tests across %d suites"}
                       {:level "info"  :msg "All tests passed"}]
   "Install"          [{:level "info"  :msg "Installing dependencies..."}
                       {:level "info"  :msg "Resolved %d packages"}
                       {:level "info"  :msg "Dependencies installed"}]
   "Install Deps"     [{:level "info"  :msg "Resolving dependencies with CocoaPods..."}
                       {:level "info"  :msg "Installing %d pods"}]
   "Lint"             [{:level "info"  :msg "Running linter..."}
                       {:level "info"  :msg "Checked %d files, 0 errors"}]
   "Security Scan"    [{:level "info"  :msg "Running security scan..."}
                       {:level "info"  :msg "Scanned %d dependencies, no vulnerabilities found"}]
   "Docker Build"     [{:level "info"  :msg "Building Docker image..."}
                       {:level "info"  :msg "Image built: %s:%s"}]
   "Deploy Staging"   [{:level "info"  :msg "Deploying to staging environment..."}
                       {:level "info"  :msg "Deployment complete, running smoke tests"}
                       {:level "info"  :msg "Staging deploy verified"}]
   "Deploy Prod"      [{:level "info"  :msg "Deploying to production..."}
                       {:level "info"  :msg "Rolling deployment started, 0/3 pods updated"}
                       {:level "info"  :msg "Production deploy complete"}]
   "Deploy"           [{:level "info"  :msg "Deploying to target environment..."}
                       {:level "info"  :msg "Deployment complete"}]
   "Train Validation" [{:level "info"  :msg "Loading validation dataset..."}
                       {:level "info"  :msg "Running model validation, epoch 1/%d"}
                       {:level "info"  :msg "Validation accuracy: %.3f"}]
   "Archive"          [{:level "info"  :msg "Archiving build artifacts..."}
                       {:level "info"  :msg "Archive created: %s.xcarchive"}]
   "Upload TestFlight" [{:level "info" :msg "Uploading to TestFlight..."}
                        {:level "info" :msg "Build uploaded, processing by Apple"}]
   "Init"             [{:level "info"  :msg "Initializing Terraform..."}
                       {:level "info"  :msg "Providers initialized"}]
   "Validate"         [{:level "info"  :msg "Validating Terraform configuration..."}
                       {:level "info"  :msg "Configuration is valid"}]
   "Plan"             [{:level "info"  :msg "Planning infrastructure changes..."}
                       {:level "info"  :msg "Plan: %d to add, %d to change, %d to destroy"}]
   "Apply"            [{:level "info"  :msg "Applying infrastructure changes..."}
                       {:level "info"  :msg "Apply complete! Resources: %d added, %d changed"}]})

(def failure-log-templates
  "Failure log messages per stage type."
  {"Test"             {:level "error" :msg "FAILED: %d of %d tests failed\n  - %s"}
   "Build"            {:level "error" :msg "Build failed: compilation error in %s"}
   "Lint"             {:level "error" :msg "Lint failed: %d errors found in %d files"}
   "Security Scan"    {:level "error" :msg "Security scan failed: %d vulnerabilities detected (severity: %s)"}
   "Docker Build"     {:level "error" :msg "Docker build failed: %s"}
   "Install"          {:level "error" :msg "Dependency installation failed: could not resolve %s"}
   "Install Deps"     {:level "error" :msg "Pod install failed: incompatible version for %s"}
   "Deploy Staging"   {:level "error" :msg "Staging deploy failed: health check timeout after 60s"}
   "Deploy Prod"      {:level "error" :msg "Production deploy failed: rollback initiated"}
   "Deploy"           {:level "error" :msg "Deployment failed: connection refused on target host"}
   "Train Validation" {:level "error" :msg "Validation failed: accuracy %.3f below threshold 0.85"}
   "Archive"          {:level "error" :msg "Archive failed: code signing error for %s"}
   "Upload TestFlight" {:level "error" :msg "Upload failed: invalid provisioning profile"}
   "Init"             {:level "error" :msg "Terraform init failed: provider %s not available"}
   "Validate"         {:level "error" :msg "Validation failed: %d errors in configuration"}
   "Plan"             {:level "error" :msg "Plan failed: state lock could not be acquired"}
   "Apply"            {:level "error" :msg "Apply failed: resource %s creation timeout"}})

;; ---------------------------------------------------------------------------
;; Flaky test periods — windows where success rate drops
;; ---------------------------------------------------------------------------

(def flaky-periods
  "Per-job flaky test periods: [job-name, day-offset-start, day-offset-end, rate-drop]"
  [["platform-api"   15 20 0.35]
   ["web-frontend"   30 35 0.30]
   ["ios-app"        45 50 0.40]
   ["ml-model-server" 60 65 0.35]
   ["data-pipeline"  70 75 0.30]])

;; ---------------------------------------------------------------------------
;; Approval gate messages
;; ---------------------------------------------------------------------------

(def approval-messages
  ["Production deployment requires approval"
   "Deploy to production — reviewed and tested in staging"
   "Release candidate ready for production"
   "Hotfix deployment pending approval"
   "Scheduled release — awaiting go/no-go"])

;; ---------------------------------------------------------------------------
;; Audit action types
;; ---------------------------------------------------------------------------

(def build-audit-actions
  ["build:create" "build:start" "build:complete" "build:fail" "build:abort"])

(def user-audit-actions
  ["user:login" "user:logout" "user:create" "user:update"])

(def job-audit-actions
  ["job:create" "job:update" "job:delete"])

(def approval-audit-actions
  ["approval:create" "approval:approve" "approval:reject"])
