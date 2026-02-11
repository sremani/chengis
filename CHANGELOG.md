# Changelog

All notable changes to Chengis are documented in this file.

## [Unreleased] — Phase 12: Infrastructure-as-Code Integration

### Feature 12a: IaC Project Detection & Configuration

- **Project detection** — Auto-detect Terraform (`.tf` files), Pulumi (`Pulumi.yaml`), and CloudFormation (`template.yaml`/`.json`) projects
- **Project configuration** — Per-project settings with org-scoped isolation, repo path, and JSON config
- **Multi-tool support** — Unified IaC project model supporting Terraform, Pulumi, and CloudFormation
- **New source**: `src/chengis/db/iac_store.clj`, `src/chengis/engine/iac.clj`
- **Migration 070**: `iac_projects` and `iac_plans` tables with org/project indexes

### Feature 12b: IaC Step Executor Plugins

- **Terraform plugin** — `StepExecutor` for Terraform init/plan/apply/destroy with workspace and variable support
- **Pulumi plugin** — `StepExecutor` for Pulumi preview/up/destroy with stack management
- **CloudFormation plugin** — `StepExecutor` for CloudFormation create/update/delete with template validation
- **Graceful degradation** — Tool availability detection at runtime with clear error messages
- **New source**: `src/chengis/plugin/builtin/terraform.clj`, `src/chengis/plugin/builtin/pulumi.clj`, `src/chengis/plugin/builtin/cloudformation.clj`

### Feature 12c: IaC State Management

- **State snapshots** — Compressed state storage with versioning and checksum verification
- **State locking** — Atomic lock/unlock to prevent concurrent state modifications
- **Conflict detection** — Detect conflicting state changes before apply
- **Version history** — Full state version history with rollback capability
- **New source**: `src/chengis/engine/iac_state.clj`
- **Migration 071**: `iac_states` and `iac_state_locks` tables

### Feature 12d: Plan Parsing & Cost Estimation

- **Plan parsing** — Parse Terraform/Pulumi/CloudFormation plan output for resource change extraction
- **Resource change visualization** — Categorize changes as create/update/delete with resource details
- **Cost estimation** — Estimate monthly infrastructure costs from plan resource changes
- **Per-resource pricing** — Detailed per-resource cost breakdown with currency support
- **New source**: `src/chengis/db/iac_cost_store.clj`, `src/chengis/engine/iac_cost.clj`
- **Migration 072**: `iac_cost_estimates` table

### Feature 12e: IaC Dashboard

- **Dashboard overview** — IaC project listing with status, recent plans, and cost trends
- **Plan visualization** — Resource change visualization with create/update/delete breakdown
- **State history** — State version timeline with compression details
- **Cost trends** — Monthly cost estimates per project
- **New source**: `src/chengis/web/views/iac.clj`, `src/chengis/web/views/iac_plans.clj`
- **Migration 073**: Dashboard performance indexes

### Feature 12f: Policy Integration

- **OPA integration** — Hook IaC plans into existing OPA policy engine for compliance checks
- **Pre-apply checks** — Policy evaluation before infrastructure changes are applied
- **Feature flag gated** — `:iac-policy-enforcement` flag controls policy integration

### Modified Existing Files

- **config.clj** — IaC feature flags and configuration options
- **handlers.clj** — IaC handler functions for projects, plans, states, and dashboard
- **routes.clj** — IaC route tree under `/iac`
- **layout.clj** — "IaC" navigation link

### Infrastructure

- **7 new feature flags** (49 total): `infrastructure-as-code`, `terraform-execution`, `pulumi-execution`, `cloudformation-execution`, `iac-state-management`, `iac-cost-estimation`, `iac-policy-enforcement`
- **4 new migrations** (070-073), 73 total migration versions
- **10 new source files**, 8 new test files
- **20 builtin plugins** (was 17): added Terraform, Pulumi, CloudFormation step executors
- **5 new tables**: `iac_projects`, `iac_plans`, `iac_states`, `iac_state_locks`, `iac_cost_estimates`
- **1,445 tests, 4,687 assertions — all passing**

---

## Phase 11: Deployment & Release Orchestration

### Feature 11a: Environment Definitions

- **Environment model** — Ordered environments (dev=10, staging=20, prod=30) with org-scoped isolation
- **Environment locking** — Atomic lock/unlock via `UPDATE WHERE locked=0` to prevent concurrent deployments
- **Approval gating** — Per-environment `requires_approval` flag for promotion workflows
- **Auto-promote** — Optional `auto_promote` flag for pipeline-driven promotions
- **Config JSON** — Flexible per-environment configuration stored as JSON text
- **New source**: `src/chengis/db/environment_store.clj`, `src/chengis/web/views/environments.clj`
- **Migration 063**: `environments` table with org/order index

### Feature 11d: Release Management

- **Semantic versioning** — Releases with version uniqueness per org+job, lifecycle states (draft → published → deprecated)
- **Auto-versioning** — `suggest-next-version` parses existing semver, increments patch; `auto-version-release!` creates and publishes in one step
- **Build validation** — Release creation requires successful build status
- **New source**: `src/chengis/db/release_store.clj`, `src/chengis/engine/release.clj`, `src/chengis/web/views/releases.clj`
- **Migration 064**: `releases` table with org/job and build indexes

### Feature 11b: Artifact Promotion

- **Promotion pipeline** — Track artifact flow between environments (dev → staging → prod)
- **Eligibility checks** — Validates build success before allowing promotion
- **Approval gates** — Promotions to `requires_approval` environments await explicit approval
- **Superseding pattern** — Transactional: marks previous active artifact as superseded, inserts new active
- **New source**: `src/chengis/db/promotion_store.clj`, `src/chengis/engine/promotion.clj`, `src/chengis/web/views/promotions.clj`
- **Migration 065**: `artifact_promotions` + `environment_artifacts` tables

### Feature 11c: Deployment Strategies

- **Strategy types** — Direct, blue-green, canary, and rolling deployment strategies as first-class DB entities
- **Default seeding** — `seed-default-strategies!` populates standard strategies with sensible configs
- **Per-environment defaults** — Environments can reference a default strategy via `default_strategy_id`
- **Type-specific config** — JSON configuration per strategy (e.g., canary increments, batch percentages)
- **New source**: `src/chengis/db/strategy_store.clj`, `src/chengis/web/views/strategies.clj`
- **Migration 066**: `deployment_strategies` table + ALTER environments for `default_strategy_id`

