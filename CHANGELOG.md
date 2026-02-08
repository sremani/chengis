# Changelog

All notable changes to Chengis are documented in this file.

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
| Chengis (self) | Clojure | 283 passed, 1331 assertions | varies | SUCCESS |
