# Architecture

This document describes the internal architecture of Chengis, a CI/CD engine written in Clojure.

## System Overview

```
                                    +------------------+
                                    |   Web Browser    |
                                    |  (htmx client)   |
                                    +--------+---------+
                                             |
                                      HTTP / SSE
                                             |
                                    +--------+---------+
                                    |    http-kit      |
                                    |   Web Server     |
                                    +--------+---------+
                                             |
                    +------------------------+------------------------+
                    |                        |                        |
             +------+------+         +------+------+         +------+------+
             |   Reitit    |         |    SSE      |         |  Master API |
             |   Router    |         |  Streaming  |         | (Distributed|
             +------+------+         +------+------+         +------+------+
                    |                       |                        |
             +------+------+        +------+------+         +------+------+
             |  Handlers   |        | core.async  |         |   Agent     |
             | (Ring fns)  |        |  Event Bus  |         |  Registry   |
             +------+------+        +------+------+         +------+------+
                    |                       ^                        |
          +---------+---------+             |                +------+------+
          |                   |             |                | Dispatcher  |
   +------+------+    +------+------+  +---+--------+       +------+------+
   |   Hiccup    |    |    Build    |  |  Executor  |              |
   |    Views    |    |   Runner    +->+   Engine   |      +------+------+
   +-------------+    +------+------+  +------+-----+      | Remote Agent|
                             |                |             |   (HTTP)    |
                      +------+------+  +------+------+     +-------------+
                      |  Database   |  | babashka/   |
                      | SQLite / PG |  |  process    |
                      | (next.jdbc  |  +------+------+
                      |  + HikariCP)|
                      +-------------+
                                              |
                                       +------+------+
                                       |   Docker    |
                                       | (optional)  |
                                       +-------------+
```

## Module Architecture

### Layer Diagram

```
+---------------------------------------------------------------+
|                        Entry Points                           |
|   core.clj (main)    cli/core.clj     web/server.clj         |
|                      agent/core.clj                           |
+---------------------------------------------------------------+
|                        Web Layer                              |
|   routes.clj   handlers.clj   sse.clj   webhook.clj          |
|   views/  (layout, dashboard, jobs, builds, admin,            |
|            trigger, agents, login, users, tokens, audit,      |
|            approvals, templates, webhooks, compliance,         |
|            policies, plugin_policies, docker_policies,         |
|            traces, analytics, notifications, cost,             |
|            flaky_tests, pr_checks, cron, webhook_replay,      |
|            dependencies, supply_chain, regulatory, signatures) |
|   distributed/master_api.clj                                  |
+---------------------------------------------------------------+
|                    Auth & Security Layer                       |
|   auth.clj          rate_limit.clj     account_lockout.clj    |
|   audit.clj         alerts.clj         metrics_middleware.clj |
+---------------------------------------------------------------+
|                        Engine Layer                            |
|   build_runner.clj   executor.clj   process.clj              |
|   git.clj   workspace.clj   artifacts.clj   notify.clj       |
|   events.clj   scheduler.clj   cleanup.clj   log_masker.clj  |
|   docker.clj   matrix.clj   retention.clj   approval.clj     |
|   scm_status.clj   compliance.clj   policy.clj               |
|   dag.clj   cache.clj   stage_cache.clj   artifact_delta.clj |
|   tracing.clj   analytics.clj   log_context.clj              |
|   cost.clj   test_parser.clj   pr_checks.clj                 |
|   branch_overrides.clj   monorepo.clj   build_deps.clj       |
|   cron.clj   webhook_replay.clj   auto_merge.clj             |
|   provenance.clj   sbom.clj   vulnerability_scanner.clj      |
|   opa.clj   license_scanner.clj   signing.clj                |
|   regulatory.clj                                              |
+---------------------------------------------------------------+
|                    Supply Chain Security Layer                 |
|   engine/provenance.clj   engine/sbom.clj                    |
|   engine/vulnerability_scanner.clj   engine/opa.clj           |
|   engine/license_scanner.clj   engine/signing.clj             |
|   engine/regulatory.clj                                       |
|   db/provenance_store.clj   db/sbom_store.clj                |
|   db/scan_store.clj   db/opa_store.clj                       |
|   db/license_store.clj   db/signature_store.clj              |
|   db/regulatory_store.clj                                     |
+---------------------------------------------------------------+
|                    Metrics & Observability Layer               |
|   metrics.clj   logging.clj   tracing.clj   analytics.clj   |
+---------------------------------------------------------------+
|                        Plugin Layer                           |
|   plugin/protocol.clj   plugin/registry.clj                  |
|   plugin/loader.clj                                           |
|   builtin/  (shell, docker, docker-compose, console, slack,   |
|              email, git, local-artifacts, local-secrets,       |
|              vault-secrets, yaml-format, github-status,        |
|              gitlab-status, gitea-status, bitbucket-status)    |
+---------------------------------------------------------------+
|                        DSL Layer                              |
|   dsl/core.clj (defpipeline macro)                            |
|   dsl/chengisfile.clj (Pipeline as Code — EDN)                |
|   dsl/yaml.clj (Pipeline as Code — YAML)                     |
|   dsl/expressions.clj (${{ }} resolver)                       |
|   dsl/docker.clj (Docker DSL helpers)                         |
|   dsl/templates.clj (Pipeline templates)                      |
+---------------------------------------------------------------+
|                        Agent Layer                            |
|   agent/core.clj     agent/worker.clj                        |
|   agent/client.clj   agent/heartbeat.clj                     |
|   agent/artifact_uploader.clj                                 |
+---------------------------------------------------------------+
|                        Distributed Layer                     |
|   distributed/agent_registry.clj                              |
|   distributed/dispatcher.clj                                  |
|   distributed/master_api.clj                                  |
|   distributed/build_queue.clj                                 |
|   distributed/queue_processor.clj                             |
|   distributed/circuit_breaker.clj                             |
|   distributed/orphan_monitor.clj                              |
|   distributed/artifact_transfer.clj                           |
|   distributed/leader_election.clj                             |
+---------------------------------------------------------------+
|                        Data Layer                             |
|   db/connection.clj   db/migrate.clj                          |
|   db/job_store.clj    db/build_store.clj                      |
|   db/build_event_store.clj                                    |
|   db/secret_store.clj db/artifact_store.clj                   |
|   db/notification_store.clj  db/user_store.clj                |
|   db/org_store.clj                                             |
|   db/audit_store.clj  db/audit_export.clj                     |
|   db/webhook_log.clj  db/secret_audit.clj                     |
|   db/approval_store.clj  db/template_store.clj                |
|   db/policy_store.clj  db/compliance_store.clj                |
|   db/plugin_policy_store.clj  db/docker_policy_store.clj      |
|   db/agent_store.clj   db/cache_store.clj                     |
|   db/analytics_store.clj  db/trace_store.clj                  |
|   db/cost_store.clj  db/test_result_store.clj                 |
|   db/pr_check_store.clj  db/dependency_store.clj              |
|   db/cron_store.clj                                           |
|   db/provenance_store.clj  db/sbom_store.clj                 |
|   db/scan_store.clj  db/opa_store.clj                        |
|   db/license_store.clj  db/signature_store.clj               |
|   db/regulatory_store.clj                                     |
|   db/backup.clj                                               |
+---------------------------------------------------------------+
|                        Foundation                             |
|   config.clj   util.clj   model/spec.clj                     |
+---------------------------------------------------------------+
```