### Feature 11e: Deployment Execution & History

- **Execution engine** — Lock environment → execute strategy steps → unlock, with automatic step tracking
- **Strategy step generation** — Direct (1 step), blue-green (4 steps), canary (N steps per increment), rolling (batch steps)
- **Rollback support** — `rollback-deployment!` finds previous successful deployment and creates reverse deployment
- **Cancellation** — `cancel-deployment!` for pending/in-progress deployments
- **Concurrent prevention** — Environment locking ensures single active deployment per environment
- **Status flow**: `pending → in-progress → succeeded | failed | cancelled`
- **New source**: `src/chengis/db/deployment_store.clj`, `src/chengis/engine/deployment.clj`, `src/chengis/web/views/deployments.clj`
- **Migration 067**: `deployments` + `deployment_steps` tables with indexes

### Feature 11f: Environment Health Checks

- **Check types** — HTTP (URL + expected status + timeout) and command-based (process + exit code)
- **Result tracking** — Health check results stored per check per deployment
- **Polling loop** — `wait-for-healthy!` with configurable timeout, interval, and retries
- **Graceful degradation** — Environments without checks auto-pass health verification
- **New source**: `src/chengis/db/health_check_store.clj`, `src/chengis/engine/health_check.clj`
- **Migration 068**: `health_check_definitions` + `health_check_results` tables

### Feature 11g: Deployment Dashboard

- **Unified view** — Dashboard showing environment cards, recent deployments, promotion pipeline, and deployment activity
- **Navigation** — "Deploy" link added to main navigation between Analytics and Search
- **Full route tree** — `/deploy` (dashboard), `/deploy/releases`, `/deploy/promotions`, `/deploy/strategies`, `/deploy/deployments`
- **Admin routes** — `/admin/environments` with CRUD, lock/unlock operations
- **New source**: `src/chengis/web/views/deploy_dashboard.clj`
- **Migration 069**: Dashboard performance indexes

### Infrastructure

- **7 new feature flags** (42 total): `environment-definitions`, `release-management`, `artifact-promotion`, `deployment-strategies`, `deployment-execution`, `environment-health-checks`, `deployment-dashboard`
- **~15 new environment variables** for all Phase 11 features + deployment config
- **7 new migrations** (063-069), 69 total migration versions
- **16 new source files**, 11 new test files
- **1,352 tests, 4,368 assertions — all passing**

---

## Phase 10: Scale & Performance

### Feature 10a: Build Log Streaming Optimization

- **Chunked log storage** — Splits large build logs into manageable chunks (default 1000 lines) stored in `build_log_chunks` table
- **Streaming process execution** — `execute-command-streaming` with ProcessBuilder, parallel stdout/stderr capture via futures
- **Line-by-line callbacks** — `on-line` callback for real-time SSE streaming, `on-chunk` callback for batch persistence
- **Secret masking** — Automatic masking of sensitive values in streaming output
- **htmx infinite scroll** — Chunked log viewer with `hx-trigger="revealed"` for lazy chunk loading
- **New source**: `src/chengis/db/log_chunk_store.clj`, `src/chengis/engine/streaming_process.clj`, `src/chengis/web/views/log_stream.clj`
- **Migration 059**: `build_log_chunks` table with indexes

### Feature 10b: API Pagination (Cursor-Based)

- **Cursor-based pagination** — Base64-encoded `"timestamp|id"` cursors for deterministic, gap-free pagination
- **Shared pagination module** — `pagination.clj` with `encode-cursor`, `decode-cursor`, `apply-cursor-where`, `paginated-response`
- **Build store pagination** — `list-builds` accepts `:cursor`, `:limit`, `:cursor-mode` kwargs; backward compatible (returns vector without cursor)
- **Job store pagination** — `list-jobs` with cursor support
- **Audit store pagination** — `query-audits` with cursor support alongside existing offset
- **Paginated response envelope** — `{:items [...] :has-more bool :next-cursor str}`
- **New source**: `src/chengis/db/pagination.clj`
- **Migration 060**: Cursor pagination indexes on `builds`, `audit_logs`, `jobs`

### Feature 10c: Database Partitioning

- **Monthly range partitions** — PostgreSQL-only partitioning for `builds`, `build_events`, `audit_logs`
- **Partition metadata tracking** — `partition_metadata` table tracks partition state across DB types
- **Maintenance cycle** — Automated creation of future partitions and cleanup of expired ones
- **Graceful degradation** — SQLite stores metadata only; actual partitioning requires PostgreSQL
- **New source**: `src/chengis/db/partitioning.clj`
- **Migration 061**: `partition_metadata` tracking table

### Feature 10d: Read Replicas

- **Query routing** — `RoutedDatasource` record wrapping primary and optional replica datasources
- **Opt-in reads** — `read-ds` returns replica when available, primary otherwise (zero behavior change for single-DB)
- **Write safety** — `write-ds` always returns primary datasource
- **Passthrough** — Non-routed datasources pass through unchanged
- **New source**: `src/chengis/db/query_router.clj`

### Feature 10e: Agent Connection Pooling

- **Per-agent pools** — Connection state atom tracking URL, health, and failure counts per agent
- **HTTP keep-alive** — `Connection: keep-alive` and `Keep-Alive: timeout=N` headers for TCP reuse
- **Health tracking** — Consecutive failure counting with configurable threshold
- **Async requests** — Promise-based `post!` and `get!` with http-kit callback pattern
- **Pool lifecycle** — `close-pool!` on agent deregistration, `close-all-pools!` on shutdown
- **New source**: `src/chengis/distributed/agent_http.clj`

### Feature 10f: Event Bus Backpressure

- **Critical event classification** — Build lifecycle events (started/completed/cancelled, stage/step) MUST NOT be silently dropped
- **Adaptive publish** — Critical events use `async/alt!!` with configurable timeout; non-critical events use `async/offer!`
- **Queue depth sampling** — Background thread periodically samples channel buffer depth for monitoring
- **Integration** — `events.clj` uses backpressure-aware publish when feature flag enabled
- **SSE sliding buffer** — Subscriber channels use `sliding-buffer 512` (drop oldest for slow clients)
- **New source**: `src/chengis/engine/event_backpressure.clj`

