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

**100 tests | 493 assertions | 0 failures**

## Why Chengis?

| | Chengis | Jenkins |
|---|---|---|
| **Setup** | `lein run -- init && lein run serve` | Download WAR, configure JVM, set up reverse proxy, install plugins |
| **Configuration** | Clojure DSL, EDN Chengisfile, or YAML workflows | Groovy/Declarative Jenkinsfile + XML job configs |
| **Dependencies** | Single JAR + SQLite (zero external services) | Java + servlet container + plugin ecosystem |
| **Disk footprint** | ~30 MB uberjar | 400+ MB with common plugins |
| **Live updates** | Built-in SSE streaming, zero JS | Requires page refresh or Blue Ocean plugin |
| **Secrets** | AES-256-GCM encrypted at rest in SQLite | Credentials plugin + separate key store |
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

### Docker Integration

- **Per-step containers** &mdash; Steps run inside Docker containers via `:type :docker`
- **Stage-level containers** &mdash; All steps in a stage share a Docker image
- **Pipeline-level containers** &mdash; Default container config propagated to all stages
- **Docker Compose** &mdash; Run steps via `docker-compose run` with custom compose files
- **Image management** &mdash; Automatic pull with configurable policies (`:always`, `:if-not-present`, `:never`)
- **Input validation** &mdash; Docker image names, service names, and args validated against injection attacks

### GitHub Actions-style YAML

- **Auto-detected** &mdash; `.chengis/workflow.yml` or `chengis.yml` in workspace
- **Full format** &mdash; Stages, steps, parallel, container, env, conditions, post-actions, artifacts, notify
- **Expression syntax** &mdash; `${{ parameters.name }}`, `${{ secrets.KEY }}`, `${{ env.VAR }}`
- **Validation** &mdash; Detailed error messages with stage/step location
- **Multi-format** &mdash; Priority detection: Chengisfile (EDN) > YAML > server pipeline

### Plugin System

- **Protocol-based** &mdash; Extension points: `StepExecutor`, `PipelineFormat`, `Notifier`, `ArtifactHandler`, `ScmProvider`
- **Central registry** &mdash; Register/lookup/introspect plugins at runtime
- **Builtin plugins** &mdash; Shell, Docker, Git, Console, Slack, Local Artifacts, YAML Format
- **External plugins** &mdash; Load `.clj` files from plugins directory with lifecycle management

### Distributed Builds

- **Master/agent architecture** &mdash; HTTP-based with shared-secret auth
- **Label-based dispatch** &mdash; Route builds to agents matching required labels
- **Heartbeat monitoring** &mdash; Agents send periodic heartbeats; offline detection after 90s
- **Local fallback** &mdash; If no remote agent matches, build runs locally on master
- **Agent management UI** &mdash; Status badges, capacity metrics, real-time monitoring

### Security & Storage

- **Encrypted secrets** &mdash; AES-256-GCM encryption at rest, automatic log masking with `***`
- **Artifact collection** &mdash; Glob-based artifact patterns, persistent storage, download via UI
- **SQLite persistence** &mdash; Zero-config database with migration-based schema evolution (10 versions)
- **Agent auth** &mdash; Shared-secret authentication for master-agent communication
- **Input validation** &mdash; Docker command injection protection, agent registration field sanitization

### Observability

- **Live streaming** &mdash; SSE-powered real-time build output (no polling, no WebSocket)
- **Build notifications** &mdash; Console and Slack notifications with Block Kit formatting
- **Admin dashboard** &mdash; JVM stats, memory usage, executor pool status, disk usage breakdown
- **Build history** &mdash; Full log retention with stage/step breakdown and timing

### User Interface

- **Web UI** &mdash; htmx + Tailwind CSS, zero custom JavaScript, dark theme
- **CLI** &mdash; Full command-line interface for headless/scripted usage
- **Parameterized builds** &mdash; Dynamic trigger forms with text, choice, and boolean parameters
- **SCM webhooks** &mdash; GitHub/GitLab webhook endpoint for push-triggered builds
- **Agent page** &mdash; Agent status, capacity, labels, and system info

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

## CLI Reference

```
Usage: lein run -- <command> [args]

Database:
  init                          Initialize database (run migrations)

Jobs:
  job create <pipeline.clj>     Create a job from a pipeline definition file
  job list                      List all registered jobs

Builds:
  build trigger <job-name>      Trigger a new build
  build show <build-id>         Show build details
  build log <build-id>          Show build logs

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

Chengis uses sensible defaults. Override via `resources/config.edn`:

```clojure
{:database   {:path "chengis.db"}
 :workspace  {:root "workspaces"}
 :server     {:port 8080 :host "0.0.0.0"}
 :log        {:level :info}

 ;; Secrets encryption (required for secrets management)
 :secrets    {:master-key "your-32-byte-hex-key"}

 ;; Artifact storage
 :artifacts  {:root "artifacts"
              :retention-builds 10}

 ;; Notifications
 :notifications {:slack {:default-webhook "https://hooks.slack.com/..."}}

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
               :dispatch {:fallback-local true}}

 ;; Automatic cleanup
 :cleanup    {:enabled true
              :interval-hours 24
              :retention-builds 10}}