## Build Execution Flow

A build goes through these phases:

```
Trigger (CLI/Web/Webhook/Cron)
  |
  v
Build Runner
  |-- Creates build record in DB (status: :running)
  |-- Submits to 4-thread executor pool
  |-- If distributed: dispatches to remote agent (or fallback local)
  |
  v
Executor: Workspace Setup
  |-- Creates isolated workspace directory
  |-- Emits :build-started event
  |
  v
Executor: Git Phase (optional)
  |-- git clone --depth N --branch <branch> <url>
  |-- Extracts metadata: SHA, short SHA, branch, author, email, message
  |-- Sets GIT_COMMIT, GIT_BRANCH, GIT_AUTHOR, etc. as env vars
  |
  v
Executor: Pipeline Detection (multi-format, priority order)
  |-- 1. Check for Chengisfile in workspace root (EDN)
  |-- 2. Check for YAML workflow (.chengis/workflow.yml, chengis.yml)
  |-- 3. Fall back to server-side pipeline definition
  |-- Resolve ${{ }} expressions (YAML only)
  |
  v
Executor: Secret Injection
  |-- Loads encrypted secrets from DB for this job
  |-- Decrypts with AES-256-GCM using master key
  |-- Adds to step environment variables
  |-- Registers values with log masker (replaced with ***)
  |
  v
Executor: Container Propagation
  |-- Pipeline-level :container config → propagated to stages
  |-- Stage-level :container config → shell steps converted to :docker
  |
  v
Executor: Matrix Expansion
  |-- If :matrix config present, expand stages × combinations
  |-- Stage "Build" × {os: [linux, macos]} → "Build [os=linux]", "Build [os=macos]"
  |-- Each expanded stage gets MATRIX_* env vars injected into steps
  |-- Max combinations enforced (default 25) to prevent explosion
  |
  v
Executor: Build Deduplication (if enabled)
  |-- Compute commit fingerprint
  |-- Check for recent successful/running build on same commit
  |-- If match found within dedup window: return existing build
  |
  v
Executor: Stage Execution (sequential or DAG-parallel)
  |
  |  Mode selection:
  |    |-- Any stage has :depends-on? → DAG mode (parallel)
  |    |-- Otherwise → sequential mode (default)
  |
  |  DAG mode:
  |    |-- Build dependency graph from :depends-on declarations
  |    |-- Topological sort (Kahn's algorithm, detect cycles)
  |    |-- Launch ready stages concurrently (bounded semaphore)
  |    |-- Collect results, fail downstream dependents on failure
  |
  |  For each stage (sequential or when ready in DAG):
  |    |-- Check cancellation flag
  |    |-- Check build result cache (stage fingerprint)
  |    |    |-- SHA-256(git-commit | stage-name | commands | stable-env)
  |    |    |-- If cache hit: emit :stage-cached, skip execution
  |    |-- Check policy engine (if configured)
  |    |-- Check approval gate (if configured)
  |    |    |-- Create approval record in DB
  |    |    |-- Emit :approval-required event (shown in UI)
  |    |    |-- Wait for approve/reject/timeout
  |    |    |-- If rejected or timed out: mark build as failed
  |    |-- Restore artifact/dependency cache (if :cache declared)
  |    |    |-- Resolve {{ hashFiles('...') }} for cache key
  |    |    |-- Try exact key match, then restore-keys prefix match
  |    |-- Emit :stage-started event
  |    |
  |    |  For each step (sequential or parallel):
  |    |    |-- Evaluate conditions (branch, param)
  |    |    |-- Look up StepExecutor plugin by :type
  |    |    |    :shell → ShellExecutor (babashka/process)
  |    |    |    :docker → DockerExecutor (docker run + cache volumes)
  |    |    |    :docker-compose → DockerComposeExecutor
  |    |    |-- Execute step, capture stdout/stderr (masked)
  |    |    |-- Emit :step-completed event
  |    |    |-- Record result in DB
  |    |
  |    |-- Save artifact/dependency cache (on success)
  |    |-- Save build result cache (stage fingerprint → result)
  |    |-- Emit :stage-completed event
  |    |-- If stage fails: skip remaining stages (or cancel dependents in DAG)
  |
  v
Executor: Post-Build Actions
  |-- Run :always steps (regardless of status)
  |-- Run :on-success steps (if build succeeded)
  |-- Run :on-failure steps (if build failed/aborted)
  |-- Post-action failures do NOT change build status
  |
  v
Executor: Artifact Collection
  |-- Match glob patterns against workspace files
  |-- Copy matching files to persistent artifact directory
  |-- Compute SHA-256 checksums for integrity verification
  |-- Record metadata in DB (filename, size, content-type, checksum)
  |-- If incremental artifacts enabled:
  |    |-- Compare against previous build's artifacts (block-level delta)
  |    |-- 4KB blocks with MD5 hashing, store only changed blocks
  |    |-- Apply delta when savings > 20%
  |
  v
Executor: Notifications
  |-- Look up Notifier plugin by :type (via registry)
  |-- Dispatch to configured notifiers (console, Slack, email)
  |-- Record notification events in DB
  |
  v
Executor: SCM Status Reporting
  |-- Look up ScmStatusReporter plugin by provider
  |-- Report build status back to GitHub/GitLab/Gitea/Bitbucket (commit status API)
  |
  v
Build Runner: Finalization
  |-- Update build record: status, end-time, duration
  |-- Emit :build-completed event (triggers SSE update)
  |-- Remove from active builds registry
```