### Feature 10g: Multi-Region Support

- **Region-aware scoring** — `locality-bonus` adds configurable weight (default 0.3) for same-region agents
- **Score capping** — `region-aware-score` capped at 1.5 to prevent excessive locality bias
- **Agent registration** — Agents declare region on registration; stored in `agents.region` column
- **New source**: `src/chengis/distributed/region.clj`
- **Migration 062**: `agents.region` column

### Infrastructure

- **7 new feature flags** (35 total): `chunked-log-storage`, `cursor-pagination`, `db-partitioning`, `read-replicas`, `agent-connection-pooling`, `event-bus-backpressure`, `multi-region`
- **~25 new environment variables** for all Phase 10 features
- **4 new migrations** (059-062), 62 total migration versions
- **9 new source files**, 9 new test files
- **1,257 tests, 4,085 assertions — all passing**

### Code Review

5 bugs fixed across 4 files (1 high, 2 medium, 2 low):

- **[HIGH] agent_http.clj — Atom state corruption** — `record-success!`/`record-failure!` used `swap! update` with `when` guard that returned `nil` for missing keys (race with `close-pool!`), inserting nil into the atom map. Fixed with `contains?` guard on the whole map
- **[MEDIUM] partitioning.clj — SQL injection in DDL** — Raw `format` string interpolation for table names in `CREATE TABLE`/`ALTER TABLE` allowed arbitrary input. Added `validate-table-name!` function that checks against hardcoded `partitionable-tables` whitelist
- **[MEDIUM] partitioning.clj — Invalid HoneySQL CASE** — `[:count [:case :status [:inline "active"] 1]]` was invalid HoneySQL syntax. Fixed with `[:sum [:case [:= :status "active"] [:inline 1] :else [:inline 0]]]`
- **[MEDIUM] region.clj — Clojure falsiness of 0.0** — `(or weight 0.3)` returns `0.3` when weight is `0.0` because `0.0` is falsy in Clojure. Fixed with `(if (some? weight) weight 0.3)` pattern
- **[LOW] region.clj — Empty string region matching** — Empty strings `""` were treated as valid regions, causing `(same-region? "" "")` to return `true`. Added `str/blank?` guards
- **event_backpressure.clj — Fragile core.async internals** — Replaced direct `ManyToManyChannel` type cast with reflection-based buffer access wrapped in `try/catch` for graceful degradation

---

## Phase 9: Developer Experience

### Feature 9a: Pipeline Linter

- **Comprehensive validation** — Structural, semantic, and expression checks for all three pipeline formats (Clojure DSL, EDN, YAML)
- **Structural checks** — Validates required fields, unique stage/step names, no empty stages, valid step types
- **Semantic checks** — DAG dependency references, circular dependency detection, Docker image requirements, timeout validation, matrix/parameter/cache config validation
- **Expression checks** — YAML `${{ }}` expression syntax validation with namespace checking
- **Warning-level hints** — Single-step stages, long timeouts, duplicate env vars, missing descriptions
- **CLI integration** — `chengis pipeline lint <file>` for offline validation before push
- **Web UI** — Interactive linter page at `/admin/linter` with htmx-powered live results
- **Content-based linting** — Lint raw content strings from the web UI (EDN and YAML formats)
- **New source**: `src/chengis/engine/linter.clj`, `src/chengis/web/views/linter.clj`

### Feature 9b: Pipeline Visualization (DAG)

- **DAG layout algorithm** — Server-side column computation: depth = 1 + max(depth of dependencies)
- **SVG arrow rendering** — Bezier curve arrows connecting dependent stages with status-colored nodes
- **Status coloring** — Green (success), red (failure), blue (running), yellow (pending), gray (default)
- **Stage node rendering** — 192×80px nodes with stage name, step count, and status badge
- **Job detail integration** — "View Pipeline" button on job pages linking to `/jobs/:name/pipeline`
- **Build detail integration** — DAG visualization shown on build detail when pipeline has dependencies
- **New source**: `src/chengis/web/views/pipeline_viz.clj`
- **New route**: `GET /jobs/:name/pipeline`

### Feature 9c: Build Log Search

- **Full-text search** — SQL LIKE-based search across build logs (database-agnostic)
- **Filter support** — Filter by job name, build number range, and build status
- **Line highlighting** — Clojure-side line highlighting with configurable context lines
- **Pagination** — Paginated results with total count
- **htmx integration** — Real-time search results via htmx POST
- **Nav integration** — "Search" link added to main navigation
- **New source**: `src/chengis/db/log_search_store.clj`, `src/chengis/web/views/log_search.clj`
- **New route**: `GET/POST /search/logs`

### Feature 9d: Mobile-Responsive UI

- **CSS-only hamburger menu** — Hidden checkbox + `peer`/`peer-checked:flex` pattern (no JavaScript)
- **Responsive nav** — `hidden peer-checked:flex md:flex flex-col md:flex-row` for mobile-first layout
- **Responsive nav links** — Full-width on mobile (`w-full`), auto-width on desktop (`md:w-auto`)
- **Hamburger hidden on desktop** — `md:hidden` class on hamburger label
- **Viewport meta tag** — `width=device-width, initial-scale=1.0` for proper mobile scaling
- **Responsive grid layouts** — Grid columns adapt to screen size across all pages

### Feature 9e: Dark/Light Theme Toggle

- **Tailwind dark mode** — `darkMode: 'class'` strategy with `dark:` prefix classes
- **Theme persistence** — localStorage-based theme persistence across sessions
- **Theme initialization** — Pre-render script prevents flash of wrong theme using `localStorage.getItem('theme')` and `prefers-color-scheme`
- **Theme toggle button** — 🌓 button in nav with `document.documentElement.classList.toggle('dark')`
- **Dark mode on all components** — status-badge, stat-card, build-table, card, page-header, pipeline-graph, build-history-chart, build-stats-row
- **Dark mode on layout** — Body, nav, footer all have dark mode variants

### Feature 9f: Build Comparison

