<p align="center">
  <h1 align="center">Chengis</h1>
  <p align="center">
    <strong>A CI/CD engine written in Clojure</strong>
  </p>
  <p align="center">
    Declarative pipelines &bull; Docker containers &bull; YAML workflows &bull; Distributed builds &bull; Plugin system &bull; Live streaming &bull; Zero JavaScript
  </p>
</p>

---

Chengis is a lightweight, extensible CI/CD system inspired by Jenkins but built from scratch in Clojure. It features a powerful DSL for defining build pipelines, GitHub Actions-style YAML workflows, Docker container support, a distributed master/agent architecture, a plugin system, and a real-time web UI powered by htmx and Server-Sent Events.

**488 tests | 1,993 assertions | 0 failures**

## Why Chengis?

| | Chengis | Jenkins |
|---|---|---|
| **Setup** | `lein run -- init && lein run serve` | Download WAR, configure JVM, set up reverse proxy, install plugins |
| **Configuration** | Clojure DSL, EDN Chengisfile, or YAML workflows | Groovy/Declarative Jenkinsfile + XML job configs |
| **Dependencies** | Single JAR + SQLite (zero external services) or PostgreSQL for production | Java + servlet container + plugin ecosystem |
| **Disk footprint** | ~30 MB uberjar | 400+ MB with common plugins |
| **Live updates** | Built-in SSE streaming, zero JS | Requires page refresh or Blue Ocean plugin |
| **Secrets** | AES-256-GCM encrypted at rest in database | Credentials plugin + separate key store |
| **Pipeline as Code** | `Chengisfile` (EDN) or `chengis.yml` (YAML) | Jenkinsfile (Groovy DSL) |
| **Containers** | Docker steps with per-step/stage/pipeline container config | Docker Pipeline plugin |
| **Distributed** | Built-in HTTP master/agent with label-based dispatch | Master/agent with JNLP or SSH |
| **Extensibility** | Protocol-based plugin system + pure Clojure | 1800+ plugins, complex classloader |
| **Resource usage** | ~100 MB heap for typical workloads | 1-4 GB heap recommended |

Chengis is not a Jenkins replacement for large enterprises. It's a focused, opinionated tool for teams that value simplicity and want CI/CD without the operational overhead.

## Live Build Results

We use Chengis to build real open-source projects. Here are actual build results:

### Java: JUnit5 Samples (Maven)

```
Build #3 — SUCCESS (8.7 sec)
  Git: junit-team/junit5-samples @ main (a9e70e7)

  Stage: Build
    Step: Compile                     SUCCESS  2.1s
      mvn compile -q

  Stage: Test
    Step: Unit Tests                  SUCCESS  5.3s
      Tests run: 5, Failures: 0, Errors: 0

  Artifacts collected:
    TEST-com.example.project.CalculatorTests.xml    7.5 KB
```

### C#: FluentValidation (.NET 9)

```
Build #1 — SUCCESS (8.3 sec)
  Git: FluentValidation/FluentValidation @ main (a712e36)

  Stage: Build
    Step: Restore & Compile           SUCCESS  3.1s
      dotnet build FluentValidation.sln — 0 warnings, 0 errors

  Stage: Test
    Step: Unit Tests                  SUCCESS  4.5s
      Passed: 865, Failed: 0, Skipped: 1

  Artifacts collected:
    FluentValidation.dll              519.5 KB
    TestResults.trx                   1.2 MB
```

## Features

### Core Pipeline Engine

- **Pipeline DSL** &mdash; Define pipelines in pure Clojure with `defpipeline`, `stage`, `step`, `parallel`, `sh`
- **Pipeline as Code** &mdash; Drop a `Chengisfile` (EDN format) in your repo root; Chengis auto-detects it
- **YAML Workflows** &mdash; GitHub Actions-style YAML (`chengis.yml`) with `${{ }}` expression syntax
- **Git integration** &mdash; Clone any Git repo, extract commit metadata (SHA, branch, author, message)
- **Parallel execution** &mdash; Steps within a stage can run concurrently via `core.async`
- **Post-build actions** &mdash; `always`, `on-success`, `on-failure` hooks that never affect build status
- **Conditional execution** &mdash; `when-branch` and `when-param` for environment-aware pipelines
- **Build cancellation** &mdash; Graceful cancellation with interrupt propagation
- **Build retry** &mdash; One-click retry of failed builds
- **Matrix builds** &mdash; Run pipelines across parameter combinations (e.g., OS x JDK), `MATRIX_*` env vars, exclude filters

### Docker Integration

- **Per-step containers** &mdash; Steps run inside Docker containers via `:type :docker`
- **Stage-level containers** &mdash; All steps in a stage share a Docker image
- **Pipeline-level containers** &mdash; Default container config propagated to all stages
- **Docker Compose** &mdash; Run steps via `docker-compose run` with custom compose files
- **Image management** &mdash; Automatic pull with configurable policies (`:always`, `:if-not-present`, `:never`)
- **Image policies** &mdash; Org-scoped Docker image allow/deny policies with priority-based evaluation and glob patterns
- **Input validation** &mdash; Docker image names, service names, and args validated against injection attacks

### GitHub Actions-style YAML

