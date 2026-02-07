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
                      |   SQLite    |  | babashka/   |
                      |  (next.jdbc)|  |  process    |
                      +-------------+  +------+------+
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
|            trigger, agents)                                   |
|   distributed/master_api.clj                                  |
+---------------------------------------------------------------+
|                        Engine Layer                            |
|   build_runner.clj   executor.clj   process.clj              |
|   git.clj   workspace.clj   artifacts.clj   notify.clj       |
|   events.clj   scheduler.clj   cleanup.clj   log_masker.clj  |
|   docker.clj                                                  |
+---------------------------------------------------------------+
|                        Plugin Layer                           |
|   plugin/protocol.clj   plugin/registry.clj                  |
|   plugin/loader.clj                                           |
|   builtin/  (shell, docker, console, slack, git,              |
|              local-artifacts, yaml-format)                     |
+---------------------------------------------------------------+
|                        DSL Layer                              |
|   dsl/core.clj (defpipeline macro)                            |
|   dsl/chengisfile.clj (Pipeline as Code — EDN)                |
|   dsl/yaml.clj (Pipeline as Code — YAML)                     |
|   dsl/expressions.clj (${{ }} resolver)                       |
|   dsl/docker.clj (Docker DSL helpers)                         |
+---------------------------------------------------------------+
|                        Agent Layer                            |
|   agent/core.clj     agent/worker.clj                        |
|   agent/client.clj   agent/heartbeat.clj                     |
+---------------------------------------------------------------+
|                        Distributed Layer                     |
|   distributed/agent_registry.clj                              |
|   distributed/dispatcher.clj                                  |
|   distributed/master_api.clj                                  |
+---------------------------------------------------------------+
|                        Data Layer                             |
|   db/connection.clj   db/migrate.clj                          |
|   db/job_store.clj    db/build_store.clj                      |
|   db/secret_store.clj db/artifact_store.clj                   |
|   db/notification_store.clj                                   |
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
Executor: Stage Execution (sequential)
  |
  |  For each stage:
  |    |-- Check cancellation flag
  |    |-- Emit :stage-started event
  |    |
  |    |  For each step (sequential or parallel):
  |    |    |-- Evaluate conditions (branch, param)
  |    |    |-- Look up StepExecutor plugin by :type
  |    |    |    :shell → ShellExecutor (babashka/process)
  |    |    |    :docker → DockerExecutor (docker run command)
  |    |    |    :docker-compose → DockerComposeExecutor
  |    |    |-- Execute step, capture stdout/stderr (masked)
  |    |    |-- Emit :step-completed event
  |    |    |-- Record result in DB
  |    |
  |    |-- Emit :stage-completed event
  |    |-- If stage fails: skip remaining stages
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
  |-- Record metadata in DB (filename, size, content-type)
  |
  v
Executor: Notifications
  |-- Look up Notifier plugin by :type (via registry)
  |-- Dispatch to configured notifiers (console, Slack)
  |-- Record notification events in DB
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
|  :artifact-handlers "local" → LocalAH   |
|  :scm-providers    :git → GitSCM         |
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
     |  External  |  (loaded from plugins/ dir)
     |  Plugins   |
     +-------------+
```

### Protocols

```clojure
;; Step execution (shell, docker, etc.)
(defprotocol StepExecutor
  (execute-step [this step-def build-ctx]))

;; Pipeline file format (EDN, YAML, etc.)
(defprotocol PipelineFormat
  (can-parse? [this file-path])
  (parse-pipeline [this file-path]))

;; Build notifications (console, slack, etc.)
(defprotocol Notifier
  (send-notification [this build-result config]))

;; Artifact storage (local, S3, etc.)
(defprotocol ArtifactHandler
  (store-artifact [this artifact-info])
  (retrieve-artifact [this artifact-id]))

;; Source code management (git, etc.)
(defprotocol ScmProvider
  (clone-repo [this source-config workspace]))