- **Side-by-side diff** — Compare two builds with stage, step, timing, and artifact differences
- **Build selection form** — Select two builds from dropdown with optional job filter
- **Summary section** — Status changed?, duration delta, stages added/removed
- **Stage comparison table** — Per-stage status, duration, and delta with nested step rows
- **Step-level comparison** — Exit codes, status, and duration deltas per step
- **Artifact comparison** — Files only in A, only in B, in both, and size changes
- **Duration formatting** — Human-readable duration formatting (seconds, minutes, hours)
- **Delta badges** — Green for faster (negative delta), red for slower (positive delta)
- **Timestamp parsing** — Handles both ISO-8601 and SQLite timestamp formats
- **New source**: `src/chengis/engine/build_compare.clj`, `src/chengis/web/views/build_compare.clj`
- **New route**: `GET /compare`

### Modified Existing Files

- **handlers.clj** — 6 new handler functions (pipeline-detail-page, log-search-page, log-search-results-handler, build-compare-page, linter-page, linter-check-handler)
- **routes.clj** — New routes for pipeline viz, log search, build comparison, and linter
- **layout.clj** — Complete rewrite for responsive layout, hamburger menu, dark mode, theme toggle, Search nav link
- **components.clj** — Dark mode variants added to all 9 component functions
- **builds.clj** — DAG visualization integration, compare button
- **jobs.clj** — "View Pipeline" button
- **admin.clj** — "Pipeline Linter" nav link
- **cli/commands.clj** — `cmd-pipeline-lint` function
- **cli/core.clj** — `"lint"` dispatch in pipeline subcommand

### New Routes

| Route | Description |
|-------|-------------|
| `GET /jobs/:name/pipeline` | Pipeline DAG visualization page |
| `GET/POST /search/logs` | Build log search (form + results) |
| `GET /compare` | Build comparison page |
| `GET /admin/linter` | Pipeline linter web UI |
| `POST /admin/linter/check` | Lint pipeline content (htmx) |

### New CLI Commands

| Command | Description |
|---------|-------------|
| `chengis pipeline lint <file>` | Lint a pipeline file (.clj, .edn, .yml, .yaml) |

### Test Summary

- **1,187 tests, 3,876 assertions — 0 failures, 0 errors**
- 120 new tests across 4 new test files
- New test files: `engine/build_compare_test.clj`, `web/views/pipeline_viz_test.clj`, `web/views/responsive_test.clj`, `db/log_search_store_test.clj`

---

## Phase 8: Enterprise Identity & Access

### Feature 8a: SAML 2.0 SSO

- **SP-initiated SAML flow** — Enterprise SSO via SAML 2.0 alongside existing OIDC
- **Assertion Consumer Service** — POST binding ACS endpoint validates SAML responses, extracts attributes
- **SP metadata** — Auto-generated SP metadata XML endpoint for IdP configuration
- **JIT provisioning** — Automatic user creation on first SAML login with attribute mapping
- **SAML identity tracking** — `saml_identities` table links SAML assertions to local user accounts
- **Feature flag** — `:saml` (default false)
- **New source**: `src/chengis/web/saml.clj`
- **Migration 051** — `saml_identities` table
- **New dependency**: `com.onelogin/java-saml 2.9.0`

### Feature 8b: LDAP/Active Directory

- **LDAP bind authentication** — Authenticate users against LDAP/AD directory via bind operation
- **Group sync** — Synchronize LDAP group membership to local roles
- **JIT provisioning** — Automatic user creation on first LDAP login
- **LDAP-first fallback** — Attempt LDAP bind first; fall back to local password check on failure
- **LDAP sync scheduler** — Periodic group sync runs as HA singleton (lock 100005)
- **Feature flag** — `:ldap` (default false)
- **New source**: `src/chengis/web/ldap.clj`
- **Migration 052** — `ldap_identities` table
- **New dependency**: `com.unboundid/unboundid-ldapsdk 6.0.11`

### Feature 8c: Fine-Grained RBAC

- **Resource-level permissions** — Grant specific actions on specific resources (e.g., user X can trigger Job Y)
- **Permission groups** — Named sets of permissions assigned to multiple users
- **Permission middleware** — `wrap-require-permission` checks resource+action before handler execution
- **Resolution order** — Direct user permissions > group membership > role-based fallback
- **Admin UI** — Permission grant/revoke and group management pages
- **Feature flag** — `:fine-grained-rbac` (default false)
- **New source**: `src/chengis/db/permission_store.clj`, `src/chengis/web/permissions.clj`, `src/chengis/web/views/permissions.clj`
- **Migration 053** — `resource_permissions` table
- **Migration 054** — `permission_groups` and `permission_group_members` tables

### Feature 8d: MFA/TOTP

- **TOTP enrollment** — Generate secret, display QR code, verify initial code before activation
- **TOTP verification** — MFA-pending session flow: login → challenge → verify → full session
- **Recovery codes** — One-time-use recovery codes (bcrypt hashed) for account recovery
- **Encrypted secrets** — TOTP secrets encrypted with AES-256-GCM at rest
- **Settings page** — User-facing MFA enable/disable with QR code display
- **Feature flag** — `:mfa-totp` (default false)
- **New source**: `src/chengis/web/mfa.clj`, `src/chengis/web/views/mfa.clj`
- **Migration 055** — `totp_enrollments` and `totp_recovery_codes` tables
- **New dependency**: `dev.samstevens.totp/totp 1.7.1`

### Feature 8e: Cross-Org Shared Resources

- **Shared agent labels** — Share agent labels across organizations for resource pooling
- **Shared templates** — Share pipeline templates across organizations
- **Grant/revoke model** — Source org grants access to target org with audit trail
- **Admin UI** — Shared resource management page with grant/revoke controls
- **Feature flag** — `:cross-org-sharing` (default false)
- **New source**: `src/chengis/db/shared_resource_store.clj`, `src/chengis/web/views/shared_resources.clj`
- **Migration 056** — `shared_resource_grants` table

### Feature 8f: Cloud Secret Backends

- **AWS Secrets Manager** — Full `SecretBackend` protocol implementation using AWS SDK v2
- **Google Cloud Secret Manager** — Full `SecretBackend` protocol implementation using GCP client library
- **Azure Key Vault** — Full `SecretBackend` protocol implementation using Azure SDK
- **Config-driven selection** — Backend chosen via `:secrets {:backend "aws-sm"|"gcp-sm"|"azure-kv"}` or env vars
- **Feature flag** — `:cloud-secret-backends` (default false)
- **New source**: `src/chengis/plugin/builtin/aws_secrets.clj`, `src/chengis/plugin/builtin/gcp_secrets.clj`, `src/chengis/plugin/builtin/azure_keyvault.clj`
- **New dependencies**: `software.amazon.awssdk/secretsmanager 2.25.0`, `software.amazon.awssdk/auth 2.25.0`, `com.google.cloud/google-cloud-secretmanager 2.37.0`, `com.azure/azure-security-keyvault-secrets 4.8.0`, `com.azure/azure-identity 1.12.0`

