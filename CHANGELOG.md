# Changelog

All notable changes to Chengis are documented in this file.

## [Unreleased] — Phase 6: Advanced SCM & Workflow

### Feature 6a: PR/MR Status Checks

- **Automatic PR status updates** — Build results automatically reported as PR status checks
- **Required check enforcement** — Configure which checks must pass before merging
- **PR check views** — Web UI for viewing and managing PR check status
- **Feature flag** — `:pr-status-checks` (default false)
- **New source**: `src/chengis/engine/pr_checks.clj`, `src/chengis/db/pr_check_store.clj`

### Feature 6b: Branch-Based Pipeline Overrides

- **Branch pattern matching** — Different pipeline behavior per branch pattern (exact, glob, regex)
- **Override configuration** — Configure stage additions, removals, or parameter changes per branch
- **Feature flag** — `:branch-overrides` (default false)
- **New source**: `src/chengis/engine/branch_overrides.clj`

### Feature 6c: Monorepo Support

- **Path-based trigger filtering** — Only build when files in specified directories change
- **Changed file detection** — Analyze git diff to determine affected paths
- **Feature flag** — `:monorepo-filtering` (default false)
- **New source**: `src/chengis/engine/monorepo.clj`

### Feature 6d: Build Dependencies

- **Job dependency graphs** — Explicit dependency declarations between jobs
- **Downstream triggering** — Automatically trigger dependent jobs on successful build completion
- **Dependency API** — `GET /api/jobs/:job-id/dependencies` for querying dependency graphs
- **Feature flag** — `:build-dependencies` (default false)
- **New source**: `src/chengis/engine/build_deps.clj`, `src/chengis/db/dependency_store.clj`

### Feature 6e: Cron Scheduling

- **Database-backed schedules** — Persistent cron schedules stored in DB
- **Missed-run detection** — Catch-up logic for schedules missed during downtime
- **Admin UI** — `/admin/cron` for managing cron schedules
- **API** — `/api/cron` for programmatic schedule management
- **Feature flag** — `:cron-scheduling` (default false)
- **New source**: `src/chengis/engine/cron.clj`, `src/chengis/db/cron_store.clj`

### Feature 6f: Additional SCM Providers

- **Gitea status reporter** — Build status reporting via Gitea API (`ScmStatusReporter` protocol)
- **Bitbucket status reporter** — Build status reporting via Bitbucket API (`ScmStatusReporter` protocol)
- **14 builtin plugins** — Up from 12, now supporting GitHub, GitLab, Gitea, Bitbucket status reporting
- **New source**: `src/chengis/plugin/builtin/gitea_status.clj`, `src/chengis/plugin/builtin/bitbucket_status.clj`

### Feature 6g: Webhook Replay

- **Re-deliver failed webhooks** — Replay webhooks from stored payloads
- **Admin UI** — `/admin/webhook-replay` for browsing and replaying failed webhooks
- **API** — `POST /api/webhooks/:id/replay` for programmatic replay
- **Feature flag** — `:webhook-replay` (default false)
- **New source**: `src/chengis/engine/webhook_replay.clj`

### Feature 6h: Auto-Merge on Success

- **Automatic PR merging** — Merge PRs when all required checks pass
- **Configurable behavior** — Merge strategy and conditions configurable per job
- **Feature flag** — `:auto-merge` (default false)
- **New source**: `src/chengis/engine/auto_merge.clj`

### New Feature Flags (7)

| Flag | Default | Feature |
|------|---------|---------|
| `:pr-status-checks` | `false` | Automatic PR status updates |
| `:branch-overrides` | `false` | Branch-based pipeline overrides |
| `:monorepo-filtering` | `false` | Path-based trigger filtering |
| `:build-dependencies` | `false` | Job dependency graphs |
| `:cron-scheduling` | `false` | Database-backed cron schedules |
| `:webhook-replay` | `false` | Webhook replay from stored payloads |
| `:auto-merge` | `false` | Auto-merge PRs on success |