## Plugin System

### Architecture

```
+-------------------------------------------+
|           Plugin Registry (atom)          |
|  :plugins          name → descriptor      |
|  :step-executors   :shell → ShellExec     |
|                    :docker → DockerExec   |
|  :pipeline-formats "yaml" → YamlFormat   |
|  :notifiers        :console → ConsoleN   |
|                    :slack → SlackN        |
|                    :email → EmailN        |
|  :artifact-handlers "local" → LocalAH   |
|  :scm-providers    :git → GitSCM         |
|  :scm-status       :github → GHStatus    |
|                    :gitlab → GLStatus     |
|                    :gitea → GiteaStatus   |
|                    :bitbucket → BBStatus  |
+-------------------------------------------+
           ^                    |
           |  register!         |  lookup
     +-----+------+     +------+------+
     |   Plugin    |     |  Executor   |
     |   Loader    |     |  Engine     |
     +-----+------+     +-------------+
           |
     +-----+------+
     |  Builtin   |  (auto-loaded on startup)
     |  Plugins   |
     +-----+------+
           |
     +-----+------+
     |  External  |  (loaded from plugins/ dir,
     |  Plugins   |   gated by plugin_policies)
     +-------------+
```

### Plugin Trust Enforcement

External plugins are gated by a DB-backed allowlist before loading:

```
Plugin Loader                    Plugin Policy Store
  |                                     |
  |-- for each .clj in plugins/ ------->|
  |                                     |-- lookup plugin_policies(name, org_id)
  |                                     |-- allowed = true?
  |<---- yes: load-file ----------------|
  |<---- no:  log warning, skip --------|
  |                                     |
  |-- (no DB provided) → load all ----->|  (backward compat)
```

### Docker Image Policy

Docker step execution checks image against org-scoped policies:

```
Docker Executor                  Docker Policy Store
  |                                     |
  |-- check-image-allowed(image) ------>|
  |                                     |-- fetch policies for org, sorted by priority
  |                                     |-- for each policy:
  |                                     |     glob-match pattern against image
  |                                     |     if match: return {allowed: action}
  |                                     |-- no match: allow (default-open)
  |<---- {allowed: true/false} ---------|
  |                                     |
  |-- if denied: throw ex-info -------->|  (build fails with policy reason)
  |-- if allowed: proceed to pull/run --|
```

Glob patterns use `Pattern/quote` for safe regex conversion, preventing injection via metacharacters in policy patterns.

### Protocols

```clojure
;; Step execution (shell, docker, etc.)
(defprotocol StepExecutor
  (execute-step [this build-ctx step-def]))

;; Pipeline file format (EDN, YAML, etc.)
(defprotocol PipelineFormat
  (parse-pipeline [this file-path])
  (detect-file [this workspace-dir]))

;; Build notifications (console, slack, email, etc.)
(defprotocol Notifier
  (send-notification [this build-result config]))

;; Artifact storage (local, S3, etc.)
(defprotocol ArtifactHandler
  (collect-artifacts [this workspace-dir artifact-dir patterns]))

;; Source code management (git, etc.)
(defprotocol ScmProvider
  (checkout-source [this source-config workspace-dir commit-override]))

;; SCM commit status reporting (GitHub, GitLab, etc.)
(defprotocol ScmStatusReporter
  (report-status [this build-info config]))

;; Secret storage backends (local, vault, etc.)
(defprotocol SecretBackend
  (get-secret [this key opts])
  (set-secret [this key value opts])
  (list-secrets [this opts])
  (delete-secret [this key opts]))
```

### Builtin Plugins

| Plugin | Type | Key |
|--------|------|-----|
| Shell Executor | StepExecutor | `:shell` |
| Docker Executor | StepExecutor | `:docker` |
| Docker Compose Executor | StepExecutor | `:docker-compose` |
| Console Notifier | Notifier | `:console` |
| Slack Notifier | Notifier | `:slack` |
| Email Notifier | Notifier | `:email` |
| Local Artifacts | ArtifactHandler | `"local"` |
| Local Secrets | SecretBackend | `"local"` |
| Vault Secrets | SecretBackend | `"vault"` |
| Git SCM | ScmProvider | `:git` |
| YAML Format | PipelineFormat | `"yaml"`, `"yml"` |
| GitHub Status | ScmStatusReporter | `:github` |
| GitLab Status | ScmStatusReporter | `:gitlab` |
| Gitea Status | ScmStatusReporter | `:gitea` |
| Bitbucket Status | ScmStatusReporter | `:bitbucket` |

## Authentication & Security

### Auth Architecture

```
Request
  |
  v
wrap-auth middleware
  |
  +-- Auth disabled? → attach admin user (backward compat)
  |
  +-- Public path? (/login, /health, /ready, /startup, /api/webhook) → pass through
  |
  +-- Distributed agent write path? → bypass (allowlist: /agent-events, /result,
  |     /artifacts, /heartbeat suffixes only; handler-level check-auth)
  |
  +-- Session cookie? → validate session version against DB
  |
  +-- Bearer token? → try JWT → try API token
  |       |
  |       +-- JWT: verify signature, check expiry, check blacklist,
  |       |        validate session version, audience, issuer
  |       |
  |       +-- API token: lookup in DB, validate scopes, return user
  |
  +-- OIDC callback? → validate state, exchange code, provision user
  |
  +-- No credentials → 401 (API) or redirect to /login (browser)
```

### RBAC

```
admin (level 3)     → full access, user management, settings
developer (level 2) → trigger builds, manage secrets, approve gates
viewer (level 1)    → read-only access to builds and jobs
```

### Security Features

- **JWT blacklist** — Tokens can be revoked (password change, forced logout)
- **Session versioning** — Password reset increments version, invalidating all sessions/tokens
- **Account lockout** — Configurable failed attempt threshold and lockout duration
- **Rate limiting** — Request-level middleware to prevent abuse
- **CSRF protection** — Anti-forgery tokens on all form endpoints
- **Constant-time comparison** — Webhook signatures and tokens use timing-safe comparison
- **Input sanitization** — Agent registration, Docker commands, and webhook payloads validated