```

## Web UI Routes

| Route | Description |
|-------|-------------|
| `GET /` | Dashboard with build stats and recent activity |
| `GET /jobs` | List all registered jobs |
| `GET /jobs/:name` | Job detail with build history |
| `POST /jobs/:name/trigger` | Trigger a new build |
| `GET /builds/:id` | Build detail with stages, logs, artifacts |
| `GET /builds/:id/log` | Full build console output |
| `POST /builds/:id/cancel` | Cancel a running build |
| `POST /builds/:id/retry` | Retry a failed build |
| `GET /builds/:id/artifacts/:file` | Download a collected artifact |
| `GET /agents` | Agent management page |
| `GET /admin` | Admin dashboard (JVM stats, disk usage) |
| `POST /admin/cleanup` | Trigger workspace/artifact cleanup |
| `GET /api/builds/:id/events` | SSE stream for live build updates |
| `POST /api/webhook` | SCM webhook endpoint (GitHub/GitLab) |
| `POST /api/agents/register` | Agent registration (machine-to-machine) |
| `POST /api/agents/:id/heartbeat` | Agent heartbeat |

## Project Structure

```
chengis/
  src/chengis/
    agent/                  # Agent node (4 files)
      core.clj              # Agent entry point + HTTP server
      client.clj            # HTTP client for master communication
      heartbeat.clj         # Periodic heartbeat scheduler
      worker.clj            # Build execution on agent
    cli/                    # Command-line interface (3 files)
    db/                     # SQLite persistence layer (8 files)
    distributed/            # Distributed build coordination (3 files)
      agent_registry.clj    # In-memory agent registry
      dispatcher.clj        # Build dispatch (local vs remote)
      master_api.clj        # Master API handlers
    dsl/                    # Pipeline DSL and formats (5 files)
      core.clj              # defpipeline macro + DSL helpers
      chengisfile.clj       # EDN Pipeline as Code parser
      docker.clj            # Docker DSL helpers
      yaml.clj              # YAML workflow parser
      expressions.clj       # ${{ }} expression resolver
    engine/                 # Build execution engine (11 files)
      executor.clj          # Core pipeline runner
      build_runner.clj      # Build lifecycle + thread pool
      docker.clj            # Docker command generation
      process.clj           # Shell command execution
      git.clj               # Git clone + metadata
      artifacts.clj         # Glob-based artifact collection
      notify.clj            # Notification dispatch
      cleanup.clj           # Workspace/artifact cleanup
      events.clj            # core.async event bus
      workspace.clj         # Build workspace management
      scheduler.clj         # Cron scheduling
    model/                  # Data specs (1 file)
    plugin/                 # Plugin system (9 files)
      protocol.clj          # Plugin protocols
      registry.clj          # Central plugin registry
      loader.clj            # Plugin discovery + lifecycle
      builtin/              # 7 builtin plugins
    web/                    # HTTP server and UI (10 files)
      views/                # Hiccup view templates
        agents.clj          # Agent management page
    config.clj              # Configuration
    util.clj                # Shared utilities
    core.clj                # Entry point
  test/chengis/             # Test suite (21 files)
  resources/migrations/     # SQLite migrations (10 versions)
  pipelines/                # Example pipeline definitions (5 files)
```

**Codebase:** ~7,700 lines source + ~2,300 lines tests across 82 files

## Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Clojure 1.12 | Core language |
| Concurrency | core.async | Parallel steps, SSE event bus |
| Process execution | babashka/process | Shell command runner |
| Database | SQLite + next.jdbc + HoneySQL | Persistence |
| Migrations | Migratus | Schema evolution (10 versions) |
| Web server | http-kit | Async HTTP + SSE |
| Routing | Reitit | Ring-compatible routing |
| HTML | Hiccup 2 | Server-side rendering |
| Frontend | htmx + Tailwind CSS | Reactive UI, zero custom JS |
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
| Storage | Embedded SQLite | XML files on filesystem |
| UI rendering | Server-side (Hiccup + htmx) | Server-side (Jelly/Groovy) + client JS |
| Plugin system | Protocol-based (7 builtin plugins) | 1800+ plugins, complex classloader |
| Pipeline formats | Clojure DSL + EDN + YAML | Groovy DSL (scripted/declarative) |
| Container support | Docker steps (built-in) | Docker Pipeline plugin |
| Distributed | HTTP master/agent (built-in) | JNLP/SSH agents |
| API | Ring HTTP handlers + JSON API | REST API + Jenkins CLI |

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
- You have complex approval workflows and RBAC requirements
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

Current test suite: **100 tests, 493 assertions, 0 failures**

Test coverage spans:
- DSL parsing and pipeline construction
- Chengisfile parsing and conversion
- YAML parsing, validation, and expression resolution
- Pipeline execution engine
- Docker command generation and input validation
- Plugin registry and loader
- Distributed agent registry, dispatcher, and master API
- Post-build action handling
- Build cancellation
- Database persistence and statistics
- Web view rendering

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

Built with these excellent Clojure libraries: [core.async](https://github.com/clojure/core.async), [babashka/process](https://github.com/babashka/process), [next.jdbc](https://github.com/seancorfield/next-jdbc), [HoneySQL](https://github.com/seancorfield/honeysql), [http-kit](https://github.com/http-kit/http-kit), [Reitit](https://github.com/metosin/reitit), [Hiccup](https://github.com/weavejester/hiccup), [Timbre](https://github.com/taoensso/timbre), [Migratus](https://github.com/yogthos/migratus), [Chime](https://github.com/jarohen/chime), [clj-yaml](https://github.com/clj-commons/clj-yaml).

UI powered by [htmx](https://htmx.org/) and [Tailwind CSS](https://tailwindcss.com/).