### New Environment Variables (16)

| Variable | Feature |
|----------|---------|
| `CHENGIS_FEATURE_PR_STATUS_CHECKS` | Enable PR status checks |
| `CHENGIS_FEATURE_BRANCH_OVERRIDES` | Enable branch-based overrides |
| `CHENGIS_FEATURE_MONOREPO_FILTERING` | Enable monorepo path filtering |
| `CHENGIS_FEATURE_BUILD_DEPENDENCIES` | Enable build dependency graphs |
| `CHENGIS_FEATURE_CRON_SCHEDULING` | Enable cron scheduling |
| `CHENGIS_FEATURE_WEBHOOK_REPLAY` | Enable webhook replay |
| `CHENGIS_FEATURE_AUTO_MERGE` | Enable auto-merge on success |
| `CHENGIS_CRON_*` | Cron scheduling configuration |
| `CHENGIS_AUTO_MERGE_*` | Auto-merge configuration |
| `CHENGIS_SCM_GITEA_*` | Gitea SCM provider configuration |
| `CHENGIS_SCM_BITBUCKET_*` | Bitbucket SCM provider configuration |

### Migrations 044-047

- 044-047: Tables for PR checks, build dependencies, cron schedules, and related indexes (both SQLite and PostgreSQL)

### Test Suite
- **838 tests, 2,849 assertions — all passing**
- 14 new source files, 13 new test files added in Phase 6
- 160 new tests added in Phase 6

---

## [Unreleased] — Phase 5: Observability & Analytics

### Feature 5a: Grafana Dashboards

- **Pre-built dashboards** — Grafana JSON provisioning files for Prometheus metrics
- **Three dashboards** — Overview (build success rate, duration, queue depth), Agents (utilization, circuit breaker), Security (login attempts, rate limits, lockouts)
- **Provisioning configs** — Datasource and dashboard YAML for auto-import
- **Setup guide** — `docs/grafana-setup.md` with step-by-step instructions

### Feature 5b: Build Tracing

- **Custom span-based tracing** — Lightweight distributed tracing stored in DB, avoiding heavyweight OpenTelemetry Java SDK
- **Span lifecycle** — `start-span!` / `end-span!` / `with-span` macro for wrapping build stages and steps
- **Waterfall visualization** — CSS-based span waterfall chart on trace detail page
- **OTLP export** — Export traces as OTLP-compatible JSON for Jaeger/Tempo import
- **Probabilistic sampling** — Configurable sample rate (default 1.0)
- **Feature flag** — `:tracing` (default false) for safe rollout
- **New source**: `src/chengis/engine/tracing.clj`, `src/chengis/db/trace_store.clj`, `src/chengis/web/views/traces.clj`
- **Migration 040** — `trace_spans` table with indexes on trace_id, build_id, created_at, org_id

### Feature 5c: Build Analytics Dashboard

- **Precomputed analytics** — Daily/weekly build and stage statistics with chime-based scheduler
- **Trend charts** — CSS bar chart for build duration trends
- **Percentile computation** — p50, p90, p99 build/stage duration statistics
- **Flakiness scoring** — Formula: `1 - |2*success_rate - 1|` (0=stable, 1=max flaky) for stages
- **Slowest stages** — Ranked by p90 duration for performance optimization
- **HA singleton** — Analytics scheduler runs on one master via leader election (lock 100004)
- **Feature flag** — `:build-analytics` (default false)
- **New source**: `src/chengis/engine/analytics.clj`, `src/chengis/db/analytics_store.clj`, `src/chengis/web/views/analytics.clj`
- **Migration 041** — `build_analytics` and `stage_analytics` tables

### Feature 5d: Log Correlation Context

- **MDC-like context** — `with-build-context`, `with-stage-context`, `with-step-context` macros
- **Correlation IDs** — build-id, job-id, org-id, stage-name, step-name in all structured logs
- **JSON output** — Context keys included in JSON log format for ELK/Loki/Datadog
- **Setup guide** — `docs/log-aggregation.md` with sample configs
- **New source**: `src/chengis/engine/log_context.clj`

