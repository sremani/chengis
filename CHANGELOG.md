# Changelog

All notable changes to Chengis are documented in this file.

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
- Shared-secret auth for agent↔master communication
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
| Chengis (self) | Clojure | 100 passed, 487 assertions | varies | SUCCESS |