```

### Builtin Plugins

| Plugin | Type | Key |
|--------|------|-----|
| Shell Executor | StepExecutor | `:shell` |
| Docker Executor | StepExecutor | `:docker` |
| Docker Compose Executor | StepExecutor | `:docker-compose` |
| Console Notifier | Notifier | `:console` |
| Slack Notifier | Notifier | `:slack` |
| Local Artifacts | ArtifactHandler | `"local"` |
| Git SCM | ScmProvider | `:git` |
| YAML Format | PipelineFormat | `"yaml"`, `"yml"` |

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

### Container Propagation

```
Pipeline level                    Stage level                 Step level
:container {:image node:18}  →  stage gets :container  →  shell steps become :docker
                                                            with :image from container
```

## Distributed Builds

### Architecture

```
Master (Chengis Web)          Agent Node 1           Agent Node 2
  Build Dispatch    ───HTTP──>  Executor Engine        Executor Engine
  Agent Registry    <──HTTP───  Event Streaming        Event Streaming
  Event Collector               Local Workspace        Local Workspace
  SQLite DB
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

4. Events:     Agent POST → master/api/builds/:id/agent-events
                 Body: build event (fed into SSE bus)

5. Result:     Agent POST → master/api/builds/:id/result
                 Body: {build-status, stage-results, error}
```

### Dispatch Strategy

```
Trigger Build
  |
  v
Is distributed enabled?
  |-- No → Run locally
  |-- Yes → Find available agent
               |-- Label matching: agent.labels ⊇ pipeline.labels
               |-- Capacity check: current-builds < max-builds
               |-- Heartbeat fresh: < 90s since last heartbeat
               |-- Selection: least-loaded agent
               |
               v
            Agent found?
               |-- Yes → HTTP dispatch to agent
               |-- No → fallback-local enabled?
                          |-- Yes → Run locally
                          |-- No → Error: no agent available
```

### Security

- Shared-secret authentication via Bearer token
- All API endpoints require auth when token is configured
- Agent registration validates and sanitizes input fields
- Secrets encrypted with AES-256-GCM in transit

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

The event bus uses `core.async` pub/sub for SSE streaming:

```
Executor                    Event Bus                    SSE Handler
   |                           |                              |
   |-- emit(:step-completed) ->|                              |
   |                           |-- publish to topic           |
   |                           |   (keyed by build-id)        |
   |                           |                              |
   |                           |            subscribe(id) ----|
   |                           |                              |
   |                           |------- event --------------->|
   |                           |                              |-- format as HTML
   |                           |                              |-- send SSE to browser
```

In distributed mode, agents stream events to the master via HTTP POST, and the master feeds them into the same event bus for SSE delivery.

## Database Schema

Chengis uses SQLite with 10 migration versions:

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

## Secrets Management

Secrets are encrypted at rest using AES-256-GCM:

```
Store Secret:
  plaintext -> AES-256-GCM encrypt (master key + random IV)
              -> Base64(ciphertext) + Base64(IV) -> SQLite

Use Secret:
  SQLite -> Base64 decode -> AES-256-GCM decrypt (master key + IV)
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

## File Organization Rationale

| Directory | Responsibility | Key Principle |
|-----------|---------------|---------------|
| `agent/` | Agent node lifecycle | Separate process entry point |
| `cli/` | User-facing CLI commands | Thin layer over engine |
| `db/` | Data access (stores) | One file per table/concern |
| `distributed/` | Master-side coordination | Registry, dispatch, API |
| `dsl/` | Pipeline definition | Macros and parsers → data |
| `engine/` | Build orchestration | Core business logic |
| `model/` | Data validation (specs) | Schema definitions |
| `plugin/` | Extension infrastructure | Protocols + registry + loader |
| `web/` | HTTP handling and views | MVC without the framework |
| `web/views/` | Hiccup templates | One file per page/component |

Dependencies flow downward: `web` -> `engine` -> `db` -> `util`. The engine layer never imports web concerns, and the database layer never imports engine concerns. The plugin layer is cross-cutting but only depends on foundation.