### Feature 5e: Browser Notifications

- **HTML5 Notification API** — Browser push notifications for build completion via SSE
- **Permission toggle** — Nav bar toggle button with localStorage persistence
- **Global SSE** — Org-scoped build completion events via `/api/events/global`
- **Feature flag** — `:browser-notifications` (default false)
- **Minimal JS exception** — Inline `<script>` for browser-native API (no HTML fallback exists)
- **New source**: `src/chengis/web/views/notifications.clj`

### Feature 5f: Build Cost Attribution

- **Agent-hours tracking** — Duration and cost computed per build for chargeback
- **Configurable cost rate** — `:default-cost-per-hour` setting (default 1.0)
- **Org/job summaries** — Aggregate cost views grouped by organization and job
- **Feature flag** — `:cost-attribution` (default false)
- **New source**: `src/chengis/engine/cost.clj`, `src/chengis/db/cost_store.clj`, `src/chengis/web/views/cost.clj`
- **Migration 042** — `build_cost_entries` table

### Feature 5g: Flaky Test Detection

- **Multi-format test parser** — JUnit XML, TAP, and generic "X passed, Y failed" pattern detection
- **Statistical analysis** — Track test results across builds, compute flakiness scores
- **Flakiness formula** — Tests with mixed pass/fail flagged when score exceeds configurable threshold
- **Configurable thresholds** — `:flakiness-threshold 0.15`, `:min-runs 5`, `:lookback-builds 30`
- **Feature flag** — `:flaky-test-detection` (default false)
- **New source**: `src/chengis/engine/test_parser.clj`, `src/chengis/db/test_result_store.clj`, `src/chengis/web/views/flaky_tests.clj`
- **Migration 043** — `test_results` and `flaky_tests` tables

### New Feature Flags (5)

| Flag | Default | Feature |
|------|---------|---------|
| `:tracing` | `false` | Distributed build tracing |
| `:build-analytics` | `false` | Precomputed analytics dashboard |
| `:browser-notifications` | `false` | HTML5 browser push notifications |
| `:cost-attribution` | `false` | Build cost tracking |
| `:flaky-test-detection` | `false` | Flaky test detection |

### New Environment Variables (13)

| Variable | Config Path | Default |
|----------|-------------|---------|
| `CHENGIS_FEATURE_TRACING` | `[:feature-flags :tracing]` | `false` |
| `CHENGIS_TRACING_SAMPLE_RATE` | `[:tracing :sample-rate]` | `1.0` |
| `CHENGIS_TRACING_RETENTION_DAYS` | `[:tracing :retention-days]` | `7` |
| `CHENGIS_FEATURE_BUILD_ANALYTICS` | `[:feature-flags :build-analytics]` | `false` |
| `CHENGIS_ANALYTICS_INTERVAL_HOURS` | `[:analytics :aggregation-interval-hours]` | `6` |
| `CHENGIS_ANALYTICS_RETENTION_DAYS` | `[:analytics :retention-days]` | `365` |
| `CHENGIS_FEATURE_BROWSER_NOTIFICATIONS` | `[:feature-flags :browser-notifications]` | `false` |
| `CHENGIS_FEATURE_COST_ATTRIBUTION` | `[:feature-flags :cost-attribution]` | `false` |
| `CHENGIS_COST_PER_HOUR` | `[:cost-attribution :default-cost-per-hour]` | `1.0` |
| `CHENGIS_FEATURE_FLAKY_TESTS` | `[:feature-flags :flaky-test-detection]` | `false` |
| `CHENGIS_FLAKY_THRESHOLD` | `[:flaky-detection :flakiness-threshold]` | `0.15` |
| `CHENGIS_FLAKY_MIN_RUNS` | `[:flaky-detection :min-runs]` | `5` |
| `CHENGIS_FLAKY_LOOKBACK` | `[:flaky-detection :lookback-builds]` | `30` |

