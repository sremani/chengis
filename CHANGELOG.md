# Changelog

All notable changes to Chengis are documented in this file.

## [Unreleased] - Security Remediation

### Batch 1: Critical + High Findings (17 remediations)

- **SQL portability** — Replaced `datetime('now')` with `CURRENT_TIMESTAMP` across all runtime queries
- **Transaction wrapping** — Wrapped multi-step store operations in transactions for atomicity
- **Rate limit hardening** — Fixed race conditions in concurrent rate limit checks
- **Account lockout** — Tightened lockout logic to prevent timing-based bypasses
- **Webhook security** — Builds inherit org-id from the matched job (prevents cross-tenant attribution)
- **SSE authorization** — SSE endpoints verify the requesting user's org owns the build
- **Alert scoping** — Alerts filtered by org-id; no cross-org build metadata exposure
- **Auth bypass** — Webhook endpoint explicitly listed in public paths when auth is enabled
- **Secret scoping** — Build secrets loaded with org-id filter to prevent cross-org leakage
- **Config hardening** — Sensitive defaults and environment variable handling tightened
- **Scheduler safety** — Cron scheduler validates org context before triggering builds
- **SCM status** — Commit status reporting scoped to org credentials
- **Approval store** — Multi-approver workflows enforce org boundaries

### Batch 1: Handler Org-Scoping (16 fixes)

- All 16 web handlers that were missing `org-id` scoping now correctly extract and propagate org context from the request

### External Review Findings (4 fixes)

- Scope escalation prevention in role updates
- OIDC state parameter bypass fix
- JWT validation tightened (clock skew, audience)
- Auth middleware ordering corrected

### Regression Tests

- Cross-org SSE denial test
- Webhook build org-attribution test
- Alerts org-scoping test
- Webhook auth-bypass test
- Cross-org build secret isolation test

### Test Suite
- 403 tests, 1781 assertions — all passing
- 60 test files across 7 test subdirectories

---

## [0.9.0] - 2026

### Multi-Tenancy & Resource Isolation

**Organization Model**
- New `organizations` table with id, name, slug, and settings
- `org_members` join table linking users to organizations with roles
- Default organization ("default-org") created on migration for backward compatibility
- All resource tables gain `org_id` column with foreign key to organizations

**Org-Scoped Stores**
- `job-store` — jobs filtered by org-id in list, get, create operations
- `build-store` — builds and build stats scoped to org
- `secret-store` — secrets isolated per org (same name allowed in different orgs)
- `template-store` — pipeline templates scoped per org
- `audit-store` — audit logs filtered by org
- `webhook-log` — webhook events scoped to org
- `approval-store` — approval gates scoped to org
- `secret-audit` — secret access logs scoped to org

**Org Context Middleware**
- `wrap-org-context` resolves org via: session cookie → user's first org membership → default-org
- All handlers receive `:org-id` in request map
- Agents inherit org context from the dispatching job

**Multi-Approver Workflows**
- Approval gates support configurable required-approvals threshold
- Multiple users can approve the same gate
- Gate proceeds when approval count meets threshold
- Concurrent approval handling with atomic operations

**Migrations 023-028**
- 023: SSO/OIDC user fields (provider, provider_id, email)
- 024: API token scopes (scopes column on api_tokens)
- 025: Organizations table and org_members
- 026: org_id columns on jobs, builds, secrets, templates, audit_logs, webhook_events, approvals
- 027: Secret backend configuration (secret_backends table)
- 028: Multi-approver fields on build_approvals

### Test Suite
- New test suites: org-store, org-isolation, multi-approver, cross-org security
- 362 tests, 1566 assertions at this phase

---

## [0.8.0] - 2026

### Enterprise Identity Foundation

**SSO/OIDC Authentication**
- OpenID Connect integration for single sign-on
- Support for Google, Okta, and generic OIDC providers
- OIDC discovery endpoint auto-configuration
- State parameter validation to prevent CSRF attacks
- Automatic user provisioning on first SSO login

**API Token Scopes**
- Tokens can be restricted to specific scopes (e.g., `build:trigger`, `job:read`)
- Scope validation middleware checks token capabilities per endpoint
- Token management UI updated to show and configure scopes