- **Auto-detected** &mdash; `.chengis/workflow.yml` or `chengis.yml` in workspace
- **Full format** &mdash; Stages, steps, parallel, container, env, conditions, post-actions, artifacts, notify
- **Expression syntax** &mdash; `${{ parameters.name }}`, `${{ secrets.KEY }}`, `${{ env.VAR }}`
- **Validation** &mdash; Detailed error messages with stage/step location
- **Multi-format** &mdash; Priority detection: Chengisfile (EDN) > YAML > server pipeline

### Plugin System

- **Protocol-based** &mdash; Extension points: `StepExecutor`, `PipelineFormat`, `Notifier`, `ArtifactHandler`, `ScmProvider`, `ScmStatusReporter`, `SecretBackend`
- **Central registry** &mdash; Register/lookup/introspect plugins at runtime
- **Builtin plugins** &mdash; Shell, Docker, Docker Compose, Git, Console, Slack, Email, Local Artifacts, Local Secrets, Vault Secrets, YAML Format, GitHub Status, GitLab Status
- **External plugins** &mdash; Load `.clj` files from plugins directory with lifecycle management
- **Plugin trust** &mdash; External plugins gated by DB-backed allowlist; untrusted plugins blocked with admin UI management

### Distributed Builds

- **Master/agent architecture** &mdash; HTTP-based with shared-secret auth
- **Label-based dispatch** &mdash; Route builds to agents matching required labels
- **Heartbeat monitoring** &mdash; Agents send periodic heartbeats; configurable offline detection (default 90s)
- **Local fallback** &mdash; Configurable fallback to local execution when no agents match (default: disabled for fail-fast)
- **Feature-flagged dispatch** &mdash; Dispatcher wiring gated by `:distributed-dispatch` feature flag for safe rollout
- **Agent management UI** &mdash; Status badges, capacity metrics, real-time monitoring
- **Persistent build queue** &mdash; Priority-based queue with configurable concurrency
- **Circuit breaker** &mdash; Automatic fault detection with half-open recovery for agent communication
- **Orphan monitor** &mdash; Auto-fail builds from agents that go offline
- **Artifact transfer** &mdash; Agent-to-master HTTP upload for build artifacts

### Authentication & Security

- **User management** &mdash; Admin/developer/viewer roles with RBAC
- **JWT authentication** &mdash; Token-based auth with configurable expiry and blacklist support
- **SSO/OIDC** &mdash; Single Sign-On via OpenID Connect providers (Google, Okta, etc.)
- **API tokens** &mdash; Generate and revoke API tokens per user with scope restrictions
- **Session management** &mdash; Secure cookie-based sessions with password-reset invalidation
- **Account lockout** &mdash; Configurable threshold and duration after failed login attempts
- **Encrypted secrets** &mdash; AES-256-GCM encryption at rest, automatic log masking with `***`
- **Secret backends** &mdash; Pluggable secret storage: local (default) or HashiCorp Vault
- **CSRF protection** &mdash; Anti-forgery tokens on all form endpoints
- **Rate limiting** &mdash; Configurable request rate limiting middleware
- **Audit logging** &mdash; All user actions logged with admin viewer and CSV/JSON export
- **Input validation** &mdash; Docker command injection protection, agent registration field sanitization

### Multi-Tenancy

- **Organization model** &mdash; Isolate resources (jobs, builds, secrets, templates) per organization
- **Org-scoped stores** &mdash; All database queries filter by `org_id` for tenant isolation
- **Cross-org protection** &mdash; SSE streams, alerts, and webhooks enforce org boundaries
- **Default org** &mdash; Backward-compatible default organization for legacy data
- **Org membership** &mdash; Users belong to one or more organizations

### Governance & Compliance

- **Policy engine** &mdash; Org-scoped build policies with priority ordering and short-circuit evaluation
- **Artifact checksums** &mdash; SHA-256 integrity verification on collected artifacts
- **Compliance reports** &mdash; Policy evaluation results tracked per build with admin dashboard
- **Feature flags** &mdash; Runtime feature toggling via config or `CHENGIS_FEATURE_*` environment variables
- **Plugin trust** &mdash; External plugin allowlist with admin management UI
- **Docker image policies** &mdash; Allow/deny rules for Docker registries and images per organization

### Build Reliability

- **Build attempts** &mdash; Retry tracking with `attempt_number` and `root_build_id` linking all retries
- **Durable events** &mdash; Build events persisted to database with time-ordered IDs for replay after restarts
- **Event replay API** &mdash; `GET /api/builds/:id/events/replay` with cursor-based pagination
- **Dispatcher wiring** &mdash; All trigger paths (UI, webhooks, retry) route through distributed dispatcher when enabled

### Approval Gates

- **Manual checkpoints** &mdash; Pause pipeline execution pending human approval
- **Multi-approver** &mdash; Require N approvals before proceeding (configurable threshold)
- **Approve/reject** &mdash; One-click approval or rejection via web UI
- **Timeout support** &mdash; Auto-fail builds if approval is not received within configured duration
- **RBAC-gated** &mdash; Only authorized roles can approve or reject gates

### Pipeline Templates