### Migrations 040-043

- 040: `trace_spans` table for distributed build tracing
- 041: `build_analytics` and `stage_analytics` tables for precomputed analytics
- 042: `build_cost_entries` table for cost attribution
- 043: `test_results` and `flaky_tests` tables for flaky test detection

### Test Suite
- **678 tests, 2,529 assertions — all passing**
- 102 test files across 7 test subdirectories
- 91 new tests added in Phase 5
- New test files: tracing_test, trace_store_test, analytics_test, analytics_store_test, log_context_test, notifications_test, events_global_test, cost_test, cost_store_test, test_parser_test, test_result_store_test

---

## [Unreleased] — Phase 4: Build Performance & Caching

### Feature 4a: Parallel Stage Execution (DAG Mode)

- **DAG-based execution** — Stages can declare `:depends-on` for parallel execution; independent stages run concurrently
- **Kahn's topological sort** — Validates dependency graph, detects cycles, ensures all dependencies exist
- **Bounded concurrency** — `Semaphore`-based limit on parallel stages (configurable via `:parallel-stages {:max-concurrent 4}`)
- **Failure propagation** — Stage failure cancels all downstream dependents
- **Backward compatible** — No `:depends-on` → sequential mode (existing behavior unchanged)
- **DSL + YAML support** — `:depends-on` works in Clojure DSL, Chengisfile EDN, and YAML workflows
- **New source**: `src/chengis/engine/dag.clj` — DAG utilities (build-dag, topological-sort, ready-stages, has-dag?)

### Feature 4b: Docker Layer Caching

- **Persistent named volumes** — `:cache-volumes` in container config mounts Docker named volumes for dependency caches
- **Cross-build persistence** — Named volumes survive container removal, shared across builds on the same agent
- **Volume name validation** — Alphanumeric + hyphens only; rejects invalid characters
- **Mount path validation** — Absolute paths required; rejects relative paths, path traversal (`..`), and special characters
- **Container propagation** — Cache volumes propagate from pipeline-level → stage-level → step-level container config

### Feature 4c: Artifact/Dependency Caching

- **Content-addressable cache** — Cache keyed by `{{ hashFiles('package-lock.json') }}` SHA-256 expressions
- **Restore-keys prefix matching** — Fallback to partial cache matches when exact key misses
- **Immutable cache entries** — Once saved, cache entries are never overwritten (first-write-wins)
- **Configurable retention** — Evict cache entries older than N days (default 30)
- **Streaming file hashing** — 8KB buffer for SHA-256 computation to avoid OOM on large files
- **New source**: `src/chengis/engine/cache.clj`, `src/chengis/db/cache_store.clj`
- **Migration 037** — `cache_entries` table with UNIQUE(job_id, cache_key)

### Feature 4d: Build Result Caching

- **Stage fingerprinting** — SHA-256 of `git-commit | stage-name | sorted commands | sorted stable-env`
- **Build-specific env exclusion** — BUILD_ID, BUILD_NUMBER, WORKSPACE, JOB_NAME excluded from fingerprint to prevent false cache misses
- **Cache hit → skip** — Matching fingerprint with successful status skips stage execution, reuses cached results
- **New source**: `src/chengis/engine/stage_cache.clj`
- **Migration 038** — `stage_cache` table with UNIQUE(job_id, fingerprint)

### Feature 4e: Resource-Aware Agent Scheduling

- **Weighted scoring** — `score = (1 - load_ratio) × 0.6 + cpu_score × 0.2 + memory_score × 0.2`
- **Minimum resource filtering** — Exclude agents below required CPU cores or memory GB
- **Backward compatible** — No resource requirements → original least-loaded selection
- **Stage-level resources** — `:resources {:cpu 4 :memory 8}` on stage definitions in DSL and YAML

### Feature 4f: Incremental Artifact Storage