**Secret Backend System**
- `SecretBackend` protocol for pluggable secret storage
- `local-secrets` builtin: default local encrypted storage (AES-256-GCM)
- `vault-secrets` builtin: HashiCorp Vault integration (KV v2 engine)
- Config-driven backend selection via `:secrets {:backend "local"|"vault"}`

**Security Hardening (Phase 1)**
- 43 new security-focused tests
- Constant-time token comparison across all auth paths
- Session version validation on every authenticated request
- JWT audience and issuer validation
- Auth lifecycle end-to-end tests

### Test Suite
- New test suites: auth-lifecycle-e2e, auth-scopes, authorization-parity, oidc, security-concurrency, vault-secrets
- 362 tests, 1566 assertions at this phase

---

## [0.7.0] - 2026

### PostgreSQL Dual-Driver Support

**Database Abstraction**
- Config-driven database selection: `{:type "sqlite"}` (default) or `{:type "postgresql"}`
- SQLite remains zero-config default for development and small teams
- PostgreSQL for production deployments with connection pooling
- Backward-compatible API: `create-datasource` and `migrate!` accept both string paths and config maps

**HikariCP Connection Pooling**
- PostgreSQL connections managed via HikariCP with configurable pool size
- Pool defaults: 10 max connections, auto-tuned for production workloads
- Graceful shutdown via `close-datasource!` on server stop

**Portable SQL**
- `INSERT OR IGNORE` → `ON CONFLICT DO NOTHING` (works on both SQLite 3.24+ and PostgreSQL)
- `datetime('now')` → `CURRENT_TIMESTAMP` across all runtime queries
- `INTEGER PRIMARY KEY AUTOINCREMENT` → `SERIAL PRIMARY KEY` in PostgreSQL migrations
- `TEXT` timestamps → `TIMESTAMPTZ` in PostgreSQL migrations

**Separate Migration Directories**
- `resources/migrations/sqlite/` — 22 versions (moved from `resources/migrations/`)
- `resources/migrations/postgresql/` — 22 versions (new, dialect-specific DDL)
- Migratus auto-selects directory based on configured `:type`

**Conditional Backup Strategy**
- SQLite: `VACUUM INTO` (unchanged)
- PostgreSQL: `pg_dump` via shell with `PGPASSWORD` environment variable
- `backup!` auto-detects database type from datasource

**New Environment Variables**
- `CHENGIS_DATABASE_TYPE` — `"sqlite"` or `"postgresql"`
- `CHENGIS_DATABASE_HOST` — PostgreSQL host (default `localhost`)
- `CHENGIS_DATABASE_PORT` — PostgreSQL port (default `5432`)
- `CHENGIS_DATABASE_NAME` — PostgreSQL database name
- `CHENGIS_DATABASE_USER` — PostgreSQL user
- `CHENGIS_DATABASE_PASSWORD` — PostgreSQL password

**New Dependencies**
- `org.postgresql/postgresql 42.7.3`
- `hikari-cp/hikari-cp 3.1.0`

### Security Hardening (HF-01–03)
- [HF-01] Whitespace-only tokens rejected as invalid authentication
- [HF-02] Webhook saturation test: validates system handles high-volume webhook events
- [HF-03] Metrics integration test: validates Prometheus endpoint under authenticated requests

### Test Suite
- 319 tests, 1427 assertions — all passing
- New coverage: whitespace token rejection, webhook saturation, metrics integration

---

## [0.6.0] - 2026

### Deployment Readiness & Operational Tooling

**Environment Variable Configuration**
- All config overridable via `CHENGIS_*` environment variables
- Explicit mapping for 25+ env vars covering all config sections
- Automatic type coercion: `"true"`/`"false"` to boolean, numeric strings to integers
- Three-tier precedence: env vars > config.edn > defaults

**Docker Deployment**
- Multi-stage Dockerfile: Clojure builder (temurin-21-lein) + JRE runtime (temurin-21-jre)
- docker-compose.yml with master + 2 agents, persistent volume, health checks
- .dockerignore for clean builds

**Database Backup/Restore**
- Hot backup via SQLite `VACUUM INTO` (safe on running databases)
- CLI commands: `backup [output-dir]`, `restore <backup-file> [--force]`
- Admin UI "Download Backup" button with streaming file response
- Timestamped backup filenames with size metadata

**Audit Export**
- Streaming CSV and JSON export of audit logs
- Batched reads (500 per batch) for memory efficiency
- Date range and action type filters
- Admin UI export buttons with Content-Disposition headers

