<p align="center">
  <h1 align="center">Chengis</h1>
  <p align="center">
    <strong>A CI/CD engine written in Clojure</strong>
  </p>
  <p align="center">
    Declarative pipelines &bull; Live build streaming &bull; Zero JavaScript &bull; Single binary
  </p>
</p>

---

Chengis is a lightweight, self-contained CI/CD system inspired by Jenkins but built from scratch in Clojure. It features a powerful DSL for defining build pipelines, a real-time web UI powered by htmx and Server-Sent Events, and ships as a single JAR file backed by SQLite.

**52 tests | 301 assertions | 0 failures**

## Why Chengis?

| | Chengis | Jenkins |
|---|---|---|
| **Setup** | `lein run -- init && lein run serve` | Download WAR, configure JVM, set up reverse proxy, install plugins |
| **Configuration** | Clojure DSL or EDN Chengisfile | Groovy/Declarative Jenkinsfile + XML job configs |
| **Dependencies** | Single JAR + SQLite (zero external services) | Java + servlet container + plugin ecosystem |
| **Disk footprint** | ~30 MB uberjar | 400+ MB with common plugins |
| **Live updates** | Built-in SSE streaming, zero JS | Requires page refresh or Blue Ocean plugin |
| **Secrets** | AES-256-GCM encrypted at rest in SQLite | Credentials plugin + separate key store |
| **Pipeline as Code** | Drop a `Chengisfile` in your repo (EDN) | Jenkinsfile (Groovy DSL) |
| **Resource usage** | ~100 MB heap for typical workloads | 1-4 GB heap recommended |
| **Extensibility** | It's just Clojure | Plugin system (1800+ plugins) |

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

### Core

- **Pipeline DSL** &mdash; Define pipelines in pure Clojure with `defpipeline`, `stage`, `step`, `parallel`, `sh`
- **Pipeline as Code** &mdash; Drop a `Chengisfile` (EDN format) in your repo root; Chengis auto-detects it
- **Git integration** &mdash; Clone any Git repo, extract commit metadata (SHA, branch, author, message)
- **Parallel execution** &mdash; Steps within a stage can run concurrently via `core.async`
- **Post-build actions** &mdash; `always`, `on-success`, `on-failure` hooks that never affect build status
- **Conditional execution** &mdash; `when-branch` and `when-param` for environment-aware pipelines
- **Build cancellation** &mdash; Graceful cancellation with interrupt propagation
- **Build retry** &mdash; One-click retry of failed builds

### Security & Storage

- **Encrypted secrets** &mdash; AES-256-GCM encryption at rest, automatic log masking with `***`
- **Artifact collection** &mdash; Glob-based artifact patterns, persistent storage, download via UI
- **SQLite persistence** &mdash; Zero-config database with migration-based schema evolution

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

## Quick Start

### Prerequisites

- **Java 21+** (for `--enable-native-access`)
- **Leiningen** (Clojure build tool)
- **Git** (for source checkout)

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

## Pipeline DSL Reference

### Basic Pipeline

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
    (step "Unit" (sh "make test"))))
```

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

### Artifacts

```clojure
;; Collect files matching glob patterns after build
(artifacts "target/*.jar"
           "test-reports/*.xml"
           "coverage/index.html")
```

### Notifications

```clojure
(notify :console {})
(notify :slack {:webhook-url "https://hooks.slack.com/services/..."
                :channel "#builds"})
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
                 :default false}
                {:name "version"
                 :type :text
                 :default "latest"}]}
  ...)
```

Parameters are injected as environment variables: `PARAM_ENVIRONMENT`, `PARAM_SKIP_TESTS`, `PARAM_VERSION`.

### Shell Command Options

```clojure
(sh "make build"
  :env {"NODE_ENV" "production" "CI" "true"}  ; extra env vars
  :dir "frontend"                              ; working directory
  :timeout 300000)                             ; 5 minute timeout
```

## Pipeline as Code (Chengisfile)

Drop a file named `Chengisfile` in your repository root:

```clojure
{:description "My application CI"
 :stages [{:name "Build"
           :steps [{:name "Compile" :run "make build"}]}
          {:name "Test"
           :parallel true
           :steps [{:name "Unit Tests" :run "make test"}
                   {:name "Lint" :run "make lint"}]}]
 :post {:always [{:name "Cleanup" :run "rm -rf tmp/"}]
        :on-success [{:name "Notify" :run "echo 'Build succeeded!'"}]
        :on-failure [{:name "Alert" :run "echo 'Build FAILED!'"}]}}