- **Block-level delta compression** — Files split into 4KB blocks, MD5 hash per block, store only changed blocks
- **Savings threshold** — Delta applied only when >20% storage savings achieved
- **Appended block support** — Handles files that grow beyond previous version length
- **New source**: `src/chengis/engine/artifact_delta.clj`
- **Migration 039** — `delta_base_id`, `is_delta`, `original_size_bytes` columns on `build_artifacts`

### Feature 4g: Build Deduplication

- **Commit-based dedup** — Skip redundant builds on the same job + git commit within a configurable time window
- **Configurable window** — Default 10 minutes via `:deduplication {:window-minutes 10}`
- **Status filtering** — Only dedup against successful, running, or queued builds; failed builds always re-run
- **SQLite datetime compatibility** — Cutoff formatted as `yyyy-MM-dd HH:mm:ss` for cross-DB compatibility

### Code Review Fixes (8 bugs resolved)

- **Stage fingerprint env exclusion** — BUILD_ID, BUILD_NUMBER, WORKSPACE, JOB_NAME no longer cause false cache misses
- **apply-delta appended blocks** — Delta reconstruction now handles files that grow beyond base file length
- **SQLite datetime format** — `find-recent-build-by-commit` and `delete-cache-entries!` use `yyyy-MM-dd HH:mm:ss` format instead of ISO-8601 for SQLite compatibility
- **cache.clj streaming hash** — `sha256-file` uses 8KB streaming buffer instead of `Files/readAllBytes` to avoid OOM
- **cache.clj null guard** — `copy-directory!` and `directory-size` handle null `.listFiles()` results
- **docker.clj mount path validation** — New `validate-mount-path!` rejects relative paths, path traversal, and special characters
- **build_runner.clj dedup wiring** — `check-dedup` now called at top of `execute-build!` (was dead code)

### New Feature Flags (7)

| Flag | Default | Feature |
|------|---------|---------|
| `:parallel-stage-execution` | `false` | DAG-based parallel stage execution |
| `:docker-layer-cache` | `false` | Docker layer caching via named volumes |
| `:artifact-cache` | `false` | Content-addressable dependency caching |
| `:build-result-cache` | `false` | Stage fingerprint result caching |
| `:resource-aware-scheduling` | `false` | CPU/memory-aware agent dispatch |
| `:incremental-artifacts` | `false` | Block-level delta compression |
| `:build-deduplication` | `false` | Commit-based build dedup |

### New Environment Variables (12)

| Variable | Config Path | Default |
|----------|-------------|---------|
| `CHENGIS_FEATURE_PARALLEL_STAGES` | `[:feature-flags :parallel-stage-execution]` | `false` |
| `CHENGIS_PARALLEL_STAGES_MAX` | `[:parallel-stages :max-concurrent]` | `4` |
| `CHENGIS_FEATURE_DOCKER_LAYER_CACHE` | `[:feature-flags :docker-layer-cache]` | `false` |
| `CHENGIS_FEATURE_ARTIFACT_CACHE` | `[:feature-flags :artifact-cache]` | `false` |
| `CHENGIS_CACHE_ROOT` | `[:cache :root]` | `cache` |
| `CHENGIS_CACHE_MAX_SIZE_GB` | `[:cache :max-size-gb]` | `10` |
| `CHENGIS_CACHE_RETENTION_DAYS` | `[:cache :retention-days]` | `30` |
| `CHENGIS_FEATURE_BUILD_RESULT_CACHE` | `[:feature-flags :build-result-cache]` | `false` |
| `CHENGIS_FEATURE_RESOURCE_SCHEDULING` | `[:feature-flags :resource-aware-scheduling]` | `false` |
| `CHENGIS_FEATURE_INCREMENTAL_ARTIFACTS` | `[:feature-flags :incremental-artifacts]` | `false` |
| `CHENGIS_FEATURE_BUILD_DEDUP` | `[:feature-flags :build-deduplication]` | `false` |
| `CHENGIS_DEDUP_WINDOW_MINUTES` | `[:deduplication :window-minutes]` | `10` |