### Feature 8g: Secret Rotation

- **Rotation policies** — Per-secret rotation interval, notification window, enabled/disabled toggle
- **Rotation scheduler** — Periodic check for due rotations (HA singleton, lock 100006)
- **Version history** — `secret_versions` table tracks all historical secret values
- **Pre-rotation notifications** — Alert before rotation due date (configurable days)
- **Admin UI** — Rotation policy management with create/edit/delete/toggle controls
- **Feature flag** — `:secret-rotation` (default false)
- **New source**: `src/chengis/engine/secret_rotation.clj`, `src/chengis/db/rotation_store.clj`, `src/chengis/web/views/secret_rotation.clj`
- **Migration 057** — `rotation_policies` table
- **Migration 058** — `secret_versions` table

### Modified Existing Files

- **auth.clj** — SAML/MFA public prefix paths, MFA-pending session handling
- **login.clj** — SAML SSO button on login page
- **handlers.clj** — LDAP-first login flow, MFA-pending session handling, 25+ new handler functions for permissions, shared resources, rotation
- **routes.clj** — SAML/MFA/permissions/shared/rotation routes, CSRF exemption for SAML ACS POST
- **admin.clj** — Permissions, Shared Resources, Secret Rotation nav links
- **server.clj** — AWS/GCP/Azure backend initialization, LDAP sync scheduler (lock 100005), rotation scheduler (lock 100006)

### New Feature Flags (7)

| Flag | Default | Feature |
|------|---------|---------|
| `:saml` | `false` | SAML 2.0 SP-initiated SSO |
| `:ldap` | `false` | LDAP/Active Directory authentication |
| `:fine-grained-rbac` | `false` | Resource-level permissions |
| `:mfa-totp` | `false` | MFA time-based one-time passwords |
| `:cross-org-sharing` | `false` | Cross-org shared resources |
| `:cloud-secret-backends` | `false` | AWS SM, GCP SM, Azure KV backends |
| `:secret-rotation` | `false` | Policy-driven secret rotation |

### New Environment Variables (~20)

| Variable | Feature |
|----------|---------|
| `CHENGIS_FEATURE_SAML` | Enable SAML 2.0 SSO |
| `CHENGIS_SAML_IDP_METADATA_URL` | SAML IdP metadata URL |
| `CHENGIS_SAML_SP_ENTITY_ID` | SAML SP entity ID |
| `CHENGIS_SAML_SP_ACS_URL` | SAML Assertion Consumer Service URL |
| `CHENGIS_SAML_IDP_ENTITY_ID` | SAML IdP entity ID |
| `CHENGIS_SAML_IDP_SSO_URL` | SAML IdP SSO URL |
| `CHENGIS_SAML_IDP_CERT` | SAML IdP certificate |
| `CHENGIS_SAML_SP_PRIVATE_KEY` | SAML SP private key |
| `CHENGIS_SAML_SP_CERT` | SAML SP certificate |
| `CHENGIS_FEATURE_LDAP` | Enable LDAP/AD authentication |
| `CHENGIS_LDAP_HOST` | LDAP server host |
| `CHENGIS_LDAP_PORT` | LDAP server port |
| `CHENGIS_LDAP_BIND_DN` | LDAP bind DN |
| `CHENGIS_LDAP_BIND_PASSWORD` | LDAP bind password |
| `CHENGIS_LDAP_USER_BASE_DN` | LDAP user search base DN |
| `CHENGIS_LDAP_USER_FILTER` | LDAP user search filter |
| `CHENGIS_LDAP_GROUP_BASE_DN` | LDAP group search base DN |
| `CHENGIS_LDAP_GROUP_FILTER` | LDAP group search filter |
| `CHENGIS_FEATURE_FINE_GRAINED_RBAC` | Enable fine-grained RBAC |
| `CHENGIS_FEATURE_MFA_TOTP` | Enable MFA/TOTP |
| `CHENGIS_FEATURE_CROSS_ORG_SHARING` | Enable cross-org sharing |
| `CHENGIS_FEATURE_CLOUD_SECRET_BACKENDS` | Enable cloud secret backends |
| `CHENGIS_AWS_SM_REGION` | AWS Secrets Manager region |
| `CHENGIS_AWS_SM_ACCESS_KEY_ID` | AWS access key ID |
| `CHENGIS_AWS_SM_SECRET_ACCESS_KEY` | AWS secret access key |
| `CHENGIS_GCP_SM_PROJECT_ID` | GCP project ID |
| `CHENGIS_GCP_SM_CREDENTIALS_PATH` | GCP credentials file path |
| `CHENGIS_AZURE_KV_VAULT_URL` | Azure Key Vault URL |
| `CHENGIS_AZURE_KV_TENANT_ID` | Azure tenant ID |
| `CHENGIS_AZURE_KV_CLIENT_ID` | Azure client ID |
| `CHENGIS_AZURE_KV_CLIENT_SECRET` | Azure client secret |
| `CHENGIS_FEATURE_SECRET_ROTATION` | Enable secret rotation |

### New Routes

**SAML:**
| Route | Description |
|-------|-------------|
| `GET /auth/saml/login` | Initiate SAML SP-initiated SSO login |
| `POST /auth/saml/acs` | SAML Assertion Consumer Service callback |
| `GET /auth/saml/metadata` | SAML SP metadata XML |

**MFA:**
| Route | Description |
|-------|-------------|
| `GET /auth/mfa/challenge` | MFA TOTP challenge page |
| `POST /auth/mfa/challenge` | Verify MFA TOTP code |
| `GET /auth/mfa/recovery` | MFA recovery code entry page |
| `POST /auth/mfa/recovery` | Verify MFA recovery code |
| `GET /settings/mfa` | MFA settings page |
| `GET /settings/mfa/setup` | MFA TOTP enrollment with QR code |
| `POST /settings/mfa/confirm` | Confirm MFA enrollment |
| `POST /settings/mfa/disable` | Disable MFA |

