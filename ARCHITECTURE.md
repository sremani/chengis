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
                    +-------------+-------------+
                    |                           |
             +------+------+            +------+------+
             |   Reitit    |            |    SSE      |
             |   Router    |            |  Streaming  |
             +------+------+            +------+------+
                    |                          |
             +------+------+           +------+------+
             |  Handlers   |           | core.async  |
             | (Ring fns)  |           |  Event Bus  |
             +------+------+           +------+------+
                    |                          ^
          +---------+---------+                |
          |                   |                |
   +------+------+    +------+------+   +-----+-------+
   |   Hiccup    |    |    Build    |   |   Executor  |
   |    Views    |    |   Runner    +-->+   Engine    |
   +-------------+    +------+------+   +------+------+
                             |                 |
                      +------+------+   +------+------+
                      |   SQLite    |   | babashka/   |
                      |  (next.jdbc)|   |  process    |
                      +-------------+   +-------------+
```

## Module Architecture

### Layer Diagram

```
+---------------------------------------------------------------+
|                        Entry Points                           |
|   core.clj (main)    cli/core.clj     web/server.clj         |
+---------------------------------------------------------------+
|                        Web Layer                              |
|   routes.clj   handlers.clj   sse.clj   webhook.clj          |
|   views/  (layout, dashboard, jobs, builds, admin, trigger)   |
+---------------------------------------------------------------+
|                        Engine Layer                            |
|   build_runner.clj   executor.clj   process.clj              |
|   git.clj   workspace.clj   artifacts.clj   notify.clj       |
|   events.clj   scheduler.clj   cleanup.clj   log_masker.clj  |
+---------------------------------------------------------------+
|                        DSL Layer                              |
|   dsl/core.clj (defpipeline macro)                            |
|   dsl/chengisfile.clj (Pipeline as Code parser)               |
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
Executor: Chengisfile Detection
  |-- Checks for Chengisfile in workspace root
  |-- If found: parses EDN, overrides server-side pipeline
  |-- If not found: uses registered pipeline definition
  |
  v
Executor: Secret Injection
  |-- Loads encrypted secrets from DB for this job
  |-- Decrypts with AES-256-GCM using master key
  |-- Adds to step environment variables
  |-- Registers values with log masker (replaced with ***)
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
  |    |    |-- Execute shell command via babashka/process
  |    |    |-- Capture stdout/stderr (masked)
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
  |-- Dispatch to configured notifiers (console, Slack)
  |-- Record notification events in DB
  |
  v
Build Runner: Finalization
  |-- Update build record: status, end-time, duration
  |-- Emit :build-completed event (triggers SSE update)
  |-- Remove from active builds registry
```

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

Each SSE client subscribes to a `core.async` channel for a specific build-id. Events flow from the executor through the pub/sub bus to all connected browsers in real time.

## Database Schema

Chengis uses SQLite with 7 migration versions:

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
  id TEXT PK
  build_id TEXT FK -> builds(id)
  stage_name TEXT
  status TEXT
  started_at TEXT
  finished_at TEXT

build_steps       -- Step results per stage
  id TEXT PK
  stage_id TEXT FK -> build_stages(id)
  step_name TEXT
  status TEXT
  exit_code INTEGER
  stdout TEXT
  stderr TEXT
  started_at TEXT
  finished_at TEXT

build_logs        -- Structured log entries
  id INTEGER PK
  build_id TEXT FK -> builds(id)
  level TEXT
  message TEXT
  created_at TEXT
```

### Extended Tables (Migrations 002-007)

```sql
-- Migration 002: Git metadata columns on builds table
builds.git_commit, builds.git_commit_short, builds.git_branch,
builds.git_author, builds.git_email, builds.git_message

-- Migration 003: Pipeline source tracking
builds.pipeline_source  -- 'server' or 'chengisfile'

-- Migration 004: Build retry support
builds.retry_of         -- FK to original build ID

-- Migration 005: Encrypted secrets
job_secrets
  id TEXT PK
  job_id TEXT FK -> jobs(id)
  secret_name TEXT
  encrypted_value TEXT  -- AES-256-GCM ciphertext (Base64)
  iv TEXT               -- Initialization vector (Base64)
  created_at TEXT

-- Migration 006: Artifact metadata
build_artifacts
  id TEXT PK
  build_id TEXT FK -> builds(id)
  filename TEXT
  original_path TEXT
  path TEXT             -- Absolute path on disk
  size_bytes INTEGER
  content_type TEXT
  created_at TEXT

-- Migration 007: Notification events
build_notifications
  id TEXT PK
  build_id TEXT FK -> builds(id)
  type TEXT             -- console, slack
  status TEXT           -- pending, sent, failed
  details TEXT
  sent_at TEXT
  created_at TEXT
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

The master key is configured in `config.edn` and is never stored in the database.

## DSL Design

The pipeline DSL uses Clojure macros that expand to pure data:

```clojure
;; This macro call:
(defpipeline my-app
  {:description "My app"}
  (stage "Build"
    (step "Compile" (sh "make"))))

;; Expands to:
(register-pipeline!
  (build-pipeline 'my-app
    {:description "My app"}
    [{:stage-name "Build"
      :parallel? false
      :steps [{:type :shell
               :command "make"
               :step-name "Compile"}]}]))

;; Which registers this data map:
{:pipeline-name "my-app"
 :description "My app"
 :stages [{:stage-name "Build"
           :parallel? false
           :steps [{:type :shell
                    :command "make"
                    :step-name "Compile"}]}]}
```

The key insight: **pipelines are just data**. The DSL macro is syntactic sugar; the executor only sees maps and vectors. This enables:

- Pipelines from DSL macros and Chengisfile EDN share the same execution path
- Pipelines can be serialized, stored in the database, and transmitted over the wire
- Testing the executor requires only constructing maps, not evaluating macros

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
  |-- SSE: close -------------->|
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

This is cooperative cancellation: the executor checks the flag at safe points rather than killing threads forcefully.

## File Organization Rationale

| Directory | Responsibility | Key Principle |
|-----------|---------------|---------------|
| `cli/` | User-facing CLI commands | Thin layer over engine |
| `db/` | Data access (stores) | One file per table/concern |
| `dsl/` | Pipeline definition | Macros expand to data |
| `engine/` | Build orchestration | Core business logic |
| `model/` | Data validation (specs) | Schema definitions |
| `web/` | HTTP handling and views | MVC without the framework |
| `web/views/` | Hiccup templates | One file per page/component |

Dependencies flow downward: `web` -> `engine` -> `db` -> `util`. The engine layer never imports web concerns, and the database layer never imports engine concerns.