### Migrations 037-039

- 037: `cache_entries` table for artifact/dependency cache metadata
- 038: `stage_cache` table for build result caching (stage fingerprints)
- 039: `build_artifacts` table gains `delta_base_id`, `is_delta`, `original_size_bytes` columns

### Test Suite
- **587 tests, 2,275 assertions — all passing**
- 88 test files across 7 test subdirectories
- 62 new tests added in Phase 4 + code review
- New test files: dag_test, executor_dag_test, docker_cache_test, cache_test, cache_store_test, stage_cache_test, resource_scheduling_test, artifact_delta_test, build_dedup_test

---

## [Unreleased] — Security Review II

### Security Fixes (5 findings, all resolved)

- **[P1] Event replay auth bypass** — `/api/builds/:id/events/replay` was excluded from authentication via the distributed API path denylist. Replaced fragile suffix-denylist with explicit allowlist of 4 agent write endpoint suffixes (`/agent-events`, `/result`, `/artifacts`, `/heartbeat`). Added RBAC (`wrap-require-role :viewer`) to the replay endpoint. Added `/startup` to public paths for K8s probes.
- **[P1] Policy evaluations not org-scoped** — `list-evaluations` query lacked org-id filtering, exposing cross-tenant policy evaluation data. Added conditional JOIN from `policy_evaluations` to `policies` table when org-id is present, with table-qualified column names to avoid ambiguity.
- **[P1] Cross-org policy delete** — `delete-policy!` did not verify the requesting org owns the policy before deleting child rows. Wrapped in `jdbc/with-transaction` with ownership verification SELECT before any deletes. Handler catches `ExceptionInfo` and returns 404.
- **[P1] Audit hash-chain uses SQLite-specific `rowid`** — `get-latest-hash` and `query-audits-asc` ordered by `rowid`, which doesn't exist in PostgreSQL. Added `seq_num` column via migration 036, with auto-incrementing insertion-order semantics. All ordering now uses `seq_num`.
- **[P2] Hash-chain verification doesn't verify content integrity** — `recompute-entry-hash` excluded `:detail` and `:timestamp` fields, meaning the hash only checked linkage, not content. Introduced `hash-fields` vector matching exactly the fields used at insert time. `verify-hash-chain` now checks both prev_hash linkage AND entry_hash recomputation.

### Migration 036

- `seq_num` column on `audit_logs` for cross-DB insertion-order tiebreaking (replaces SQLite-specific `rowid`)

### Regression Tests

- 9 new tests, 36 assertions covering all 5 security fixes
- Auth bypass: replay endpoint requires auth, agent write endpoints still exempt
- Cross-org: policy evaluations org-scoped, cross-org delete blocked
- Hash chain: seq_num ordering, content tamper detection, prev_hash linkage, org-scoped entries

### Test Suite
- **525 tests, 2,126 assertions — all passing**
- 82 test files across 7 test subdirectories

---

## [Unreleased] — Phase 3: Kubernetes & High Availability

### Feature 3a: Persistent Agent Registry

- **Write-through cache** — Agent registry mutations write to DB first, then update in-memory atom. All existing consumers unchanged (read from atom)
- **DB hydration** — `hydrate-from-db!` loads all agents from DB on master startup, restoring state after restarts
- **Graceful degradation** — When `ds-ref` is nil (tests, CLI), all operations fall back to atom-only mode
- **New store** — `db/agent_store.clj` with upsert, heartbeat update, status/builds update, delete, load-all, and get-by-id operations
- **Migration 035** — Adds `current_builds` column to existing `agents` table

### Feature 3b: Leader Election + HA Singletons