**Matrix Builds**
- Cartesian product stage expansion across parameter combinations
- `MATRIX_*` environment variables injected into expanded stages
- Exclude filter to remove specific combinations
- Max combinations limit (default 25) to prevent explosion
- DSL `matrix` function for Clojure pipelines
- Chengisfile EDN `:matrix` key support
- YAML `strategy.matrix` and top-level `matrix` support
- Matrix grid visualization on build detail page

### Security Fixes
- [High] SSE build event endpoints now require auth in distributed mode
- [High] Session invalidation enforced on password reset via session_version DB check
- [Medium] Webhook metrics use keyword status/provider (fixes Prometheus label fragmentation)
- [Medium] Retention metrics use keyword resource types (fixes Prometheus label fragmentation)

### Test Suite
- 283 tests, 1331 assertions — all passing
- New test suites: config, audit export, backup, matrix builds
- New security tests: SSE auth bypass, session invalidation on password reset

---

## [0.5.0] - 2026

### Enterprise Integration

**Approval Gates**
- Manual approval checkpoints in pipeline execution
- Approve/reject actions via web UI with user attribution
- Configurable timeout with auto-reject on expiry
- RBAC-gated: only developer+ roles can approve
- Approval dashboard page listing pending gates
- API endpoint for pending approval queries

**Pipeline Templates**
- Admin-defined reusable pipeline templates
- Create new jobs from templates with one click
- Template CRUD via admin UI (create, edit, delete)
- Templates stored as pipeline data in SQLite
- Migration 022: pipeline_templates table

**SCM Status Reporting**
- GitHub commit status reporting via GitHub API
- GitLab commit status reporting via GitLab API
- ScmStatusReporter protocol for extensibility
- Builtin plugins: github-status, gitlab-status

**Email Notifications**
- SMTP-based email notification plugin
- Configurable host, port, from address
- Email notifier registered as builtin plugin

**Webhook Event Logging**
- All incoming webhooks logged to database
- Provider, status, repo, branch, commit, payload size tracked
- Processing duration measured
- Admin webhook viewer page with filtering
- Migration 018: webhook_events table

**Secret Access Auditing**
- All secret reads logged with timestamp and user info
- Secret access log viewer
- Migration 019: secret_access_log table

**Account Lockout Enhancements**
- Migration 020: lockout fields on users table

### Test Suite
- New test suites: approval engine, approval store, templates, webhook log, secret audit, SCM status, email notifier

---

## [0.4.0] - 2026

### Production Hardening

**Persistent Build Queue**
- SQLite-backed build queue surviving master restarts
- Priority levels: `:high`, `:normal`, `:low`
- Queue processor with configurable concurrency
- Migration 014: build_queue table

**Circuit Breaker**
- Wraps agent HTTP calls for fault tolerance
- States: closed (normal), open (failing), half-open (probing)
- Configurable failure threshold and reset timeout
- Automatic recovery via half-open probe requests

**Orphan Build Monitor**
- Detects builds dispatched to agents that went offline
- Auto-fails orphaned builds after configurable timeout
- Periodic sweep via chime scheduler

**Artifact Transfer**
- Agent-to-master artifact upload via HTTP multipart
- Artifacts collected on agent, transferred to master storage
- API endpoint for artifact ingestion

**Alert System**
- System health alerts with severity levels
- Auto-resolve when conditions clear
- Alert API polled by htmx for real-time display
- Alert sources: queue overflow, agent offline, system health

**HTTP Request Metrics**
- Request count, duration, and status code tracking
- Prometheus histogram for response time distribution
- Per-route metrics via middleware

### Test Suite
- New test suites: build queue, queue processor, circuit breaker, orphan monitor, alerts, metrics middleware, health checks

---

## [0.3.0] - 2026

### Enterprise Security

**User Authentication**
- Login page with username/password authentication
- JWT token-based auth with configurable expiry
- Session management with secure cookies
- Auto-generated JWT secret on first startup (with warning)
- Consistent error messages to prevent user enumeration

**Role-Based Access Control (RBAC)**
- Three-tier role hierarchy: admin > developer > viewer
- `wrap-require-role` middleware for endpoint protection
- Admin: full access including user management
- Developer: trigger builds, manage secrets, approve gates
- Viewer: read-only access