**Permissions:**
| Route | Description |
|-------|-------------|
| `GET /admin/permissions` | Permission management page |
| `POST /admin/permissions/grant` | Grant resource permission |
| `POST /admin/permissions/revoke/:id` | Revoke resource permission |
| `GET /admin/permissions/groups` | Permission group listing |
| `POST /admin/permissions/groups` | Create permission group |
| `GET /admin/permissions/groups/:id` | Permission group detail |
| `POST /admin/permissions/groups/:id/members` | Add group member |
| `POST /admin/permissions/groups/:id/members/:uid/remove` | Remove group member |
| `POST /admin/permissions/groups/:id/delete` | Delete permission group |
| `POST /admin/permissions/groups/:id/entries` | Add entry to permission group |
| `POST /admin/permissions/groups/:id/entries/:entry-id/remove` | Remove entry from permission group |

**Shared Resources:**
| Route | Description |
|-------|-------------|
| `GET /admin/shared-resources` | Cross-org shared resource management |
| `POST /admin/shared-resources/grant` | Grant shared resource access |
| `POST /admin/shared-resources/revoke/:id` | Revoke shared resource grant |

**Secret Rotation:**
| Route | Description |
|-------|-------------|
| `GET /admin/rotation` | Secret rotation policy management |
| `POST /admin/rotation` | Create/update rotation policy |
| `POST /admin/rotation/delete/:id` | Delete rotation policy |
| `POST /admin/rotation/toggle/:id` | Enable/disable rotation policy |

### New Library Dependencies (8)

| Library | Version | Purpose |
|---------|---------|---------|
| `com.onelogin/java-saml` | 2.9.0 | SAML 2.0 SP-initiated SSO |
| `com.unboundid/unboundid-ldapsdk` | 6.0.11 | LDAP/AD authentication |
| `dev.samstevens.totp/totp` | 1.7.1 | MFA TOTP generation/verification |
| `software.amazon.awssdk/secretsmanager` | 2.25.0 | AWS Secrets Manager |
| `software.amazon.awssdk/auth` | 2.25.0 | AWS authentication |
| `com.google.cloud/google-cloud-secretmanager` | 2.37.0 | GCP Secret Manager |
| `com.azure/azure-security-keyvault-secrets` | 4.8.0 | Azure Key Vault |
| `com.azure/azure-identity` | 1.12.0 | Azure identity/authentication |

### Migrations 051-058

- 051: SAML identities table (user_id, idp_entity_id, name_id, attributes, org_id)
- 052: LDAP identities table (user_id, ldap_dn, ldap_uid, ldap_groups, org_id)
- 053: Resource permissions table (user_id, resource_type, resource_id, action, org_id)
- 054: Permission groups tables (permission_groups, permission_group_members)
- 055: TOTP enrollment and recovery codes tables (totp_enrollments, totp_recovery_codes)
- 056: Cross-org shared resource grants table (source_org_id, target_org_id, resource_type)
- 057: Secret rotation policies table (secret_name, rotation_interval_days, next_rotation)
- 058: Secret versions table (secret_name, version, encrypted_value, active)

### Code Review Fixes (9 issues resolved across 6 source files)

**High (3):**
- **Form field name mismatches (4 handlers)** — HTML forms used hyphenated field names (e.g., `user-id`, `resource-type`, `target-org-id`) but handlers read underscored names (e.g., `user_id`, `resource_type`, `target_org_id`). All form param reads in `grant-permission-handler`, `create-shared-grant-handler`, `create-rotation-policy-handler`, and `add-group-member-handler` corrected to match HTML form field names exactly.
- **MFA view route URL mismatches** — MFA views (`views/mfa.clj`) had form actions and links pointing to `/login/mfa` and `/login/mfa/recovery`, but actual routes were defined as `/auth/mfa/challenge` and `/auth/mfa/recovery`. Fixed 4 URL references in the view file.
- **Shared resources create form URL mismatch** — The create-grant form in `views/shared_resources.clj` posted to `/admin/shared-resources` (a GET-only route) instead of `/admin/shared-resources/grant`. Fixed form action URL.

**Medium (4):**
- **MFA recovery form field name** — Handler `mfa-recovery-submit` read `"code"` from form params, but the form field was named `"recovery-code"`. Changed handler to read `"recovery-code"`.
- **Function call signature errors** — `list-groups` and `list-policies` were called with positional args (e.g., `(list-groups ds org-id)`) but their signatures use keyword arguments (`[ds & {:keys [org-id]}]`). Fixed to keyword style: `(list-groups ds :org-id org-id)`.
- **permissions-page listing broken** — Called `list-resource-permissions` with nil resource-type and resource-id, which returned empty results. Added new `list-org-permissions` function to `permission_store.clj` and used it in the handler. Also added missing `groups` data to the view render call.
- **Missing permission group routes and handlers** — Views had buttons/forms for delete group, add entry, and remove entry, but no corresponding routes existed. Added 3 routes to `routes.clj` (`/:id/delete`, `/:id/entries`, `/:id/entries/:entry-id/remove`) and 3 handler functions to `handlers.clj` (`delete-permission-group-handler`, `add-group-entry-handler`, `remove-group-entry-handler`).

**Low (2):**
- **Rotation page missing versions data** — The `rotation-page` handler didn't fetch or pass `:versions` data to the view. Added version fetching logic with aggregation across all policies and sorted by `rotated-at` descending.
- **SQLite-specific SQL in rotation_store** — `create-policy!`, `mark-rotated!`, and `policies-due-for-notification` used SQLite-only functions (`datetime('now', ...)`, `julianday()`). Replaced with database-agnostic Java `Instant`/`Duration` calculations via `now-plus-days` and `now-str` helper functions, ensuring compatibility with both SQLite and PostgreSQL.

### New Routes (added during code review)

| Route | Description |
|-------|-------------|
| `POST /admin/permissions/groups/:id/delete` | Delete a permission group |
| `POST /admin/permissions/groups/:id/entries` | Add entry to permission group |
| `POST /admin/permissions/groups/:id/entries/:entry-id/remove` | Remove entry from permission group |

### Test Suite
- **1,067 tests, 3,564 assertions — all passing**
- ~17 new source files, ~12 new test files, 4 new view files, 8 migration pairs added in Phase 8
- 139 new tests added in Phase 8 (1,067 - 928)
- 412 new assertions added in Phase 8 (3,564 - 3,152)
- 41 total feature flags (was 34)
- 58 total migration versions (was 50)