- **PostgreSQL advisory locks** — `pg_try_advisory_lock(bigint)` for non-blocking, session-scoped leadership. Auto-released on connection drop for instant failover
- **Poll-based leadership** — Background daemon thread polls every N seconds (default 15s). On acquire: calls `start-fn`. On loss: calls `stop-fn`
- **Singleton services** — Queue processor (lock 100001), orphan monitor (100002), and retention scheduler (100003) run on exactly one master when `CHENGIS_HA_ENABLED=true`
- **SQLite compatibility** — All locks granted unconditionally in SQLite mode (single master assumed)

### Feature 3c: Enhanced Health/Readiness Probes

- **Startup probe** — `GET /startup` returns 503 until initialization completes, then 200. Prevents premature K8s traffic routing
- **Enhanced readiness** — `GET /ready` includes queue depth and agent summary (total, online, offline, capacity)
- **Instance identity** — `GET /health` includes `instance-id` from HA config
- **Queue depth helper** — `build-queue/queue-depth` counts pending items

### Feature 3d: Kubernetes Manifests + Helm Chart

- **Raw manifests** — `k8s/base/` directory with namespace, ConfigMap, Secret, master Deployment (2 replicas), master Service, agent Deployment, PVC, HPA, and Ingress YAML
- **Helm chart** — `helm/chengis/` with Chart.yaml, values.yaml, and templated resources (ConfigMap, Secret, Deployments, Service, PVC, Ingress, HPA, ServiceMonitor)
- **HA Docker Compose** — `docker-compose.ha.yml` override adds PostgreSQL 16 + second master for local multi-master testing
- **Probe configuration** — Master pods use startupProbe (/startup, 150s max), livenessProbe (/health, 10s), readinessProbe (/ready, 5s)

### New Environment Variables

- `CHENGIS_FEATURE_PERSISTENT_AGENTS` — Enable DB-backed agent registry (default: true)
- `CHENGIS_HA_ENABLED` — Enable leader election for multi-master (default: false)
- `CHENGIS_HA_LEADER_POLL_MS` — Leader election poll interval in ms (default: 15000)
- `CHENGIS_HA_INSTANCE_ID` — Unique master instance identifier (auto-generated if not set)

### Test Suite
- **516 tests, 2,090 assertions — all passing** (before security review)
- New test files: agent_store_test, agent_registry_persistent_test, leader_election_test, probes_test

---

## [Unreleased] — Phase 2: Distributed Dispatch & Hardening

### Feature 2a: Config Hardening + Dispatcher Wiring

- **Dispatcher integration** — All build trigger paths (web UI, CLI retry, webhooks) now route through `dispatcher/dispatch-build!` when the `distributed-dispatch` feature flag is enabled
- **Fallback-local default flipped** — `:fallback-local` now defaults to `false` (fail-fast when no agents available in distributed mode)
- **Configurable heartbeat timeout** — Agent offline detection threshold configurable via `CHENGIS_DISTRIBUTED_HEARTBEAT_TIMEOUT_MS` (default 90s)
- **New feature flag** — `:distributed-dispatch` gates the dispatcher wiring, allowing safe rollout
- **New environment variables** — `CHENGIS_DISTRIBUTED_HEARTBEAT_TIMEOUT_MS`, `CHENGIS_DISTRIBUTED_FALLBACK_LOCAL`, `CHENGIS_DISTRIBUTED_QUEUE_ENABLED`, `CHENGIS_FEATURE_DISTRIBUTED_DISPATCH`
- **Agent registry config** — `agent_registry.clj` accepts runtime configuration via `set-config!` instead of hardcoded timeout

### Feature 2b: Build Attempt Model

- **Attempt tracking** — Each build retry gets an `attempt_number` (starting at 1), with `root_build_id` linking all retries of the same build
- **Parent chain resolution** — `get-root-build-id` follows the parent chain to find the original build
- **Attempt listing** — `list-attempts` returns all retries of a build ordered by attempt number
- **UI integration** — Build detail page shows "Attempt #N" badge and retry history section
- **Migration 032** — Adds `attempt_number` and `root_build_id` columns to builds table

### Feature 2c: Durable Build Events