## Multi-Tenancy

### Organization Model

```
organizations
  |-- id (UUID)
  |-- name, slug
  |-- settings (JSON)
  |
  +-- org_members (join table)
       |-- user_id → users(id)
       |-- org_id → organizations(id)
       |-- role (admin/member)

Default org ("default-org") created on migration for backward compatibility.
```

### Resource Isolation

All resource queries are scoped by `org_id`:

```
Request
  |
  v
wrap-org-context middleware
  |
  +-- Session has current-org-id? → use it
  |
  +-- User has org membership? → use first org
  |
  +-- Fallback → "default-org"
  |
  v
:org-id attached to request
  |
  v
Store layer: WHERE org_id = :org-id on all queries
```

Resources isolated per org: jobs, builds, secrets, templates, audit logs, webhook events, approval gates, and build queue entries. Agents can be shared (no `org_id`) or org-specific.

## Docker Integration

### Command Generation

```
Step Definition                Docker Command
+------------------+          +----------------------------------------+
| :type :docker    |    →     | docker run --rm                        |
| :image maven:3.9 |          |   -v '/workspace:/workspace'           |
| :command mvn test|          |   -w '/workspace'                      |
| :env {CI true}   |          |   -e CI='true'                         |
+------------------+          |   maven:3.9 sh -c 'mvn test'           |
                              +----------------------------------------+
```

Docker commands are shell strings passed to `babashka/process`. Input validation prevents injection attacks:
- Image names validated against `[a-zA-Z0-9._\-/:@]+` pattern
- Service and network names validated
- Environment values shell-quoted with single quotes
- Volume paths shell-quoted
- Docker args filtered (must start with `-`)
- Cache volume names validated (alphanumeric + hyphens only)
- Cache volume mount paths validated (absolute, no traversal, no special chars)

### Container Propagation

```
Pipeline level                    Stage level                 Step level
:container {:image node:18}  →  stage gets :container  →  shell steps become :docker
                                                            with :image from container

Cache volumes propagate through the same chain:
:container {:image node:18             step gets :cache-volumes
            :cache-volumes             → Docker named volumes mounted
            {"npm" "/root/.npm"}}      → -v npm-cache:'/root/.npm'
```

## Distributed Builds

### Architecture

```
Master (Chengis Web)          Agent Node 1           Agent Node 2
  Build Queue         ───────>  Executor Engine        Executor Engine
  Build Dispatch      ───HTTP─>  Event Streaming        Event Streaming
  Agent Registry      <──HTTP──  Artifact Upload        Artifact Upload
  Event Collector                Local Workspace        Local Workspace
  Circuit Breaker
  Orphan Monitor
  Database (SQLite / PostgreSQL)
```

### Communication Protocol

```
1. Register:   Agent POST → master/api/agents/register
                 Body: {name, url, labels, max-builds, system-info}
                 Auth: Bearer token header
                 Response: {agent-id}

2. Heartbeat:  Agent POST → master/api/agents/:id/heartbeat (every 30s)
                 Body: {current-builds, system-info}
                 Master marks offline after 90s silence

3. Dispatch:   Master POST → agent/builds
                 Body: {pipeline, build-id, job-id, parameters, env}
                 Agent returns 202 Accepted
                 Circuit breaker wraps this call

4. Events:     Agent POST → master/api/builds/:id/agent-events
                 Body: build event (fed into SSE bus)

5. Result:     Agent POST → master/api/builds/:id/result
                 Body: {build-status, stage-results, error}

6. Artifacts:  Agent POST → master/api/builds/:id/artifacts
                 Body: multipart file upload
```

### Dispatch Strategy

All build trigger paths (web UI, CLI retry, webhooks) route through the dispatcher when the `:distributed-dispatch` feature flag is enabled:

```
Trigger Build
  |
  v
Feature flag :distributed-dispatch enabled?
  |-- No → Run locally (legacy path)
  |-- Yes → Dispatcher
               |
               v
Queue enabled? → Enqueue in persistent build_queue table
  |                with priority (default: :normal)
  |                Queue processor picks up periodically
  |
  v
Is distributed enabled?
  |-- No → Run locally
  |-- Yes → Find available agent
               |-- Label matching: agent.labels ⊇ pipeline.labels
               |-- Capacity check: current-builds < max-builds
               |-- Heartbeat fresh: < 90s since last heartbeat
               |-- Resource filtering: CPU ≥ required, memory ≥ required
               |-- Weighted scoring: (1-load)×0.6 + cpu×0.2 + mem×0.2
               |-- Circuit breaker: skip agents in :open state
               |
               v
            Agent found?
               |-- Yes → HTTP dispatch to agent (via circuit breaker)
               |-- No → fallback-local enabled?
                          |-- Yes → Run locally
                          |-- No → Error: no agent available
```

### Reliability

- **Circuit breaker** — Wraps agent HTTP calls. Opens after N consecutive failures, half-opens after timeout for probe requests, closes on success
- **Orphan monitor** — Periodically checks for builds dispatched to agents that have gone offline. Auto-fails orphaned builds after configurable timeout
- **Build queue** — Persistent database-backed queue ensures builds survive master restarts. Priority levels: `:high`, `:normal`, `:low`
- **Persistent agent registry** — Write-through cache: mutations write to DB first, then update in-memory atom. On master restart, `hydrate-from-db!` restores agent state. When no datasource is configured (tests, CLI), falls back to atom-only mode

### High Availability

- **Leader election** — PostgreSQL advisory locks (`pg_try_advisory_lock`) ensure singleton services (queue-processor, orphan-monitor, retention-scheduler) run on exactly one master. Session-scoped locks auto-release on connection drop for instant failover. SQLite mode always acquires (single master assumed)
- **Poll-based leadership** — Background thread polls `try-acquire!` every N seconds (default 15s). On acquisition calls `start-fn`; on loss calls `stop-fn`. Simpler than holding a dedicated connection
- **Startup probe** — `/startup` returns 503 until initialization completes, then 200. Kubernetes `startupProbe` prevents premature traffic routing
- **Enhanced readiness** — `/ready` includes queue depth and agent summary (total, online, offline, capacity)
- **Instance identification** — `/health` includes `instance-id` (from `CHENGIS_HA_INSTANCE_ID` or auto-generated)