- **Admin-defined** &mdash; Create reusable pipeline templates from the admin UI
- **Create from template** &mdash; Quickly create new jobs based on existing templates
- **Template management** &mdash; Edit, update, and delete templates

### Observability

- **Live streaming** &mdash; SSE-powered real-time build output (no polling, no WebSocket)
- **Prometheus metrics** &mdash; `/metrics` endpoint with build, auth, webhook, and system metrics
- **Build notifications** &mdash; Console, Slack, and email notifications
- **Admin dashboard** &mdash; JVM stats, memory usage, executor pool status, disk usage breakdown
- **Build history** &mdash; Full log retention with stage/step breakdown, timing, and attempt tracking
- **Alert system** &mdash; System health alerts with auto-resolve
- **Data retention** &mdash; Automated cleanup scheduler for audit logs, webhook events, and old data
- **Database backup** &mdash; Hot backup via SQLite `VACUUM INTO` or `pg_dump` for PostgreSQL, CLI commands, and admin UI download
- **Audit export** &mdash; Export audit logs as CSV or JSON for SIEM integration

### Persistence

- **Dual-driver database** &mdash; SQLite (default, zero-config) or PostgreSQL (production, with HikariCP connection pooling) — config-driven switch via `:database {:type "postgresql"}` or `CHENGIS_DATABASE_TYPE=postgresql`
- **34 migration versions** &mdash; Separate migration directories per database type (`migrations/sqlite/` and `migrations/postgresql/`)
- **Artifact collection** &mdash; Glob-based artifact patterns, persistent storage, download via UI
- **Webhook logging** &mdash; All incoming webhooks logged with provider, status, and payload size
- **Secret access audit** &mdash; Track all secret reads with timestamp and user info

### User Interface

- **Web UI** &mdash; htmx + Tailwind CSS, zero custom JavaScript, dark theme
- **CLI** &mdash; Full command-line interface for headless/scripted usage
- **Login page** &mdash; Username/password authentication with lockout protection
- **Parameterized builds** &mdash; Dynamic trigger forms with text, choice, and boolean parameters
- **SCM webhooks** &mdash; GitHub/GitLab webhook endpoint for push-triggered builds
- **Agent page** &mdash; Agent status, capacity, labels, and system info
- **User management** &mdash; Admin panel for creating, editing, and deactivating users
- **Audit viewer** &mdash; Filterable audit log with date range, action type, and user filters
- **Webhook viewer** &mdash; Admin page showing all incoming webhook events
- **Approval dashboard** &mdash; View and act on pending approval gates
- **Template management** &mdash; Create, edit, and delete pipeline templates
- **API token management** &mdash; Generate and revoke personal API tokens
- **Compliance dashboard** &mdash; Policy evaluation results across builds
- **Plugin policy admin** &mdash; Manage external plugin trust allowlist
- **Docker policy admin** &mdash; Manage Docker image allow/deny rules per organization

## Quick Start

### Prerequisites

- **Java 21+** (for `--enable-native-access`)
- **Leiningen** (Clojure build tool)
- **Git** (for source checkout)
- **Docker** (optional, for container steps)

### Install and Run

```bash
# Clone the repository
git clone https://github.com/sremani/chengis.git
cd chengis

# Initialize the database
lein run -- init

# Start the web server
lein run serve
```