- **Event persistence** — Build events (stage/step start, completion, errors) persisted to `build_events` table before broadcasting via core.async
- **Time-ordered IDs** — Events use `<epoch_ms>-<seq_counter>-<uuid>` format for guaranteed insertion-order retrieval, avoiding SQLite's second-level timestamp precision
- **Event replay API** — `GET /api/builds/:id/events/replay` returns historical events as JSON with cursor-based pagination (`?after=<event-id>`) and event type filtering
- **Graceful degradation** — DB persistence failures are logged but don't block SSE broadcast
- **Retention cleanup** — `cleanup-old-events!` purges events older than configurable retention period
- **Migration 033** — Creates `build_events` table with indices on `build_id` and `event_type`

### Feature 2d: Plugin Trust & Docker Policy

- **Plugin allowlist** — External plugins gated by DB-backed trust policy; only plugins with `allowed=true` are loaded
- **Docker image policies** — Priority-ordered glob-pattern matching for allowed registries, denied images, and allowed images
- **Regex injection safety** — Docker policy patterns use `Pattern/quote` for safe glob-to-regex conversion
- **Policy enforcement** — Docker step executor checks image against org-scoped policies before pulling/running
- **Backward compatibility** — When no DB is provided, all external plugins load (no-DB fallback)
- **Admin UI** — Plugin policy and Docker policy management pages under `/admin/plugins/policies` and `/admin/docker/policies`
- **Migration 034** — Creates `plugin_policies` and `docker_policies` tables with org-scoped indices

### P0 Fixes (from forensic review)

- **Atomic dequeue race** — Build queue `dequeue!` wrapped in transaction with row-level locking
- **Webhook org-id** — Webhook-triggered builds inherit org-id from the matched job
- **Retry handler `:failed` dispatch** — Fixed silent local fallback when dispatcher returns `:failed`; now properly errors
- **Webhook `:failed` dispatch** — Same fix applied to webhook build trigger path

### Code Quality

- **Migration 032 down** — Fixed missing `agent_id`, `dispatched_at` columns and `DEFAULT 'default-org'` on `org_id` in SQLite rollback
- **Docstring accuracy** — Fixed misleading docstrings in `build_event_store.clj` and `handlers.clj`

### Test Suite
- **488 tests, 1,993 assertions — all passing**
- 77 test files across 7 test subdirectories
- 34 new tests added in Phase 2

---

## [1.0.0] — Phase 1: Governance Foundation

### Policy Engine

- **Org-scoped policies** — Policy store with CRUD operations, scope filtering, and priority ordering
- **Policy evaluation** — `evaluate-policies` checks build context against applicable policies with short-circuit evaluation
- **Pipeline policy integration** — Policy checks wired into executor before build execution
- **Admin UI** — Policy management page under `/admin/policies`

### Artifact Checksums

- **SHA-256 checksums** — Artifacts computed and stored with SHA-256 hash on collection
- **Integrity verification** — Artifact downloads validate checksum before serving

### Compliance Reports

- **Build compliance** — Compliance store tracks policy evaluation results per build
- **Compliance views** — Admin compliance dashboard showing policy pass/fail across builds
- **Export support** — Compliance data available for audit export

### Feature Flags

- **Runtime feature toggling** — `feature-flags/enabled?` checks config map for boolean flags
- **Config-driven** — Feature flags set via `:feature-flags` in config or `CHENGIS_FEATURE_*` env vars
- **Gate pattern** — New features can be rolled out incrementally behind flags

### Migrations 029–031

- 029: Policy store tables (`policies`)
- 030: Compliance and feature flag support (`compliance_results`, artifact checksum columns)
- 031: Artifact integrity columns

### Test Suite
- 449 tests, 1,909 assertions at end of Phase 1

---

## [Unreleased - Pre-Phase] — Security Remediation

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
- 403 tests, 1,781 assertions — all passing
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
| Chengis (self) | Clojure | 838 passed, 2,849 assertions | varies | SUCCESS |