### Security

- Shared-secret authentication via Bearer token
- All API endpoints require auth when token is configured
- Agent registration validates and sanitizes input fields
- Distributed path exemptions use explicit allowlist (4 agent write endpoint suffixes only)
- SSE event endpoints require user authentication (not bypassed in distributed mode)
- Secrets encrypted with AES-256-GCM in transit

## Matrix Builds

### Expansion

```
Matrix Config                     Expanded Stages
+-------------------+             +----------------------------------+
| :os [linux macos] |    →        | Build [jdk=11, os=linux]         |
| :jdk [11 17]      |             | Build [jdk=17, os=linux]         |
|                   |             | Build [jdk=11, os=macos]         |
| :exclude          |             | Build [jdk=17, os=macos]         |
| [{:os macos       |             +----------------------------------+
|   :jdk 11}]       |             (exclude removes os=macos, jdk=11)
+-------------------+
```

Matrix expansion happens after container propagation but before stage execution. Each expanded stage gets:
- Suffixed name: `"Build [os=linux, jdk=11]"`
- `MATRIX_*` env vars injected into all steps: `MATRIX_OS=linux`, `MATRIX_JDK=11`
- `:matrix-combination` metadata for UI rendering

Maximum combinations enforced (default 25) to prevent combinatorial explosion.

## YAML Pipeline Format

### Expression Resolution

```
${{ parameters.name }}  → PARAM_NAME env var reference
${{ secrets.KEY }}      → resolved at runtime by executor
${{ env.VAR }}          → env var reference

Resolution happens during YAML parsing, before execution.
```

### Multi-Format Pipeline Detection

```
Workspace cloned
  |
  v
Check Chengisfile (EDN) → if found, use it (source: "chengisfile")
  |
  v (not found)
Check YAML files:
  .chengis/workflow.yml
  .chengis/workflow.yaml
  chengis.yml
  chengis.yaml
  → if found, parse and use (source: "yaml")
  |
  v (not found)
Use server-side pipeline definition (source: "server")
```

All three formats produce the same internal data map. The executor does not know which format the pipeline came from.

## Concurrency Model

### Build-Level Parallelism

The build runner uses a fixed thread pool (`Executors/newFixedThreadPool 4`) to limit concurrent builds. Each build runs on a dedicated thread.

```
Build Runner Thread Pool (4 threads)
  |
  +-- Thread 1: Build #42 (running)
  +-- Thread 2: Build #43 (running)
  +-- Thread 3: Build #44 (running)
  +-- Thread 4: Build #45 (running)
  +-- Queue: Build #46, #47 (waiting)
```

### Stage-Level Parallelism (DAG Mode)

When stages declare `:depends-on`, independent stages execute concurrently:

```clojure
;; Stages B and C run in parallel (both depend only on A)
(stage "A" ...)
(stage "B" {:depends-on ["A"]} ...)
(stage "C" {:depends-on ["A"]} ...)
(stage "D" {:depends-on ["B" "C"]} ...)
```

DAG execution uses `core.async/thread` per stage, bounded by a `Semaphore` (default max-concurrent: 4). On any stage failure, all downstream dependents are cancelled.

### Step-Level Parallelism

Within a stage, steps can run sequentially (default) or in parallel:

```clojure
;; Sequential: Step A completes before Step B starts
(stage "Build"
  (step "A" (sh "..."))
  (step "B" (sh "...")))

;; Parallel: Steps A, B, C run concurrently via core.async/thread
(stage "Test"
  (parallel
    (step "A" (sh "..."))
    (step "B" (sh "..."))
    (step "C" (sh "..."))))
```

Parallel steps use `core.async/thread` (backed by the cached thread pool) and are joined with `<!!` before proceeding to the next stage.

### Agent Worker Pool

Each agent has a configurable thread pool (default 2, set by `:max-builds`). The pool is lazily initialized and can be recreated after shutdown.

### Event Bus

The event bus uses a dual-path architecture: **durable persistence** to the database for replay, and **ephemeral pub/sub** via `core.async` for live SSE streaming:

```
Executor                    Event Bus                    SSE Handler
   |                           |                              |
   |-- emit(:step-completed) ->|                              |
   |                           |-- persist to build_events DB |
   |                           |   (time-ordered ID)          |
   |                           |-- publish to channel         |
   |                           |   (keyed by build-id)        |
   |                           |                              |
   |                           |            subscribe(id) ----|
   |                           |                              |
   |                           |------- event --------------->|
   |                           |                              |-- format as HTML
   |                           |                              |-- send SSE to browser
```

Events are persisted with time-ordered IDs (`<epoch_ms>-<seq>-<uuid>`) that guarantee insertion-order retrieval regardless of timestamp precision. The replay API (`GET /api/builds/:id/events/replay`) supports cursor-based pagination via `?after=<event-id>` for SSE reconnection or post-mortem analysis. DB persistence failures are logged but never block the ephemeral SSE path.

In distributed mode, agents stream events to the master via HTTP POST, and the master feeds them into the same event bus for SSE delivery.

## Database Schema

Chengis supports dual-driver persistence — **SQLite** (default, zero-config) and **PostgreSQL** (production, HikariCP-pooled). Both drivers share 50 migration versions maintained in separate directories (`migrations/sqlite/` and `migrations/postgresql/`):

### Core Tables (Migration 001)

```sql
jobs              -- Pipeline definitions
  id TEXT PK
  name TEXT UNIQUE
  description TEXT
  pipeline TEXT     -- Serialized EDN pipeline definition
  parameters TEXT   -- Serialized parameter definitions
  triggers TEXT     -- Serialized trigger config
  source TEXT       -- Serialized git source config
  created_at TEXT

builds            -- Build execution records
  id TEXT PK
  job_id TEXT FK -> jobs(id)
  build_number INTEGER
  status TEXT       -- running, success, failure, aborted
  trigger_type TEXT -- manual, webhook, cron, retry
  started_at TEXT
  finished_at TEXT
  workspace TEXT
  parameters TEXT   -- Serialized runtime parameters

build_stages      -- Stage results per build
build_steps       -- Step results per stage
build_logs        -- Structured log entries
```