**API Token Management**
- Generate personal API tokens for CI integrations
- Token revocation with immediate effect
- Bearer token authentication for API endpoints
- Token management page in user settings
- Migration 012: api_tokens table
- Migration 015: token revocation support

**Audit Logging**
- All user actions logged: login, build triggers, secret access, config changes
- Admin audit viewer with filtering by date range, action type, and user
- Structured log entries with IP address, timestamp, and detail
- Migration 013: audit_logs table

**Account Lockout**
- Configurable failed login attempt threshold
- Timed lockout duration with automatic unlock
- Admin unlock via user management page
- Integration with login flow: checked before password verification

**Rate Limiting**
- Request-level rate limiting middleware
- Configurable per-endpoint limits
- 429 Too Many Requests response with retry guidance

**Prometheus Metrics**
- `/metrics` endpoint in Prometheus exposition format
- Build, auth, webhook, and retention metrics
- Optional auth requirement for metrics endpoint
- iapetos library for clean Clojure Prometheus integration

**CSRF Protection**
- Anti-forgery tokens on all form endpoints
- API endpoints exempted (use Bearer token auth instead)
- Narrowed CSRF exemption from broad prefix to specific paths

**Password Security**
- bcrypt password hashing with adaptive cost factor
- JWT blacklist for forced logout on password change
- Session version tracking: password reset invalidates all sessions
- Migration 016: jwt_blacklist table
- Migration 017: session_version on users

### Infrastructure
- Migration 011: users table
- Seed admin user creation on first startup

### Test Suite
- New test suites: auth, user store, rate limiting, account lockout, integration
- ~200 tests at this phase

---

## [0.2.0] - 2026

### The Golden Horde Expansion

**Plugin System**
- Protocol-based extension points: StepExecutor, PipelineFormat, Notifier, ArtifactHandler, ScmProvider
- Central atom-based plugin registry with register/lookup/introspection
- Plugin loader with builtin + external plugin discovery and lifecycle
- Existing shell executor, git, slack, console, artifacts refactored into builtin plugins
- Migration 008: plugins table for tracking installed plugins

**Docker Integration**
- Steps can run inside Docker containers via `:type :docker`
- Docker command generation: `docker run`, `docker-compose run`
- Image management: pull-image!, image-exists?, ensure-image! with pull policies
- Stage-level container wrapping: all steps in a stage share a Docker image
- Pipeline-level container config propagated to stages
- DSL helpers: `docker-step`, `docker-compose-step`, `container`
- Chengisfile EDN support: `:image` key on steps, `:container` on stages
- Migration 009: container_image and container_id on build_steps

**GitHub Actions-style YAML Pipelines**
- `.chengis/workflow.yml` or `chengis.yml` auto-detected in workspace
- Full YAML format: stages, steps, parallel, container, env, conditions, post-actions, artifacts, notify
- `${{ }}` expression syntax: parameters, secrets, env variable resolution
- YAML validation with detailed error messages
- Registered as PipelineFormat plugin (multi-format pipeline detection)
- New dependency: clj-commons/clj-yaml 1.0.29

**Distributed Builds**
- HTTP-based master/agent architecture
- Agent registry with heartbeat monitoring, label matching, capacity tracking
- Build dispatcher: label-based agent selection with local fallback
- Master API: agent registration, heartbeat, event ingestion, result collection
- Agent entry point: `lein run agent --master-url URL --labels docker,linux`
- Agent worker: executes builds, streams events to master in real-time
- Periodic heartbeat via chime scheduler (offline detection after 90s)
- Agent management UI page with status badges and capacity metrics
- Shared-secret auth for agent-to-master communication
- Migration 010: agents table, agent_id/dispatched_at on builds

### Security Hardening (Code Review Refactor)
- Docker command injection protection: input validation for image names, service names, network names
- Shell quoting for all Docker command interpolated values (env vars, volumes, paths, commands)
- Agent registration field validation and sanitization (whitelist allowed keys)
- Auth check added to GET /api/agents endpoint
- CSRF exemption narrowed from broad prefix matching to specific API paths
- Race condition fixes in agent registry using atomic swap operations
- Agent heartbeat timestamps stored as strings for JSON serialization safety
- Worker thread pool made configurable and restartable
- Heartbeat scheduler stops existing schedule before starting new one
- Agent core uses exceptions instead of System/exit for testability
- YAML parser refactored: shared build-pipeline-from-data eliminates code duplication
- convert-yaml-to-pipeline now carries over all pipeline fields (env, post-actions, artifacts, etc.)
- Config EDN reader restricted with {:readers {}} for safety
- External plugin loader logs security warning when loading untrusted code