---

## [Unreleased] — Phase 7: Supply Chain Security

### Feature 7a: SLSA Provenance

- **Build provenance attestations** — SLSA v1.0 provenance generation tracking builder, source, build config, and materials
- **Attestation storage** — Provenance records persisted per build with builder ID, invocation metadata, and material hashes
- **API endpoint** — `GET /api/supply-chain/builds/:build-id/provenance` returns SLSA provenance JSON
- **Feature flag** — `:slsa-provenance` (default false)
- **New source**: `src/chengis/engine/provenance.clj`, `src/chengis/db/provenance_store.clj`

### Feature 7b: SBOM Generation

- **CycloneDX/SPDX support** — Generate software bill of materials via Syft or cdxgen
- **Dual format output** — SBOM available in both CycloneDX and SPDX formats
- **Graceful degradation** — SBOM generation skipped when external tools (Syft/cdxgen) are not installed
- **API endpoint** — `GET /api/supply-chain/builds/:build-id/sbom/:format` returns SBOM in requested format
- **Feature flag** — `:sbom-generation` (default false)
- **New source**: `src/chengis/engine/sbom.clj`, `src/chengis/db/sbom_store.clj`

### Feature 7c: Container Image Scanning

- **Trivy/Grype integration** — Vulnerability scanning for container images with CVE detection
- **Dual scanner support** — Use Trivy or Grype (auto-detected), graceful degradation when neither is installed
- **Severity tracking** — Vulnerabilities classified by severity (critical, high, medium, low)
- **API endpoint** — `GET /api/supply-chain/builds/:build-id/scans` returns scan results
- **Feature flag** — `:container-scanning` (default false)
- **New source**: `src/chengis/engine/vulnerability_scanner.clj`, `src/chengis/db/scan_store.clj`

### Feature 7d: Policy-as-Code with OPA

- **OPA/Rego policy evaluation** — Define build policies in Rego for complex decision logic
- **Policy management** — CRUD operations for OPA policies with admin UI
- **Build-time evaluation** — Policies evaluated against build context before/after execution
- **Graceful degradation** — OPA evaluation skipped when OPA binary is not installed
- **API endpoints** — `GET/POST /api/supply-chain/opa` for policy management
- **Admin UI** — `/admin/supply-chain/opa` for policy browsing and editing
- **Feature flag** — `:opa-policies` (default false)
- **New source**: `src/chengis/engine/opa.clj`, `src/chengis/db/opa_store.clj`

### Feature 7e: License Scanning

- **SPDX license compliance** — Detect dependency licenses from SBOMs and enforce license policies
- **License policy engine** — Allow/deny lists for license types with configurable enforcement
- **API endpoints** — `GET /api/supply-chain/builds/:build-id/licenses`, `GET/POST /api/supply-chain/licenses/policy`
- **Admin UI** — `/admin/supply-chain/licenses` for license policy management
- **Feature flag** — `:license-scanning` (default false)
- **New source**: `src/chengis/engine/license_scanner.clj`, `src/chengis/db/license_store.clj`

### Feature 7f: Artifact Signing

- **cosign/GPG signature support** — Sign build artifacts and attestations with cosign or GPG
- **Signature verification** — Verify artifact signatures via API endpoint
- **Graceful degradation** — Signing skipped when cosign/GPG tools are not installed
- **API endpoint** — `GET /api/supply-chain/builds/:build-id/verify` for signature verification
- **Admin UI** — `/admin/supply-chain` includes signature status overview
- **Feature flag** — `:artifact-signing` (default false)
- **New source**: `src/chengis/engine/signing.clj`, `src/chengis/db/signature_store.clj`
- **View**: `src/chengis/web/views/signatures.clj`

### Feature 7g: Regulatory Dashboards

- **SOC 2 / ISO 27001 readiness** — Regulatory framework assessment based on audit trail completeness
- **Framework scoring** — Automated readiness scoring across control categories
- **Assessment API** — `POST /api/regulatory/assess`, `GET /api/regulatory/frameworks/:framework`
- **Admin UI** — `/admin/regulatory` for regulatory readiness dashboard
- **Feature flag** — `:regulatory-dashboards` (default false)
- **New source**: `src/chengis/engine/regulatory.clj`, `src/chengis/db/regulatory_store.clj`
- **View**: `src/chengis/web/views/regulatory.clj`

### New Feature Flags (7)

| Flag | Default | Feature |
|------|---------|---------|
| `:slsa-provenance` | `false` | SLSA v1.0 build provenance attestations |
| `:sbom-generation` | `false` | CycloneDX/SPDX SBOM generation |
| `:container-scanning` | `false` | Trivy/Grype vulnerability scanning |
| `:opa-policies` | `false` | OPA/Rego policy-as-code evaluation |
| `:license-scanning` | `false` | SPDX license compliance scanning |
| `:artifact-signing` | `false` | cosign/GPG artifact signing |
| `:regulatory-dashboards` | `false` | SOC 2 / ISO 27001 readiness dashboards |

### New Environment Variables (14)

| Variable | Feature |
|----------|---------|
| `CHENGIS_FEATURE_SLSA_PROVENANCE` | Enable SLSA provenance generation |
| `CHENGIS_FEATURE_SBOM_GENERATION` | Enable SBOM generation |
| `CHENGIS_FEATURE_CONTAINER_SCANNING` | Enable container image scanning |
| `CHENGIS_FEATURE_OPA_POLICIES` | Enable OPA policy-as-code |
| `CHENGIS_FEATURE_LICENSE_SCANNING` | Enable license scanning |
| `CHENGIS_FEATURE_ARTIFACT_SIGNING` | Enable artifact signing |
| `CHENGIS_FEATURE_REGULATORY_DASHBOARDS` | Enable regulatory dashboards |
| `CHENGIS_SBOM_TOOL` | Preferred SBOM tool (`syft` or `cdxgen`) |
| `CHENGIS_SCANNER_TOOL` | Preferred scanner tool (`trivy` or `grype`) |
| `CHENGIS_OPA_BINARY` | Path to OPA binary |
| `CHENGIS_SIGNING_TOOL` | Preferred signing tool (`cosign` or `gpg`) |
| `CHENGIS_SIGNING_KEY` | Signing key path or reference |
| `CHENGIS_REGULATORY_FRAMEWORKS` | Comma-separated frameworks to assess |
| `CHENGIS_LICENSE_POLICY_MODE` | License policy enforcement mode (`warn` or `deny`) |