### Extended Tables (Migrations 002-010)

```sql
-- 002: Git metadata on builds
-- 003: Pipeline source tracking (server/chengisfile/yaml)
-- 004: Build retry support
-- 005: Encrypted secrets (job_secrets)
-- 006: Artifact metadata (build_artifacts)
-- 007: Notification events (build_notifications)
-- 008: Plugin tracking (plugins)
-- 009: Docker container columns (container_image, container_id on build_steps)
-- 010: Agent management (agents table, agent_id/dispatched_at on builds)
```

### Enterprise Tables (Migrations 011-022)

```sql
-- 011: User management (users table — username, password_hash, role, active)
-- 012: API tokens (api_tokens table — token_hash, user_id, name, expires_at)
-- 013: Audit logging (audit_logs table — username, action, resource, ip, timestamp)
-- 014: Build queue (build_queue table — job_id, priority, status, queued_at)
-- 015: Token revocation (revoked_at column on api_tokens)
-- 016: JWT blacklist (jwt_blacklist table — jti, user_id, expires_at, reason)
-- 017: Session versioning (session_version column on users, default 1)
-- 018: Webhook events (webhook_events table — provider, status, repo, branch, payload_size)
-- 019: Secret access audit (secret_access_log table — secret_name, user, action, timestamp)
-- 020: Account lockout (failed_attempts, locked_until columns on users)
-- 021: Approval gates (build_approvals table — build_id, stage, status, approver, timeout)
-- 022: Pipeline templates (pipeline_templates table — name, description, pipeline_data)
```

### Identity & Multi-Tenancy Tables (Migrations 023-028)

```sql
-- 023: SSO/OIDC fields (provider, provider_id, email columns on users)
-- 024: API token scopes (scopes column on api_tokens)
-- 025: Organizations (organizations table, org_members join table)
-- 026: Org-scoped resources (org_id column on jobs, builds, secrets, templates,
--       audit_logs, webhook_events, build_approvals)
-- 027: Secret backends (secret_backends table — name, type, config, org_id)
-- 028: Multi-approver (required_approvals, approval_count on build_approvals)
```

### Governance Tables (Migrations 029-031)

```sql
-- 029: Policy engine (policies table — org_id, name, type, rules, priority, enabled)
-- 030: Compliance tracking (compliance_results table, artifact checksum columns)
-- 031: Artifact integrity (additional checksum validation columns)
```

### Distributed Hardening Tables (Migrations 032-034)

```sql
-- 032: Build attempts (attempt_number, root_build_id on builds)
-- 033: Durable build events (build_events table — build_id, event_type, stage_name,
--       step_name, data; time-ordered IDs for guaranteed insertion order)
-- 034: Plugin & Docker policies (plugin_policies table — trust allowlist per org;
--       docker_policies table — image allow/deny rules with priority ordering)
```

### HA & Integrity Tables (Migrations 035-036)

```sql
-- 035: Persistent agent registry (current_builds column on agents table)
-- 036: Audit hash-chain ordering (seq_num column on audit_logs for
--       cross-DB insertion-order tiebreaking, replacing SQLite-specific rowid)
```

### Build Performance & Caching Tables (Migrations 037-039)

```sql
-- 037: Artifact/dependency cache metadata (cache_entries table — job_id,
--       cache_key, paths, size_bytes, hit_count, org_id; UNIQUE(job_id, cache_key))
-- 038: Build result cache (stage_cache table — job_id, fingerprint, stage_name,
--       stage_result, git_commit; UNIQUE(job_id, fingerprint))
-- 039: Incremental artifact storage (delta_base_id, is_delta, original_size_bytes
--       columns on build_artifacts)
```

### Observability & Analytics Tables (Migrations 040-043)

```sql
-- 040: Distributed tracing (trace_spans table — trace_id, span_id, parent_span_id,
--       service_name, operation, kind, status, started_at, ended_at, duration_ms,
--       attributes, build_id, org_id)
-- 041: Build analytics (build_analytics table — period_type, period_start/end,
--       total_builds, success/failure/aborted counts, success_rate, percentile durations;
--       stage_analytics table — same with stage_name and flakiness_score)
-- 042: Cost attribution (build_cost_entries table — build_id, job_id, org_id,
--       agent_id, started_at, ended_at, duration_s, cost_per_hour, computed_cost)
-- 043: Flaky test detection (test_results table — build_id, test_name, status,
--       duration_ms, error_msg; flaky_tests table — total_runs, pass/fail counts,
--       flakiness_score, UNIQUE(org_id, job_id, test_name))
```

### Advanced SCM & Workflow Tables (Migrations 044-047)

```sql
-- 044-047: PR status checks (pr_checks table), build dependencies
--          (job_dependencies table), cron schedules (cron_schedules table),
--          and related indexes for PR check enforcement, dependency graphs,
--          and persistent cron scheduling
```

### Supply Chain Security Tables (Migrations 048-050)

```sql
-- 048: Supply chain core tables
--       provenance_attestations — build_id, builder_id, build_type, invocation,
--         materials, metadata, slsa_version, org_id
--       sbom_records — build_id, format (cyclonedx/spdx), tool, content, org_id
--       vulnerability_scans — build_id, scanner, image, severity_counts, findings, org_id
-- 049: OPA and license tables
--       opa_policies — name, description, rego_source, enabled, org_id
--       license_results — build_id, license_id, package, status, org_id
--       license_policies — name, allowed_licenses, denied_licenses, mode, org_id
-- 050: Signatures and regulatory tables
--       artifact_signatures — build_id, artifact_id, tool, key_id, signature, verified, org_id
--       regulatory_assessments — framework, assessment_date, overall_score,
--         control_scores, recommendations, org_id
```

## Build Performance & Caching

### DAG-Based Parallel Stage Execution

When any stage in a pipeline declares `:depends-on`, the executor switches from sequential to DAG mode:

```
Pipeline Definition                   DAG Execution
  Build (no deps)           ┌──> Lint (depends-on Build)
  Lint (depends-on Build)   │         ↓
  Test (depends-on Build) ──┼──> Test (depends-on Build)   [parallel]
  Deploy (depends-on         │         ↓
    Lint, Test)              └──> Deploy (depends-on Lint, Test)
```