### Test Suite
- 100 tests, 493 assertions — all passing
- New test suites: plugin registry, plugin loader, Docker command generation, Docker plugin, YAML parsing, YAML expressions, agent registry, dispatcher, master API, agent worker
- Added: Docker injection protection tests, image validation tests, agent timestamp serialization tests

---

## [0.1.0] - 2025

### Round 2 Features

**Secrets Management**
- AES-256-GCM encrypted secrets stored in SQLite
- Per-job secret scoping with web UI management
- Automatic log masking: secret values replaced with `***` in stdout/stderr

**Build Artifact Storage**
- Glob-based artifact collection from build workspace
- Persistent storage with download links in the web UI
- MIME type detection for common file types
- Smart glob normalization: directory-prefixed patterns used as-is, simple patterns match anywhere

**Notification System**
- Console and Slack notification dispatch
- Slack Block Kit formatting with color-coded status
- Notification audit trail in SQLite
- Configurable per-pipeline via DSL or Chengisfile

**Parameterized Builds**
- Dynamic trigger forms with text, choice, and boolean parameter types
- htmx-loaded form UI with per-job parameter definitions
- Parameters injected as `PARAM_*` environment variables
- Conditional step execution based on parameter values

**Admin Dashboard**
- JVM metrics: heap usage, uptime, OS info
- Build executor pool stats (active threads, queue depth)
- Per-job disk usage breakdown
- One-click workspace/artifact cleanup

### Round 1 Features

**Build Cancellation**
- Cooperative cancellation with interrupt propagation
- Cancellation flag checked before each stage/step
- Process termination for long-running commands
- Build marked as `:aborted` with proper cleanup

**Post-Build Actions**
- `always`, `on-success`, `on-failure` action groups
- Post-action failures never change build status
- Available in both DSL and Chengisfile formats

**Build Retry**
- One-click retry of failed/aborted builds
- Retry links original build for traceability
- Reuses same pipeline definition and parameters

**Pipeline Visualization**
- Stage/step breakdown with timing information
- Color-coded status badges
- Build history charts on job detail pages

**Build Statistics**
- Success/failure/abort counts per job
- Average build duration tracking
- Dashboard summary with recent activity

### Core Engine

**Pipeline DSL**
- `defpipeline` macro for declarative pipeline definition
- `stage`, `step`, `parallel`, `sh` composable building blocks
- `when-branch`, `when-param` conditional execution
- Pipeline registry for named pipeline lookup

**Pipeline as Code**
- `Chengisfile` (EDN format) auto-detected in repository root
- Overrides server-side pipeline when present
- Supports stages, parallel steps, post-actions, artifacts, notifications

**Git Integration**
- Shallow clone with branch selection
- Full metadata extraction: SHA, branch, author, email, message
- Git info injected as environment variables

**Execution Engine**
- Sequential stages with early abort on failure
- Parallel or sequential steps within a stage
- 4-thread bounded executor pool
- Process timeout support with configurable per-step limits
- core.async event bus for live streaming

**Web UI**
- htmx + Tailwind CSS dark theme, zero custom JavaScript
- SSE-powered real-time build output
- Job management, build history, log viewer
- SCM webhook endpoint for GitHub/GitLab

**CLI**
- `init`, `job create/list`, `build trigger/show/log`, `status`, `serve`
- Formatted output with status indicators

**Persistence**
- SQLite with migration-based schema evolution (7 versions)
- next.jdbc + HoneySQL for type-safe queries
- Full build history with stage/step breakdown

## Build Verification

Chengis has been verified building real open-source projects:

| Project | Language | Tests | Build Time | Result |
|---------|----------|-------|------------|--------|
| JUnit5 Samples | Java (Maven) | 5 passed | 8.7s | SUCCESS |
| FluentValidation | C# (.NET 9) | 865 passed | 8.3s | SUCCESS |
| Chengis (self) | Clojure | 403 passed, 1781 assertions | varies | SUCCESS |