### New Routes

**Admin UI:**
| Route | Description |
|-------|-------------|
| `GET /admin/supply-chain` | Supply chain security overview dashboard |
| `GET /admin/supply-chain/opa` | OPA policy management |
| `GET /admin/supply-chain/licenses` | License policy management |
| `GET /admin/supply-chain/builds/:build-id` | Per-build supply chain detail |
| `GET /admin/regulatory` | Regulatory readiness dashboard |

**API:**
| Route | Description |
|-------|-------------|
| `GET /api/supply-chain/builds/:build-id/provenance` | SLSA provenance attestation |
| `GET /api/supply-chain/builds/:build-id/sbom/:format` | SBOM in CycloneDX or SPDX format |
| `GET /api/supply-chain/builds/:build-id/scans` | Vulnerability scan results |
| `GET /api/supply-chain/builds/:build-id/licenses` | License scan results |
| `GET /api/supply-chain/builds/:build-id/verify` | Artifact signature verification |
| `GET/POST /api/supply-chain/opa` | OPA policy CRUD |
| `GET/POST /api/supply-chain/licenses/policy` | License policy CRUD |
| `POST /api/regulatory/assess` | Trigger regulatory assessment |
| `GET /api/regulatory/frameworks/:framework` | Framework readiness details |

### External Tool Integrations

All external tools degrade gracefully when not installed:

| Tool | Purpose | Detection |
|------|---------|-----------|
| Trivy | Container vulnerability scanning | `which trivy` |
| Grype | Container vulnerability scanning (alternative) | `which grype` |
| Syft | SBOM generation (CycloneDX/SPDX) | `which syft` |
| cdxgen | SBOM generation (alternative) | `which cdxgen` |
| cosign | Artifact signing (Sigstore) | `which cosign` |
| GPG | Artifact signing (traditional) | `which gpg` |
| OPA | Policy-as-code evaluation | `which opa` |

### Migrations 048-050

- 048: Supply chain core tables (provenance attestations, SBOM records, vulnerability scans)
- 049: OPA policies and license compliance tables
- 050: Artifact signatures and regulatory assessment tables

### Code Review Fixes (16 issues resolved across 12 source files)

**Critical (2):**
- **Verify-signatures handler never detects failure** — `api-verify-signatures` handler in `handlers.clj` was wrapping `verify-signature!` in try/catch, swallowing the actual result. Fixed to inspect return value (`{:verified? true/false}`) and propagate verification status correctly to the JSON response.
- **Signatures view form action URL missing `/builds/` segment** — `signatures.clj` form action was `"/api/supply-chain/"` instead of `"/api/supply-chain/builds/"`, causing 404 on signature verification requests.

**High (3):**
- **Timestamp format mismatch in 5 store cleanup functions** — `cleanup-old-*!` functions in `provenance_store.clj`, `sbom_store.clj`, `scan_store.clj`, `signature_store.clj`, and `license_store.clj` used `Instant.toString()` (ISO-8601 `"2025-01-15T10:30:00Z"`) for cutoff comparison against SQLite's `CURRENT_TIMESTAMP` format (`"2025-01-15 10:30:00"`). Fixed by adding `DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss"` formatting (consistent with `build_store.clj`).
- **OPA cond branch ordering** — In `opa.clj`, the `:timed-out?` check was after the `(zero? (:exit-code result))` check. A timed-out process could have exit code 0, causing false positive results. Moved timeout check to first position.
- **OPA shell injection via package-name** — Package names were interpolated into shell commands without sanitization. Added regex validation (`[a-zA-Z0-9._]+`) to reject malicious package names before command construction.

**Medium (7):**
- **scan_store.clj boolean-to-integer for `:passed`** — Clojure booleans passed for `:passed` field were not coerced to SQLite integers. Added `(if (boolean? passed) (if passed 1 0) passed)` conversion.
- **signature_store.clj `verify-signature!` org-id scoping** — `verify-signature!` lacked org-id filtering, allowing cross-tenant signature verification updates. Added optional `org-id` parameter with conditional WHERE clause.
- **signing.clj unused import** — Removed unused `clojure.data.json` require.
- **signing.clj verify-signature! temp file handling** — Refactored to use proper `try/catch/finally` with `File/createTempFile` for reliable cleanup.
- **vulnerability_scanner.clj timed-out scan silently passes** — Timed-out scans were falling through to the `:else` branch with empty stdout, resulting in a passing scan record. Added explicit `:timed-out?` check as first cond branch, creating a failing scan record (`passed=0`).
- **regulatory.clj redundant DB queries** — `audit-log-count` was called twice per check (once for `passing?`, once for `evidence`). Refactored to use a single `let` binding.
- **supply_chain.clj signatures panel not wired** — The supply chain dashboard was missing the artifact signatures panel. Added `v-signatures/signatures-panel` call at the end of the dashboard view.

**Low (4):**
- **provenance.clj unused imports** — Removed unused `MessageDigest` and `BigInteger` imports (only `Base64` needed).
- **API endpoints return HTML on 404** — Three API endpoints (`api-get-provenance`, `api-get-scans`, `api-get-licenses`) returned HTML 404 instead of JSON. Added `json-not-found` helper returning `{"error": "..."}` with `Content-Type: application/json`.
- **regulatory.clj unused json import** — Removed unused `clojure.data.json` require.
- **signing_test.clj cleanup edge case** — `cleanup-old-signatures!` test used `retention-days=0`, which in the new timestamp format creates a cutoff at exactly "now" and misses records created in the same second. Changed to `retention-days=-1` to ensure the cutoff is in the future.

### Test Suite
- **928 tests, 3,152 assertions — all passing**
- 13 new source files, 7 new test files, 3 new view files, 6 migration pairs added in Phase 7
- 90 new tests added in Phase 7
- New test files: provenance_test, sbom_test, vulnerability_scanner_test, opa_test, license_scanner_test, signing_test, regulatory_test

---

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
| Chengis (self) | Clojure | 1,067 passed, 3,564 assertions | varies | SUCCESS |
