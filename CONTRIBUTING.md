# Contributing to Chengis

Thank you for your interest in contributing to Chengis! This guide covers everything you need to get started with development.

## Development Setup

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 21+ | Runtime (required for `--enable-native-access`) |
| Leiningen | 2.9+ | Clojure build tool |
| Git | 2.x | Source control + build integration |
| SQLite | 3.x | Ships with the JDBC driver, no install needed (default) |
| PostgreSQL | 14+ | Optional — for production deployments (configure via `CHENGIS_DATABASE_TYPE=postgresql`) |

Optional (for running example pipelines):
- Maven 3.x (for Java demo pipeline)
- .NET SDK 9.x (for C# demo pipeline)

### Getting Started

```bash
# Clone the repository
git clone https://github.com/sremani/chengis.git
cd chengis

# Download dependencies (first run takes a minute)
lein deps

# Initialize the database
lein run -- init

# Run the test suite
lein test

# Start the web server
lein run serve
```

### IDE Setup

**Cursive (IntelliJ):**
1. Open the project directory
2. Leiningen will be auto-detected
3. Start a REPL: Run > Edit Configurations > Clojure REPL > Local

**Emacs (CIDER):**
1. Open `project.clj`
2. `M-x cider-jack-in`

**VS Code (Calva):**
1. Open the project directory
2. Ctrl+Shift+P > Calva: Start a Project REPL

## Project Layout

```
src/chengis/
  core.clj              # Entry point (-main)
  config.clj            # Configuration loading + env var overrides
  logging.clj           # Structured logging setup
  metrics.clj           # Prometheus metrics registry
  util.clj              # Shared utilities

  cli/                  # Command-line interface
    core.clj            # CLI dispatcher
    commands.clj        # Job, build, secret, pipeline, backup commands
    output.clj          # Formatted output helpers

  db/                   # Database layer (16 files)
    connection.clj      # SQLite + PostgreSQL connection pool (HikariCP)
    migrate.clj         # Migratus migration runner
    job_store.clj       # Job CRUD
    build_store.clj     # Build + stage + step CRUD
    secret_store.clj    # Encrypted secrets
    artifact_store.clj  # Artifact metadata
    notification_store.clj  # Notification events
    user_store.clj      # User accounts, API tokens, password hashing
    org_store.clj       # Organization CRUD + membership
    audit_store.clj     # Audit log queries with filtering
    audit_export.clj    # CSV/JSON audit export (streaming)
    webhook_log.clj     # Webhook event logging
    secret_audit.clj    # Secret access audit trail
    approval_store.clj  # Approval gate records
    template_store.clj  # Pipeline template CRUD
    backup.clj          # Database backup (VACUUM INTO) + restore

  dsl/                  # Pipeline definition (6 files)
    core.clj            # defpipeline macro + DSL functions (matrix, post, etc.)
    chengisfile.clj     # Chengisfile (EDN) parser
    docker.clj          # Docker DSL helpers (docker-step, container)
    yaml.clj            # YAML workflow parser
    expressions.clj     # ${{ }} expression resolver
    templates.clj       # Pipeline template DSL

  engine/               # Build execution (16 files)
    executor.clj        # Core pipeline runner
    build_runner.clj    # Build lifecycle + thread pool
    process.clj         # Shell command execution
    git.clj             # Git clone + metadata extraction
    workspace.clj       # Build workspace management
    artifacts.clj       # Glob-based artifact collection
    notify.clj          # Notification dispatch
    events.clj          # core.async event bus
    scheduler.clj       # Cron-based scheduling
    cleanup.clj         # Workspace/artifact cleanup
    log_masker.clj      # Secret masking in output
    docker.clj          # Docker command generation + validation
    matrix.clj          # Matrix build expansion (cartesian product)
    retention.clj       # Data retention scheduler
    approval.clj        # Approval gate engine
    scm_status.clj      # SCM commit status reporting

  model/
    spec.clj            # Clojure specs for validation

  plugin/               # Plugin system (15 files)
    protocol.clj        # Plugin protocols (7: StepExecutor, PipelineFormat,
                        #   Notifier, ArtifactHandler, ScmProvider,
                        #   ScmStatusReporter, SecretBackend)
    registry.clj        # Central plugin registry (atom-based)
    loader.clj          # Plugin discovery + lifecycle
    builtin/            # 12 builtin plugins
      shell.clj         # Shell step executor
      docker.clj        # Docker step executor
      docker_compose.clj # Docker Compose step executor
      console_notifier.clj # Console notifier
      slack_notifier.clj # Slack notifier (Block Kit)
      email_notifier.clj # Email notifier (SMTP)
      git_scm.clj       # Git SCM provider
      local_artifacts.clj # Local artifact handler
      local_secrets.clj # Local secret backend (default)
      vault_secrets.clj # HashiCorp Vault secret backend
      yaml_format.clj   # YAML pipeline format
      github_status.clj # GitHub commit status reporter
      gitlab_status.clj # GitLab commit status reporter

  agent/                # Agent node (5 files)
    core.clj            # Agent entry point + HTTP server
    artifact_uploader.clj # Artifact upload to master
    client.clj          # HTTP client for master communication
    heartbeat.clj       # Periodic heartbeat scheduler
    worker.clj          # Build execution on agent

  distributed/          # Distributed build coordination (8 files)
    agent_registry.clj  # In-memory agent registry
    dispatcher.clj      # Build dispatch (local vs remote)
    master_api.clj      # Master API handlers
    build_queue.clj     # Persistent build queue (database-backed)
    queue_processor.clj # Queue processing worker
    circuit_breaker.clj # Agent communication circuit breaker
    orphan_monitor.clj  # Orphaned build detection
    artifact_transfer.clj # Agent-to-master artifact upload

  web/                  # HTTP layer (26 files total)
    server.clj          # http-kit server startup
    routes.clj          # Reitit routes + middleware
    handlers.clj        # Request handlers
    sse.clj             # Server-Sent Events
    webhook.clj         # SCM webhook handler
    auth.clj            # JWT/session/API token auth + RBAC
    audit.clj           # Audit logging middleware
    alerts.clj          # System alert management
    rate_limit.clj      # Request rate limiting
    account_lockout.clj # Account lockout logic
    metrics_middleware.clj # HTTP request metrics
    views/              # Hiccup view templates (15 files)
      layout.clj        # Base HTML layout
      dashboard.clj     # Home page
      jobs.clj          # Job list + detail
      builds.clj        # Build detail + logs + matrix grid
      components.clj    # Reusable UI components
      admin.clj         # Admin dashboard
      trigger_form.clj  # Parameterized build form
      agents.clj        # Agent management page
      login.clj         # Login page
      users.clj         # User management page
      tokens.clj        # API token management
      audit.clj         # Audit log viewer
      approvals.clj     # Approval gates page
      templates.clj     # Pipeline template management
      webhooks.clj      # Webhook event viewer

test/chengis/           # Test suite (60 files, mirrors src/ structure)
resources/migrations/   # SQL migration files (28 versions × 2 drivers)
pipelines/              # Example pipeline definitions (5 files)
benchmarks/             # Performance benchmark suite
```

## Running Tests

```bash
# Full test suite
lein test

# Single namespace
lein test chengis.engine.executor-test

# With test.check generative tests
lein test chengis.dsl.chengisfile-test
```

The test suite currently has **403 tests with 1781 assertions**. All tests must pass before submitting a PR.

### Test Organization

Tests mirror the source layout:

| Source | Test |
|--------|------|
| `dsl/core.clj` | `dsl/core_test.clj` |
| `dsl/chengisfile.clj` | `dsl/chengisfile_test.clj` |
| `dsl/yaml.clj` | `dsl/yaml_test.clj` |
| `engine/executor.clj` | `engine/executor_test.clj` |
| `engine/process.clj` | `engine/process_test.clj` |
| `engine/matrix.clj` | `engine/matrix_test.clj` |
| `engine/approval.clj` | `engine/approval_test.clj`, `engine/approval_concurrency_test.clj` |
| `engine/docker.clj` | `engine/docker_test.clj` |
| `engine/scm_status.clj` | `engine/scm_status_test.clj` |
| `db/build_store.clj` | `db/build_store_test.clj`, `db/build_stats_test.clj` |
| `db/user_store.clj` | `db/user_store_test.clj` |
| `db/org_store.clj` | `db/org_store_test.clj`, `db/org_isolation_test.clj` |
| `db/template_store.clj` | `db/template_store_test.clj` |
| `db/approval_store.clj` | `db/approval_store_test.clj`, `db/multi_approver_test.clj` |
| `web/auth.clj` | `web/auth_test.clj`, `web/auth_scopes_test.clj`, `web/oidc_test.clj` |
| `web/auth.clj` (e2e) | `web/auth_lifecycle_e2e_test.clj` |
| `web/rate_limit.clj` | `web/rate_limit_test.clj` |
| `web/alerts.clj` | `web/alerts_test.clj` |
| `web/handlers.clj` | `web/integration_test.clj`, `web/cross_org_security_test.clj` |
| `plugin/builtin/vault_secrets.clj` | `plugin/vault_secrets_test.clj` |
| `distributed/circuit_breaker.clj` | `distributed/circuit_breaker_test.clj` |
| `distributed/build_queue.clj` | `distributed/build_queue_test.clj` |
| `distributed/dispatcher.clj` | `distributed/dispatcher_test.clj` |
| `web/views/*.clj` | `web/views_test.clj` |

### Writing Tests

Tests use `clojure.test`. Test helpers are in `test/chengis/test_helpers.clj`:

```clojure
(ns chengis.engine.my-feature-test
  (:require [clojure.test :refer :all]
            [chengis.test-helpers :as th]))

(deftest my-feature-test
  (testing "basic behavior"
    (is (= expected actual))))
```

## Database Migrations

Chengis uses [Migratus](https://github.com/yogthos/migratus) for schema evolution.

### Creating a New Migration

1. Create up and down SQL files in both migration directories:

```
resources/migrations/sqlite/
  029-my-feature.up.sql
  029-my-feature.down.sql
resources/migrations/postgresql/
  029-my-feature.up.sql
  029-my-feature.down.sql
```

2. Write the SQL (SQLite example):

```sql
-- 029-my-feature.up.sql
CREATE TABLE IF NOT EXISTS my_table (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);

-- 029-my-feature.down.sql
DROP TABLE IF EXISTS my_table;
```

For PostgreSQL, use `TIMESTAMPTZ` instead of `TEXT` for timestamps and `SERIAL` for auto-incrementing IDs.

3. Run migrations:

```bash
lein run -- init
```

### Migration Conventions

- Use `IF NOT EXISTS` / `IF EXISTS` for idempotency
- Use `TEXT` for timestamps in SQLite migrations (`TIMESTAMPTZ` in PostgreSQL migrations)
- Use `TEXT` for IDs (UUIDs stored as strings)
- Always provide both up and down migrations

## Adding a New Feature

### Example: Adding a New Store

1. **Migration**: Create `resources/migrations/sqlite/029-widgets.up.sql` (and `postgresql/` equivalent)
2. **Store**: Create `src/chengis/db/widget_store.clj`
3. **Engine integration**: Update `executor.clj` or create a new engine module
4. **Web handler**: Add handler in `handlers.clj`
5. **Route**: Add route in `routes.clj`
6. **View**: Create or update view in `web/views/`
7. **Tests**: Add tests mirroring the source structure

### Example: Adding a New DSL Function

1. Add the function to `dsl/core.clj`
2. Update `build-pipeline` to handle the new map type
3. If it appears in Chengisfiles, update `dsl/chengisfile.clj`
4. If it appears in YAML, update `dsl/yaml.clj`
5. Add tests in `dsl/core_test.clj`

### Example: Adding a New Plugin

1. Define a new protocol in `plugin/protocol.clj` (or reuse an existing one)
2. Create `plugin/builtin/my_plugin.clj` implementing the protocol
3. Register in `plugin/loader.clj` (add to `load-builtins!`)
4. Add tests in `plugin/my_plugin_test.clj`

## Code Style

### Naming

- **Namespaces**: `chengis.module.sub-module` (kebab-case)
- **Files**: `sub_module.clj` (underscores, Clojure convention)
- **Functions**: `kebab-case` (e.g., `save-build!`, `run-step`)
- **Side-effecting functions**: Append `!` (e.g., `save-notification!`, `cancel-build!`)
- **Private functions**: Use `defn-` for implementation details
- **Constants**: `kebab-case` (e.g., `default-config`)

### Patterns

- **Stores**: Functions take `ds` (datasource) as first argument
- **Handlers**: Closures over `system` map: `(defn my-handler [system] (fn [request] ...))`
- **Views**: Pure functions returning Hiccup vectors
- **Engine**: Functions take `build-ctx` map threaded through execution
- **Middleware**: Ring middleware functions: `(defn wrap-something [handler system] (fn [req] ...))`
- **Plugins**: Implement protocol, register via `plugin/registry.clj`

### Dependencies

- Prefer existing dependencies over adding new ones
- The project intentionally has a minimal dependency footprint (~20 libraries)
- If you must add a dependency, justify it in the PR description

## Common Development Tasks

### Start a REPL with the Web Server

```clojure
(require '[chengis.web.server :as server])
(require '[chengis.config :as config])
(require '[chengis.db.connection :as conn])
(require '[chengis.db.migrate :as migrate])

(def cfg (config/load-config))
(def ds (conn/datasource (:database cfg)))
(migrate/migrate! ds)
(def stop-fn (server/start! cfg ds))

;; When done:
(stop-fn)
```

### Inspect the Database

```bash
sqlite3 chengis.db
.tables
.schema builds
SELECT id, build_number, status FROM builds ORDER BY started_at DESC LIMIT 10;
```

### Test a Pipeline Without Creating a Job

```clojure
(require '[chengis.dsl.core :refer :all])
(require '[chengis.engine.executor :as executor])

(defpipeline test-pipe
  (stage "Test"
    (step "Echo" (sh "echo hello"))))

;; Execute directly (needs a system map with datasource)
```

## Pull Request Guidelines

1. **One feature per PR** &mdash; Keep changes focused
2. **All tests pass** &mdash; Run `lein test` before submitting
3. **Include tests** &mdash; New features should have test coverage
4. **Update docs** &mdash; If changing the DSL, CLI, or config, update README.md
5. **Descriptive commit messages** &mdash; Explain the "why", not just the "what"

## Architecture Decisions

Key design choices and their rationale:

| Decision | Rationale |
|----------|-----------|
| SQLite default + PostgreSQL option | Zero setup for dev (SQLite), production-grade scaling with PostgreSQL via config switch |
| htmx over React/ClojureScript | No build step, no JS bundler, simpler stack |
| SSE over WebSocket | Simpler protocol, sufficient for unidirectional streaming |
| core.async over Java threads | Lightweight channels, pub/sub for event distribution |
| babashka/process over Runtime.exec | Better API, stream handling, timeout support |
| Hiccup over Selmer | Clojure data structures for templates, compile-time safety |
| EDN over YAML for Chengisfile | Native Clojure format, no parser dependency |
| buddy-sign over manual JWT | Standard library, proven security, well-maintained |
| bcrypt over SHA-256 | Adaptive hashing, industry standard for passwords |
| iapetos over raw Prometheus | Clean Clojure wrapper for Prometheus metrics |

For a detailed architecture deep-dive, see [ARCHITECTURE.md](ARCHITECTURE.md).