```

When Chengis clones a repository, it checks for a `Chengisfile` at the root. If found, it overrides the server-side pipeline definition (similar to how Jenkins uses a `Jenkinsfile`).

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

 ;; Automatic cleanup
 :cleanup    {:enabled true
              :interval-hours 24
              :retention-builds 10}

 ;; Cron scheduler
 :scheduler  {:enabled false}}
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
| `GET /admin` | Admin dashboard (JVM stats, disk usage) |
| `POST /admin/cleanup` | Trigger workspace/artifact cleanup |
| `GET /api/builds/:id/events` | SSE stream for live build updates |
| `POST /api/webhook` | SCM webhook endpoint (GitHub/GitLab) |

## Project Structure

```
chengis/
  src/chengis/
    cli/                    # Command-line interface (3 files)
    db/                     # SQLite persistence layer (8 files)
    dsl/                    # Pipeline DSL and Chengisfile parser (2 files)
    engine/                 # Build execution engine (10 files)
      executor.clj          # Core pipeline runner
      build_runner.clj      # Build lifecycle + thread pool
      process.clj           # Shell command execution
      git.clj               # Git clone + metadata
      artifacts.clj         # Glob-based artifact collection
      notify.clj            # Notification dispatch
      cleanup.clj           # Workspace/artifact cleanup
      events.clj            # core.async event bus
      workspace.clj         # Build workspace management
      scheduler.clj         # Cron scheduling
    model/                  # Data specs (1 file)
    web/                    # HTTP server and UI (9 files)
      views/                # Hiccup view templates
    config.clj              # Configuration
    util.clj                # Shared utilities
    core.clj                # Entry point
  test/chengis/             # Test suite (11 files)
  resources/migrations/     # SQLite migrations (7 versions)
  pipelines/                # Example pipeline definitions (5 files)
  Chengisfile.example       # Example Pipeline as Code file
```

**Codebase:** ~4,500 lines source + ~1,300 lines tests across 50 files

## Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Language | Clojure 1.12 | Core language |
| Concurrency | core.async | Parallel steps, SSE event bus |
| Process execution | babashka/process | Shell command runner |
| Database | SQLite + next.jdbc + HoneySQL | Persistence |
| Migrations | Migratus | Schema evolution |
| Web server | http-kit | Async HTTP + SSE |
| Routing | Reitit | Ring-compatible routing |
| HTML | Hiccup 2 | Server-side rendering |
| Frontend | htmx + Tailwind CSS | Reactive UI, zero custom JS |
| Scheduling | Chime | Cron-based triggers |
| Logging | Timbre | Structured logging |
| Encryption | javax.crypto (AES-256-GCM) | Secrets at rest |
| Build tool | Leiningen | Project management |

## Chengis vs Jenkins: Detailed Comparison

### Architecture

| Aspect | Chengis | Jenkins |
|--------|---------|---------|
| Runtime | Single JVM process | Master + optional agent nodes |
| Storage | Embedded SQLite | XML files on filesystem |
| UI rendering | Server-side (Hiccup + htmx) | Server-side (Jelly/Groovy) + client JS |
| Plugin system | None (extend via Clojure) | 1800+ plugins, complex classloader |
| Configuration | EDN files | XML + Groovy + UI forms |
| API | Ring HTTP handlers | REST API + Jenkins CLI |

### Pipeline Definition

**Chengis DSL:**
```clojure
(defpipeline my-app
  {:description "Build and test"}
  (stage "Build"
    (step "Compile" (sh "make build")))
  (stage "Test"
    (parallel
      (step "Unit" (sh "make test"))
      (step "Lint" (sh "make lint")))))
```

**Jenkins Declarative Pipeline:**
```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                sh 'make build'
            }
        }
        stage('Test') {
            parallel {
                stage('Unit') {
                    steps { sh 'make test' }
                }
                stage('Lint') {
                    steps { sh 'make lint' }
                }
            }
        }
    }
}
```

### When to Choose Chengis

- You want CI/CD running in minutes, not hours
- Your team is small to medium (1-20 developers)
- You value simplicity over feature breadth
- You're comfortable with Clojure or want a Clojure-native tool
- You need a self-contained system with no external dependencies
- You want to understand and modify your CI/CD engine

### When to Choose Jenkins

- You need distributed builds across many machines
- You require specific integrations (LDAP, Artifactory, Kubernetes, etc.)
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

Current test suite: **52 tests, 301 assertions, 0 failures**

Test coverage spans:
- DSL parsing and pipeline construction
- Chengisfile parsing and conversion
- Pipeline execution engine
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

Built with these excellent Clojure libraries: [core.async](https://github.com/clojure/core.async), [babashka/process](https://github.com/babashka/process), [next.jdbc](https://github.com/seancorfield/next-jdbc), [HoneySQL](https://github.com/seancorfield/honeysql), [http-kit](https://github.com/http-kit/http-kit), [Reitit](https://github.com/metosin/reitit), [Hiccup](https://github.com/weavejester/hiccup), [Timbre](https://github.com/taoensso/timbre), [Migratus](https://github.com/yogthos/migratus), [Chime](https://github.com/jarohen/chime).

UI powered by [htmx](https://htmx.org/) and [Tailwind CSS](https://tailwindcss.com/).