- **Kahn's algorithm** — Topological sort detects cycles and validates all dependencies exist
- **Bounded concurrency** — `java.util.concurrent.Semaphore` limits parallel stages (configurable, default 4)
- **Failure propagation** — Stage failure cancels all downstream dependents
- **Backward compatible** — No `:depends-on` annotations → sequential mode (existing behavior)

### Caching Architecture

```
Artifact/Dependency Cache                Build Result Cache
  |                                        |
  |-- resolve-cache-key                    |-- stage-fingerprint
  |     {{ hashFiles('lock') }}            |     SHA-256(commit|name|cmds|env)
  |     → SHA-256 of file contents         |
  |                                        |-- check-stage-cache(fingerprint)
  |-- restore-cache!                       |     → cached result or nil
  |     exact key → prefix match           |
  |     → copy dirs to workspace           |-- save-stage-result!(fingerprint, result)
  |                                        |     → persist for future builds
  |-- save-cache!                          |
  |     → copy workspace dirs to store     |-- Build-specific env vars excluded:
  |     (immutable: skip if key exists)    |     BUILD_ID, BUILD_NUMBER, WORKSPACE,
  |                                        |     JOB_NAME (prevents false cache misses)
  |-- DB: cache_entries table              |
  |     job_id, cache_key, paths,          |-- DB: stage_cache table
  |     size_bytes, hit_count              |     job_id, fingerprint, stage_result
```

### Resource-Aware Agent Scheduling

```
Agent Selection Pipeline
  |
  |-- Filter: label matching (agent.labels ⊇ required)
  |-- Filter: capacity (current-builds < max-builds)
  |-- Filter: heartbeat freshness (< timeout threshold)
  |-- Filter: minimum resources (CPU ≥ required, memory ≥ required)
  |-- Score: weighted formula per agent
  |     score = (1 - load_ratio) × 0.6
  |           + cpu_score × 0.2
  |           + memory_score × 0.2
  |-- Sort: highest score wins
```

## Supply Chain Security

### Architecture

```
Build Executor                  Supply Chain Layer
  |                                    |
  |-- build completes              Provenance Engine
  |                                    |-- generate SLSA v1.0 attestation
  |                                    |-- record builder, source, materials
  |                                    |-- store in provenance_attestations
  |                                    |
  |                                 SBOM Generator
  |                                    |-- detect tool: syft or cdxgen
  |                                    |-- generate CycloneDX / SPDX
  |                                    |-- store in sbom_records
  |                                    |
  |                                 Vulnerability Scanner
  |                                    |-- detect tool: trivy or grype
  |                                    |-- scan container images
  |                                    |-- classify by severity
  |                                    |-- store in vulnerability_scans
  |                                    |
  |                                 License Scanner
  |                                    |-- parse SBOM for license data
  |                                    |-- evaluate against license policy
  |                                    |-- store in license_results
  |                                    |
  |                                 OPA Policy Engine
  |                                    |-- load Rego policies from DB
  |                                    |-- evaluate against build context
  |                                    |-- return allow/deny decisions
  |                                    |
  |                                 Artifact Signer
  |                                    |-- detect tool: cosign or gpg
  |                                    |-- sign artifacts + attestations
  |                                    |-- store in artifact_signatures
  |                                    |
  |                                 Regulatory Engine
  |                                    |-- assess audit trail completeness
  |                                    |-- score per control category
  |                                    |-- store in regulatory_assessments
```

### External Tool Integration

All external tools are detected at runtime and degrade gracefully when not installed:

```
Tool Detection                      Execution
  |                                    |
  |-- which trivy -------> found? --->| trivy image --format json <image>
  |                    \-> not found ->| try grype, or skip scanning
  |
  |-- which syft --------> found? --->| syft <target> -o cyclonedx-json
  |                    \-> not found ->| try cdxgen, or skip SBOM
  |
  |-- which cosign ------> found? --->| cosign sign --key <key> <artifact>
  |                    \-> not found ->| try gpg, or skip signing
  |
  |-- which opa ---------> found? --->| opa eval -d <policy> -i <input>
  |                    \-> not found ->| skip OPA evaluation
```

### Data Flow

```
Build Artifacts
  |
  +---> SBOM Generation -----> License Scanning
  |         |                       |
  |         v                       v
  |     sbom_records          license_results
  |
  +---> Image Scanning -----> vulnerability_scans
  |
  +---> Provenance ---------> provenance_attestations
  |
  +---> Artifact Signing ---> artifact_signatures
  |
  +---> All data feeds -----> Regulatory Assessment
                                    |
                                    v
                              regulatory_assessments
                              (SOC 2 / ISO 27001 scoring)
```

### Feature Flags

Each supply chain feature is independently gated:

| Flag | Component | External Tool |
|------|-----------|---------------|
| `:slsa-provenance` | `provenance.clj` | None (pure Clojure) |
| `:sbom-generation` | `sbom.clj` | Syft or cdxgen |
| `:container-scanning` | `vulnerability_scanner.clj` | Trivy or Grype |
| `:opa-policies` | `opa.clj` | OPA |
| `:license-scanning` | `license_scanner.clj` | None (parses SBOM data) |
| `:artifact-signing` | `signing.clj` | cosign or GPG |
| `:regulatory-dashboards` | `regulatory.clj` | None (queries audit data) |

## Secrets Management

Secrets are encrypted at rest using AES-256-GCM:

```
Store Secret:
  plaintext -> AES-256-GCM encrypt (master key + random IV)
              -> Base64(ciphertext) + Base64(IV) -> Database

Use Secret:
  Database -> Base64 decode -> AES-256-GCM decrypt (master key + IV)
           -> plaintext -> injected as env var

Log Masking:
  All secret values registered with log masker
  stdout/stderr scanned and values replaced with ***
```

## DSL Design

The pipeline DSL uses Clojure macros that expand to pure data:

```clojure
;; This macro call:
(defpipeline my-app
  {:description "My app"}
  (stage "Build"
    (step "Compile" (sh "make"))))

;; Produces this data map:
{:pipeline-name "my-app"
 :description "My app"
 :stages [{:stage-name "Build"
           :parallel? false
           :steps [{:type :shell
                    :command "make"
                    :step-name "Compile"}]}]}
```