Open [http://localhost:8080](http://localhost:8080) in your browser.

### Docker Deployment

```bash
# Build the Docker image
docker build -t chengis:latest .

# Run standalone
docker run -p 8080:8080 -v chengis-data:/data chengis:latest

# Run with docker-compose (master + 2 agents)
docker compose up --build
```

The included `docker-compose.yml` sets up a master node with two agent nodes, shared-secret auth, and a persistent data volume. All configuration is via `CHENGIS_*` environment variables.

### Start an Agent (Distributed Mode)

```bash
# On the agent machine:
lein run agent --master-url http://master:8080 --labels docker,linux --port 9090
```

### Create Your First Pipeline

Create a file `pipelines/my-app.clj`:

```clojure
(require '[chengis.dsl.core :refer [defpipeline stage step sh]])

(defpipeline my-app
  {:description "My first pipeline"}

  (stage "Build"
    (step "Compile"
      (sh "echo 'Building...'")))

  (stage "Test"
    (step "Run tests"
      (sh "echo 'All tests passed!'"))))
```

Register and trigger it:

```bash
lein run -- job create pipelines/my-app.clj
lein run -- build trigger my-app
```

Or use the web UI: navigate to **Jobs** > **my-app** > **Trigger Build**.

## Pipeline Formats

Chengis supports three pipeline definition formats, auto-detected in priority order:

### 1. Clojure DSL

```clojure
(defpipeline my-app
  {:description "Application pipeline"
   :source {:type :git
            :url "https://github.com/org/repo.git"
            :branch "main"
            :depth 1}}

  (stage "Build"
    (step "Compile" (sh "make build")))

  (stage "Test"
    (parallel
      (step "Unit" (sh "make test"))
      (step "Lint" (sh "make lint")))))
```

### 2. Chengisfile (EDN)

Drop a file named `Chengisfile` in your repository root:

```clojure
{:description "My application CI"
 :stages [{:name "Build"
           :steps [{:name "Compile" :run "make build"}]}
          {:name "Test"
           :parallel true
           :steps [{:name "Unit Tests" :run "make test"}
                   {:name "Lint" :run "make lint"}]}]
 :post {:always [{:name "Cleanup" :run "rm -rf tmp/"}]}
 :artifacts ["target/*.jar" "coverage/*.html"]
 :notify [{:type :slack :webhook-url "https://hooks.slack.com/..."}]}
```

### 3. YAML Workflow (GitHub Actions-style)

Create `.chengis/workflow.yml` or `chengis.yml`:

```yaml
name: my-app
description: "Build and test"
container:
  image: node:18
env:
  CI: "true"
parameters:
  environment:
    type: choice
    choices: [staging, production]
    default: staging
on:
  push:
    branches: [main]

stages:
  - name: Build
    steps:
      - name: Install
        run: npm install
      - name: Compile
        run: npm run build

  - name: Test
    parallel: true
    steps:
      - name: Unit Tests
        run: npm test
      - name: E2E
        run: npm run e2e
        container:
          image: cypress/browsers:node18

  - name: Deploy
    when:
      branch: main
    steps:
      - name: Deploy
        run: ./deploy.sh
        env:
          ENV: ${{ parameters.environment }}

post:
  always:
    - name: Cleanup
      run: rm -rf .cache

artifacts:
  - "dist/**"
  - "coverage/*.html"

notify:
  - type: slack
    webhook-url: ${{ secrets.SLACK_WEBHOOK }}
```

## Docker Support

### Per-Step Docker

```clojure
(require '[chengis.dsl.docker :refer [docker-step]])

(stage "Test"
  (docker-step "maven:3.9" "Unit Tests" "mvn test"))
```

### Stage-Level Container

```clojure
(require '[chengis.dsl.docker :refer [container]])

(container {:image "node:18"}
  (stage "Build"
    (step "Install" (sh "npm install"))
    (step "Test" (sh "npm test"))))
```

### Chengisfile EDN with Docker

```clojure
{:stages [{:name "Build"
           :container {:image "node:18"}
           :steps [{:name "Install" :run "npm install"}]}
          {:name "Test"
           :steps [{:name "Maven Test" :run "mvn test" :image "maven:3.9"}]}]}
```

## DSL Reference

### Parallel Steps

```clojure
(stage "Test"
  (parallel
    (step "Unit Tests"  (sh "make test-unit"))
    (step "Integration" (sh "make test-integration"))
    (step "Lint"        (sh "make lint"))))
```

### Post-Build Actions

```clojure
(post
  (always
    (step "Cleanup" (sh "rm -rf tmp/")))
  (on-success
    (step "Deploy" (sh "./deploy.sh")))
  (on-failure
    (step "Alert" (sh "curl -X POST https://hooks.slack.com/..."))))
```

### Conditional Execution

```clojure
(stage "Deploy"
  (when-branch "main"
    (step "Deploy Prod" (sh "./deploy.sh prod")))
  (when-param :environment "staging"
    (step "Deploy Staging" (sh "./deploy.sh staging"))))
```

### Shell Command Options

```clojure
(sh "make build"
  :env {"NODE_ENV" "production" "CI" "true"}  ; extra env vars
  :dir "frontend"                              ; working directory
  :timeout 300000)                             ; 5 minute timeout
```

### Parameterized Builds

```clojure
(defpipeline deploy
  {:description "Deployment pipeline"
   :parameters [{:name "environment"
                 :type :choice
                 :choices ["staging" "production"]
                 :default "staging"}
                {:name "skip-tests"
                 :type :boolean
                 :default false}]}
  ...)
```

Parameters are injected as environment variables: `PARAM_ENVIRONMENT`, `PARAM_SKIP_TESTS`.

### Matrix Builds

Run a pipeline across multiple parameter combinations:

**Clojure DSL:**
```clojure
(defpipeline cross-platform
  {:description "Cross-platform build"}

  (matrix {:os ["linux" "macos"] :jdk ["11" "17"]}
          :exclude [{:os "macos" :jdk "11"}])

  (stage "Build"
    (step "Compile" (sh "echo Building on $MATRIX_OS with JDK $MATRIX_JDK"))))
```

**Chengisfile (EDN):**
```clojure
{:stages [{:name "Build"
           :steps [{:name "Compile" :run "make build"}]}]
 :matrix {:os ["linux" "macos"] :jdk ["11" "17"]}}
```

**YAML:**
```yaml
strategy:
  matrix:
    os: [linux, macos]
    jdk: ["11", "17"]
  exclude:
    - os: macos
      jdk: "11"
stages:
  - name: Build
    steps:
      - name: Compile
        run: echo "Building on $MATRIX_OS with JDK $MATRIX_JDK"
```

Each combination gets its own stage copy with `MATRIX_*` environment variables injected. Stage names are suffixed: `Build [os=linux, jdk=11]`.

## CLI Reference

```
Usage: lein run -- <command> [args]

Database:
  init                          Initialize database (run migrations)

Jobs:
  job create <pipeline.clj>     Create a job from a pipeline definition file
  job create-repo <name> <url>  Create a job from a repo with Chengisfile
  job list                      List all registered jobs
  job show <name>               Show job details
  job delete <name>             Delete a job

Builds:
  build trigger <job-name>      Trigger a new build
  build cancel <build-id>       Cancel a running build
  build retry <build-id>        Retry a completed build
  build list [job-name]         List builds (optionally by job)
  build show <build-id>         Show build details
  build log <build-id>          Show build step output

Secrets:
  secret set <name> <value>     Set a secret (--scope <job-id> for job-scoped)
  secret list                   List secret names (--scope <job-id>)
  secret delete <name>          Delete a secret (--scope <job-id>)

Pipeline:
  pipeline validate <file>      Validate a pipeline file (.clj)
  pipeline validate-edn <file>  Validate a Chengisfile (EDN)

Database:
  backup [output-dir]           Create database backup
  restore <backup-file>         Restore from backup (--force to overwrite)

System:
  status                        Show system status (jobs, builds, queue)
  serve                         Start the web UI server (default port 8080)

Agent:
  agent                         Start as an agent node
    --master-url URL            Master server URL
    --port PORT                 Agent HTTP port (default 9090)
    --labels LABELS             Comma-separated labels (e.g., docker,linux)
```

## Configuration

Chengis uses sensible defaults. Override via `resources/config.edn` or environment variables:

```clojure
{:database   {:type "sqlite"         ;; "sqlite" (default) or "postgresql"
              :path "chengis.db"    ;; SQLite file path
              ;; PostgreSQL settings (used when :type "postgresql"):
              ;; :host "localhost" :port 5432 :dbname "chengis"
              ;; :user "chengis" :password "secret"
              ;; :pool {:minimum-idle 2 :maximum-pool-size 10}
              }
 :workspace  {:root "workspaces"}
 :server     {:port 8080 :host "0.0.0.0"}
 :log        {:level :info}

 ;; Authentication & RBAC
 :auth       {:enabled false
              :jwt-secret "your-jwt-signing-secret"
              :jwt-expiry-hours 24
              :session-secret "your-session-signing-key"
              :seed-admin-password "initial-admin-password"}

 ;; Secrets encryption (required for secrets management)
 :secrets    {:master-key "your-32-byte-hex-key"}

 ;; Artifact storage
 :artifacts  {:root "artifacts"
              :retention-builds 10}

 ;; Notifications
 :notifications {:slack {:default-webhook "https://hooks.slack.com/..."}
                 :email {:host "smtp.example.com" :port 587 :from "ci@example.com"}}

 ;; Plugin system
 :plugins    {:directory "plugins"
              :enabled []}

 ;; Docker defaults
 :docker     {:host "unix:///var/run/docker.sock"
              :default-timeout 600000
              :pull-policy :if-not-present}

 ;; Distributed builds
 :distributed {:enabled false
               :mode :master
               :auth-token "your-shared-secret"
               :agent {:port 9090
                       :labels #{"linux" "docker"}
                       :max-builds 2}
               :heartbeat-timeout-ms 90000
               :dispatch {:fallback-local false
                          :queue-enabled false}}

 ;; Feature flags (opt-in for new features)
 :feature-flags {:distributed-dispatch false}

 ;; Prometheus metrics
 :metrics    {:enabled false
              :auth-required false}

 ;; Rate limiting
 :rate-limit {:enabled false}

 ;; Data retention
 :retention  {:enabled false
              :interval-hours 24
              :audit-days 90
              :webhook-events-days 30
              :secret-access-days 90}

 ;; Matrix builds
 :matrix     {:max-combinations 25}

 ;; Automatic cleanup
 :cleanup    {:enabled true
              :interval-hours 24
              :retention-builds 10}}
```

### Environment Variable Configuration

All configuration can be overridden with `CHENGIS_*` environment variables. Nested keys use `_` separators:

| Environment Variable | Config Path | Example |
|---------------------|-------------|---------|
| `CHENGIS_SERVER_PORT` | `[:server :port]` | `8080` |
| `CHENGIS_AUTH_ENABLED` | `[:auth :enabled]` | `true` |
| `CHENGIS_AUTH_JWT_SECRET` | `[:auth :jwt-secret]` | `my-secret` |
| `CHENGIS_DATABASE_TYPE` | `[:database :type]` | `postgresql` |
| `CHENGIS_DATABASE_PATH` | `[:database :path]` | `/data/chengis.db` |
| `CHENGIS_DATABASE_HOST` | `[:database :host]` | `localhost` |
| `CHENGIS_DATABASE_PORT` | `[:database :port]` | `5432` |
| `CHENGIS_DATABASE_NAME` | `[:database :dbname]` | `chengis` |
| `CHENGIS_DATABASE_USER` | `[:database :user]` | `chengis` |
| `CHENGIS_DATABASE_PASSWORD` | `[:database :password]` | `secret` |
| `CHENGIS_SECRETS_MASTER_KEY` | `[:secrets :master-key]` | `0123456789abcdef...` |
| `CHENGIS_DISTRIBUTED_ENABLED` | `[:distributed :enabled]` | `true` |
| `CHENGIS_DISTRIBUTED_FALLBACK_LOCAL` | `[:distributed :dispatch :fallback-local]` | `false` |
| `CHENGIS_DISTRIBUTED_QUEUE_ENABLED` | `[:distributed :dispatch :queue-enabled]` | `true` |
| `CHENGIS_DISTRIBUTED_HEARTBEAT_TIMEOUT_MS` | `[:distributed :heartbeat-timeout-ms]` | `90000` |
| `CHENGIS_FEATURE_DISTRIBUTED_DISPATCH` | `[:feature-flags :distributed-dispatch]` | `true` |
| `CHENGIS_METRICS_ENABLED` | `[:metrics :enabled]` | `true` |
| `CHENGIS_RETENTION_ENABLED` | `[:retention :enabled]` | `true` |

Type coercion is automatic: `"true"`/`"false"` become booleans, numeric strings become integers.

## Web UI Routes

| Route | Description |
|-------|-------------|
| `GET /` | Dashboard with build stats and recent activity |
| `GET /login` | Login page |
| `POST /login` | Authenticate user |
| `POST /logout` | Log out |
| `GET /jobs` | List all registered jobs |
| `GET /jobs/:name` | Job detail with build history |
| `POST /jobs/:name/trigger` | Trigger a new build |
| `GET /builds/:id` | Build detail with stages, logs, artifacts |
| `GET /builds/:id/log` | Full build console output |
| `POST /builds/:id/cancel` | Cancel a running build |
| `POST /builds/:id/retry` | Retry a failed build |
| `GET /builds/:id/artifacts/:file` | Download a collected artifact |
| `GET /approvals` | Approval gates dashboard |
| `POST /approvals/:id/approve` | Approve a gate |
| `POST /approvals/:id/reject` | Reject a gate |
| `GET /agents` | Agent management page |
| `GET /settings/tokens` | API token management |
| `GET /admin` | Admin dashboard (JVM stats, disk usage) |
| `POST /admin/cleanup` | Trigger workspace/artifact cleanup |
| `POST /admin/retention` | Run data retention cleanup |
| `POST /admin/backup` | Download database backup |
| `GET /admin/audit` | Audit log viewer with filtering |
| `GET /admin/audit/export` | Export audit logs (CSV/JSON) |
| `GET /admin/webhooks` | Webhook event viewer |
| `GET /admin/users` | User management |
| `GET /admin/templates` | Pipeline template management |
| `GET /admin/plugins/policies` | Plugin trust policy management |
| `POST /admin/plugins/policies` | Set plugin trust policy |
| `POST /admin/plugins/policies/:name/delete` | Delete plugin trust policy |
| `GET /admin/docker/policies` | Docker image policy management |
| `POST /admin/docker/policies` | Create Docker image policy |
| `POST /admin/docker/policies/:id/delete` | Delete Docker image policy |
| `GET /health` | Health check endpoint |
| `GET /ready` | Readiness check endpoint |
| `GET /metrics` | Prometheus metrics endpoint |
| `GET /api/builds/:id/events` | SSE stream for live build updates |
| `GET /api/builds/:id/events/replay` | Historical event replay (JSON, cursor pagination) |
| `POST /api/webhook` | SCM webhook endpoint (GitHub/GitLab) |
| `POST /api/agents/register` | Agent registration (machine-to-machine) |
| `POST /api/agents/:id/heartbeat` | Agent heartbeat |

## Project Structure

```
chengis/
  src/chengis/
    agent/                  # Agent node (5 files)
      core.clj              # Agent entry point + HTTP server
      artifact_uploader.clj # Artifact upload to master
      client.clj            # HTTP client for master communication
      heartbeat.clj         # Periodic heartbeat scheduler
      worker.clj            # Build execution on agent
    cli/                    # Command-line interface (3 files)
      core.clj              # CLI dispatcher
      commands.clj          # Job, build, secret, pipeline commands
      output.clj            # Formatted output helpers
    db/                     # Database persistence layer (21 files)
      connection.clj        # SQLite + PostgreSQL (HikariCP) connection pool
      migrate.clj           # Migratus migration runner
      job_store.clj         # Job CRUD
      build_store.clj       # Build + stage + step CRUD (with attempt tracking)
      build_event_store.clj # Durable build event persistence
      secret_store.clj      # Encrypted secrets
      artifact_store.clj    # Artifact metadata (with checksums)
      notification_store.clj # Notification events
      user_store.clj        # User accounts + API tokens
      org_store.clj         # Organization CRUD + membership
      audit_store.clj       # Audit log queries
      audit_export.clj      # CSV/JSON audit export
      webhook_log.clj       # Webhook event logging
      secret_audit.clj      # Secret access audit
      approval_store.clj    # Approval gate records
      template_store.clj    # Pipeline template CRUD
      policy_store.clj      # Org-scoped build policies
      compliance_store.clj  # Build compliance tracking
      plugin_policy_store.clj # Plugin trust allowlist
      docker_policy_store.clj # Docker image policies
      backup.clj            # Database backup/restore
    distributed/            # Distributed build coordination (8 files)
      agent_registry.clj    # In-memory agent registry
      dispatcher.clj        # Build dispatch (local vs remote)
      master_api.clj        # Master API handlers
      build_queue.clj       # Persistent build queue
      queue_processor.clj   # Queue processing worker
      circuit_breaker.clj   # Agent communication circuit breaker
      orphan_monitor.clj    # Orphaned build detection
      artifact_transfer.clj # Agent-to-master artifact upload
    dsl/                    # Pipeline DSL and formats (6 files)
      core.clj              # defpipeline macro + DSL helpers
      chengisfile.clj       # EDN Pipeline as Code parser
      docker.clj            # Docker DSL helpers
      yaml.clj              # YAML workflow parser
      expressions.clj       # ${{ }} expression resolver
      templates.clj         # Pipeline template DSL
    engine/                 # Build execution engine (18 files)
      executor.clj          # Core pipeline runner
      build_runner.clj      # Build lifecycle + thread pool
      docker.clj            # Docker command generation
      process.clj           # Shell command execution
      git.clj               # Git clone + metadata
      artifacts.clj         # Glob-based artifact collection
      notify.clj            # Notification dispatch
      cleanup.clj           # Workspace/artifact cleanup
      events.clj            # core.async event bus + durable persistence
      workspace.clj         # Build workspace management
      scheduler.clj         # Cron scheduling
      log_masker.clj        # Secret masking in output
      matrix.clj            # Matrix build expansion
      retention.clj         # Data retention scheduler
      approval.clj          # Approval gate engine
      scm_status.clj        # SCM commit status reporting
      compliance.clj        # Build compliance evaluation
      policy.clj            # Policy engine integration
    model/                  # Data specs (1 file)
      spec.clj              # Clojure specs for validation
    plugin/                 # Plugin system (15 files)
      protocol.clj          # Plugin protocols (7 protocols)
      registry.clj          # Central plugin registry
      loader.clj            # Plugin discovery + lifecycle
      builtin/              # 12 builtin plugins
        shell.clj           # Shell step executor
        docker.clj          # Docker step executor
        docker_compose.clj  # Docker Compose step executor
        console_notifier.clj # Console notifier
        slack_notifier.clj  # Slack notifier (Block Kit)
        email_notifier.clj  # Email notifier (SMTP)
        git_scm.clj         # Git SCM provider
        local_artifacts.clj # Local artifact handler
        local_secrets.clj   # Local secret backend (default)
        vault_secrets.clj   # HashiCorp Vault secret backend
        yaml_format.clj     # YAML pipeline format
        github_status.clj   # GitHub commit status reporter
        gitlab_status.clj   # GitLab commit status reporter
    web/                    # HTTP server and UI (30 files)
      server.clj            # http-kit server startup
      routes.clj            # Reitit routes + middleware
      handlers.clj          # Request handlers
      sse.clj               # Server-Sent Events
      webhook.clj           # SCM webhook handler
      auth.clj              # JWT/session/API token authentication + RBAC
      audit.clj             # Audit logging middleware
      alerts.clj            # System alert management
      rate_limit.clj        # Request rate limiting
      account_lockout.clj   # Account lockout logic
      metrics_middleware.clj # HTTP request metrics
      views/                # Hiccup view templates (19 files)
        layout.clj          # Base HTML layout
        dashboard.clj       # Home page
        jobs.clj            # Job list + detail
        builds.clj          # Build detail + logs + matrix grid + attempts
        components.clj      # Reusable UI components
        admin.clj           # Admin dashboard
        trigger_form.clj    # Parameterized build form
        agents.clj          # Agent management page
        login.clj           # Login page
        users.clj           # User management page
        tokens.clj          # API token management
        audit.clj           # Audit log viewer
        approvals.clj       # Approval gates page
        templates.clj       # Pipeline template management
        webhooks.clj        # Webhook event viewer
        compliance.clj      # Compliance dashboard
        policies.clj        # Policy management
        plugin_policies.clj # Plugin trust allowlist management
        docker_policies.clj # Docker image policy management
    config.clj              # Configuration loading + env var overrides
    logging.clj             # Structured logging setup
    metrics.clj             # Prometheus metrics registry
    util.clj                # Shared utilities
    core.clj                # Entry point
  test/chengis/             # Test suite (77 files)
  resources/migrations/     # Database migrations (34 versions × 2 drivers)
    sqlite/                 # SQLite-dialect migrations
    postgresql/             # PostgreSQL-dialect migrations
  pipelines/                # Example pipeline definitions (5 files)
  benchmarks/               # Performance benchmark suite
  Dockerfile                # Multi-stage Docker build
  docker-compose.yml        # Master + 2 agents deployment
```

**Codebase:** ~18,600 lines source + ~11,500 lines tests across 191 files

## Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Clojure 1.12 | Core language |
| Concurrency | core.async | Parallel steps, SSE event bus |
| Process execution | babashka/process | Shell command runner |
| Database | SQLite + PostgreSQL + next.jdbc + HoneySQL | Persistence (dual-driver) |
| Connection pool | HikariCP | PostgreSQL connection pooling |
| Migrations | Migratus | Schema evolution (34 versions × 2 drivers) |
| Web server | http-kit | Async HTTP + SSE |
| Routing | Reitit | Ring-compatible routing |
| HTML | Hiccup 2 | Server-side rendering |
| Frontend | htmx + Tailwind CSS | Reactive UI, zero custom JS |
| Auth | buddy-sign (JWT) | Token-based authentication |
| Metrics | iapetos (Prometheus) | Observability + `/metrics` endpoint |
| HTTP client | clj-http | Agent communication |
| YAML | clj-yaml (SnakeYAML) | YAML workflow parsing |
| Scheduling | Chime | Cron-based triggers + heartbeats |
| Logging | Timbre | Structured logging |
| Encryption | javax.crypto (AES-256-GCM) | Secrets at rest |
| Build tool | Leiningen | Project management |

## Chengis vs Jenkins: Detailed Comparison

### Architecture

| Aspect | Chengis | Jenkins |
|--------|---------|---------|
| Runtime | Single JVM, optional agent nodes | Master + optional agent nodes |
| Storage | SQLite (default) or PostgreSQL | XML files on filesystem |
| UI rendering | Server-side (Hiccup + htmx) | Server-side (Jelly/Groovy) + client JS |
| Plugin system | Protocol-based (12 builtin plugins) | 1800+ plugins, complex classloader |
| Pipeline formats | Clojure DSL + EDN + YAML | Groovy DSL (scripted/declarative) |
| Container support | Docker steps (built-in) | Docker Pipeline plugin |
| Distributed | HTTP master/agent (built-in) | JNLP/SSH agents |
| API | Ring HTTP handlers + JSON API | REST API + Jenkins CLI |
| Auth | JWT + sessions + API tokens with RBAC | Jenkins security realm + authorization strategy |

### When to Choose Chengis

- You want CI/CD running in minutes, not hours
- Your team is small to medium (1-20 developers)
- You value simplicity over feature breadth
- You want Docker container builds without extra plugins
- You prefer YAML workflows or Clojure DSL
- You need a self-contained system with no external dependencies
- You want to understand and modify your CI/CD engine

### When to Choose Jenkins

- You need specific integrations (LDAP, Artifactory, Kubernetes, etc.)
- You're in a large enterprise with existing Jenkins infrastructure
- You need the ecosystem of 1800+ community plugins

## Running Tests

```bash
# Run the full test suite
lein test

# Run a specific test namespace
lein test chengis.engine.executor-test

# Run with verbose output
lein test 2>&1 | tee test-output.log
```

Current test suite: **488 tests, 1,993 assertions, 0 failures**

Test coverage spans:
- DSL parsing and pipeline construction
- Chengisfile parsing and conversion
- YAML parsing, validation, and expression resolution
- Pipeline execution engine
- Matrix build expansion
- Docker command generation and input validation
- Plugin registry, loader, and trust enforcement
- Distributed agent registry, dispatcher, and master API
- Dispatcher integration (feature flag gating, fallback behavior)
- Build queue, circuit breaker, and orphan monitor
- Build attempt tracking and retry chains
- Durable build event persistence and replay
- Approval gate engine (including multi-approver concurrency)
- Post-build action handling
- Build cancellation
- Authentication, RBAC, JWT, and OIDC handling
- API token scopes and authorization parity
- Account lockout and rate limiting
- Security concurrency (race conditions)
- Audit logging and export
- Database persistence and statistics
- Multi-tenancy and cross-org resource isolation
- Cross-org security regression tests (SSE, webhooks, alerts, secrets)
- Webhook event logging
- Configuration and environment variable loading
- Prometheus metrics
- Web view rendering
- Vault secrets plugin
- Plugin policy store (trust allowlist, org isolation)
- Docker policy store (image allow/deny, priority ordering, org isolation)
- Policy engine and compliance reports

## Building an Uberjar

```bash
lein uberjar

# Run the standalone JAR
java -jar target/uberjar/chengis-0.1.0-SNAPSHOT-standalone.jar init
java -jar target/uberjar/chengis-0.1.0-SNAPSHOT-standalone.jar serve
```

## Example Pipelines

The `pipelines/` directory contains ready-to-use examples:

| Pipeline | Language | Description |
|----------|----------|-------------|
| `example.clj` | Shell | Basic stages with parallel execution |
| `hello-git.clj` | Git | Git clone and metadata verification |
| `java-demo.clj` | Java | JUnit5 Samples with Maven build + test |
| `dotnet-demo.clj` | C# | FluentValidation with .NET 9 build + test |
| `chengis.clj` | Clojure | Self-hosting: Chengis builds itself |

## License

MIT

## Acknowledgments

Built with these excellent Clojure libraries: [core.async](https://github.com/clojure/core.async), [babashka/process](https://github.com/babashka/process), [next.jdbc](https://github.com/seancorfield/next-jdbc), [HoneySQL](https://github.com/seancorfield/honeysql), [http-kit](https://github.com/http-kit/http-kit), [Reitit](https://github.com/metosin/reitit), [Hiccup](https://github.com/weavejester/hiccup), [Timbre](https://github.com/taoensso/timbre), [Migratus](https://github.com/yogthos/migratus), [Chime](https://github.com/jarohen/chime), [clj-yaml](https://github.com/clj-commons/clj-yaml), [buddy-sign](https://github.com/funcool/buddy-sign), [iapetos](https://github.com/clj-commons/iapetos), [clj-http](https://github.com/dakrone/clj-http).

UI powered by [htmx](https://htmx.org/) and [Tailwind CSS](https://tailwindcss.com/).