The key insight: **pipelines are just data**. The DSL macro is syntactic sugar; the executor only sees maps and vectors. This enables:

- Pipelines from DSL macros, Chengisfile EDN, and YAML workflows share the same execution path
- Pipelines can be serialized, stored in the database, and transmitted over the wire (to agents)
- Testing the executor requires only constructing maps, not evaluating macros
- New pipeline formats (YAML, TOML, etc.) only need to produce the same data map
- Matrix expansion operates purely on the data map, duplicating stages with injected env vars

## Web UI Architecture

The UI uses a zero-JavaScript architecture:

```
Server (Clojure)              Browser
  |                              |
  |-- Full HTML page ----------->|  (initial page load)
  |                              |
  |<-- htmx request (AJAX) -----|  (user clicks trigger)
  |-- HTML fragment ------------>|  (htmx swaps into DOM)
  |                              |
  |<-- SSE subscribe ------------|  (build page opened)
  |-- SSE: HTML fragments ------>|  (live log lines)
  |-- SSE: HTML fragments ------>|  (step completed)
  |-- SSE: HTML fragments ------>|  (build completed)
  |-- SSE: close --------------->|
```

- **htmx** handles all interactivity (form submission, navigation, polling)
- **SSE** handles real-time streaming (build logs, status updates)
- **Tailwind CSS** (CDN) handles all styling
- **Hiccup 2** renders HTML on the server

No build step, no bundler, no node_modules.

## Cancellation Model

Build cancellation uses cooperative interruption:

```
User clicks Cancel
  |
  v
Handler: POST /builds/:id/cancel
  |
  v
Build Runner: cancel-build!(build-id)
  |-- Sets cancelled? atom to true
  |-- Calls .interrupt() on build thread
  |
  v
Executor: checks cancelled? before each stage/step
  |-- If true: skips remaining work
  |-- Sets build status to :aborted
  |
  v
Process: .waitFor() is interrupted
  |-- InterruptedException caught
  |-- Process destroyed
  |-- Step marked as :aborted
```

## Observability

### Prometheus Metrics

When enabled (`:metrics {:enabled true}`), Chengis exposes a `/metrics` endpoint with:

- **Build metrics** — `chengis_builds_total`, `chengis_build_duration_seconds`
- **Auth metrics** — `chengis_login_total`, `chengis_token_auth_total`
- **Webhook metrics** — `chengis_webhooks_received_total`, `chengis_webhook_processing_seconds`
- **Retention metrics** — `chengis_retention_cleaned_total`
- **HTTP metrics** — request count, duration, status codes (via middleware)

### Alert System

The alert system monitors system health and auto-resolves when conditions clear:

```
Alert Sources                   Alert Manager              UI
  |                                |                        |
  |-- System health check -------->|                        |
  |-- Build queue overflow ------->|  Active alerts (atom)  |
  |-- Agent offline detected ----->|                        |
  |                                |-- GET /api/alerts ---->|
  |                                |   (polled by htmx)     |
```

### Data Retention

The retention scheduler runs periodically (default every 24 hours) and cleans up:
- Audit logs older than N days (default 90)
- Webhook events older than N days (default 30)
- Secret access logs older than N days
- Expired JWT blacklist entries
- Old workspaces
- Trace spans older than retention period (default 7 days)
- Build analytics older than retention period (default 365 days)
- Cost entries older than retention period
- Old test results

### Build Tracing

When the `:tracing` feature flag is enabled, Chengis creates lightweight span records for build execution:

```
Build Runner                    Tracing Engine              DB (trace_spans)
  |                                |                              |
  |-- start-span!(build) -------->|                              |
  |                                |-- create-span! ------------>|
  |                                |   (trace-id, span-id,       |
  |                                |    operation, build-id)      |
  |                                |                              |
  |   For each stage/step:        |                              |
  |-- start-span!(stage) -------->|                              |
  |                                |-- create-span! ------------>|
  |                                |   (parent-span-id linked)   |
  |                                |                              |
  |-- end-span!(stage) ---------->|                              |
  |                                |-- update-span! ------------>|
  |                                |   (ended-at, duration-ms)   |
  |                                |                              |
  |-- end-span!(build) ---------->|                              |
  |                                |                              |
  Web UI:                          |                              |
  GET /admin/traces → list-traces  |                              |
  GET /admin/traces/:id → waterfall visualization                |
  GET /api/traces/:id/otlp → OTLP-compatible JSON export         |
```

Spans are sampled probabilistically based on `:sample-rate` and cleaned up by the retention scheduler.

### Build Analytics

When the `:build-analytics` feature flag is enabled, a chime-based scheduler aggregates build and stage statistics:

```
Analytics Scheduler (HA singleton, lock 100004)
  |
  v
run-aggregation!
  |-- Query builds for daily/weekly periods
  |-- Compute: total, success/fail/abort counts, success rate
  |-- Compute percentiles: p50, p90, p99 duration
  |-- Compute stage flakiness: 1 - |2*success_rate - 1|
  |-- Upsert into build_analytics / stage_analytics tables
  |
  v
Web UI:
  GET /analytics → trends, slowest stages, flaky stages
```

## File Organization Rationale

| Directory | Responsibility | Key Principle | Files |
|-----------|---------------|---------------|-------|
| `agent/` | Agent node lifecycle | Separate process entry point | 5 |
| `cli/` | User-facing CLI commands | Thin layer over engine | 3 |
| `db/` | Data access (stores) | One file per table/concern | 37 |
| `distributed/` | Master-side coordination | Registry, dispatch, queue, reliability, HA | 9 |
| `dsl/` | Pipeline definition | Macros and parsers produce data | 6 |
| `engine/` | Build orchestration | Core business logic | 41 |
| `model/` | Data validation (specs) | Schema definitions | 1 |
| `plugin/` | Extension infrastructure | Protocols + registry + loader + builtins | 17 |
| `web/` | HTTP handling | Handlers + middleware | 12 |
| `web/views/` | Hiccup templates | One file per page/component | 31 |

Dependencies flow downward: `web` -> `engine` -> `db` -> `util`. The engine layer never imports web concerns, and the database layer never imports engine concerns. The plugin layer is cross-cutting but only depends on foundation. The auth/security layer wraps web handlers and is applied via middleware composition in `routes.clj`.
