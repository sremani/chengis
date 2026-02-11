# Roadmap

This document outlines the product roadmap for Chengis. It reflects completed work, current priorities, and planned future development.

## Completed Phases

### v0.1.0 — Core CI/CD Engine

- Pipeline DSL (`defpipeline` macro) producing pure data maps
- Chengisfile (EDN) auto-detection in repo root
- Git integration with shallow clone and metadata extraction
- Sequential stage execution with parallel steps via `core.async`
- Process execution with per-step timeout support
- Build cancellation with cooperative interrupt propagation
- Build retry with parent chain linkage
- Post-build actions (`always`, `on-success`, `on-failure`)
- Parameterized builds with dynamic trigger forms
- AES-256-GCM encrypted secrets with automatic log masking
- Glob-based artifact collection and persistent storage
- Console and Slack notification plugins
- SSE-powered real-time build streaming (zero JavaScript)
- htmx + Tailwind CSS web UI with dark theme
- Full CLI for headless operation
- SQLite persistence with migration-based schema evolution
- Admin dashboard with JVM stats, disk usage, cleanup

### v0.2.0 — Plugin System, Docker & Distributed Builds

- Protocol-based plugin system (7 protocols, 12 builtin plugins)
- Central plugin registry with runtime introspection
- External plugin loading from `plugins/` directory
- Docker step execution (`docker run`, `docker-compose run`)
- Per-step, stage-level, and pipeline-level container config
- Image management with configurable pull policies
- Docker command injection protection
- HTTP-based master/agent distributed architecture
- Agent registry with heartbeat monitoring and label matching
- Build dispatcher with local fallback
- Agent management UI with status badges and capacity metrics
- GitHub Actions-style YAML workflows (`.chengis/workflow.yml`)
- `${{ }}` expression syntax for parameters, secrets, and env vars
- Multi-format pipeline detection (EDN > YAML > server)

### v0.3.0 — Enterprise Security

- JWT + session cookie authentication
- Role-based access control (admin / developer / viewer)
- API token management with scope restrictions
- Audit logging with admin viewer and date/action/user filtering
- Account lockout with configurable threshold and duration
- Request rate limiting middleware
- CSRF anti-forgery tokens on all form endpoints
- Prometheus metrics endpoint (`/metrics`)
- bcrypt password hashing with session versioning

### v0.4.0 — Production Hardening

- Persistent build queue (database-backed, priority levels)
- Circuit breaker for agent HTTP calls (closed / open / half-open)
- Orphan build monitor (auto-fail builds from offline agents)
- Agent-to-master artifact transfer via HTTP multipart
- System health alerts with auto-resolve
- HTTP request metrics middleware

### v0.5.0 — Enterprise Integration

- Approval gates with manual checkpoints and multi-approver support
- Pipeline templates with admin CRUD and one-click job creation
- SCM commit status reporting (GitHub and GitLab)
- Email notification plugin (SMTP)
- Webhook event logging with admin viewer
- Secret access auditing

### v0.6.0 — Deployment & Operations

- Environment variable configuration (`CHENGIS_*`, 25+ vars, auto type coercion)
- Docker Compose deployment (master + 2 agents)
- Database backup/restore (SQLite `VACUUM INTO`, PostgreSQL `pg_dump`)
- Streaming audit export (CSV/JSON) with batched reads
- Matrix builds (cartesian expansion, `MATRIX_*` env vars, exclude filters)

### v0.7.0 — PostgreSQL Dual-Driver

- Dual-driver database: SQLite (default) or PostgreSQL (production)
- HikariCP connection pooling for PostgreSQL
- Portable SQL across both drivers
- Separate migration directories per database type
- Conditional backup strategy per driver

### v0.8.0 — Enterprise Identity

- SSO/OIDC integration (Google, Okta, generic providers)
- OIDC discovery endpoint auto-configuration
- API token scopes for granular permission control
- Secret backend abstraction (`SecretBackend` protocol)
- HashiCorp Vault integration (KV v2 engine)

### v0.9.0 — Multi-Tenancy

- Organization model with membership and roles
- Org-scoped resource isolation (jobs, builds, secrets, templates, audit, webhooks, approvals)
- Org context middleware (`wrap-org-context`)
- Default organization for backward compatibility

### Security Remediation

- 17 critical/high findings remediated (SQL portability, transaction wrapping, cross-org isolation)
- 16 handler org-scoping fixes
- 4 external review findings (scope escalation, OIDC state bypass, JWT validation, auth ordering)
- Cross-org regression test suite (SSE, webhooks, alerts, secrets)

### v1.0.0 — Phase 1: Governance Foundation

- Org-scoped policy engine with priority ordering and short-circuit evaluation
- Artifact SHA-256 checksums with integrity verification
- Compliance reports with per-build tracking and admin dashboard
- Feature flags for runtime feature toggling (`CHENGIS_FEATURE_*`)
- Migrations 029-031

### Phase 2: Distributed Dispatch & Hardening

- Dispatcher wiring into all build trigger paths (UI, webhooks, retry), gated by feature flag
- Configurable heartbeat timeout and `fallback-local` default flipped to `false`
- Build attempt tracking (`attempt_number`, `root_build_id`) with retry history UI
- Durable build events with time-ordered IDs and cursor-based replay API
- Plugin trust enforcement via DB-backed allowlist
- Docker image policies (allow/deny with priority-ordered glob patterns)
- P0 fixes: atomic dequeue race, webhook org-id, `:failed` dispatch handling
- Migrations 032-034

### Phase 3: Kubernetes & High Availability

- Persistent agent registry with write-through cache (atom + DB, survives master restarts)
- PostgreSQL advisory lock leader election for singleton services (queue-processor, orphan-monitor, retention-scheduler)
- Enhanced health probes: `/startup` (K8s startup probe), enhanced `/ready` (queue depth, agent summary), `/health` (instance-id)
- Kubernetes raw manifests (`k8s/base/`): namespace, ConfigMap, Secret, master/agent Deployments, Service, PVC, HPA, Ingress
- Parameterized Helm chart (`helm/chengis/`): values for replicas, resources, ingress, TLS, ServiceMonitor
- HA Docker Compose (`docker-compose.ha.yml`): PostgreSQL 16 + multi-master local testing
- Migrations 035-036

### Security Review II

- Auth bypass fix: event replay endpoint + distributed path allowlist hardening
- Cross-org policy evaluation scoping with conditional JOIN
- Transactional policy delete with ownership verification
- Audit hash-chain: `seq_num` column for PostgreSQL-portable ordering (replaces `rowid`)
- Hash-chain content integrity verification (entry_hash recomputation)
- 9 regression tests covering all 5 findings
- **525 tests, 2,126 assertions — all passing**

### Phase 4: Build Performance & Caching

- Parallel stage execution via DAG-based dependency graph (`:depends-on` with Kahn's topological sort, bounded semaphore concurrency)
- Docker layer caching via persistent named volumes (`:cache-volumes` in container config)
- Content-addressable artifact/dependency caching (`{{ hashFiles('...') }}` expressions, restore-keys prefix matching)
- Build result caching with stage fingerprinting (SHA-256 of git-commit + commands + env, skip unchanged stages)
- Resource-aware agent scheduling (weighted scoring: load 60% + CPU 20% + memory 20%, minimum resource filtering)
- Incremental artifact storage via block-level delta compression (4KB blocks, MD5 hashing, >20% savings threshold)
- Build deduplication (skip redundant builds on same commit within configurable time window)
- 7 new feature flags (all default `false` for safe rollout)
- 12 new environment variables for configuration
- Migrations 037-039
- **587 tests, 2,275 assertions — all passing**

---

### Phase 5: Observability & Analytics

- Grafana dashboard provisioning files (overview, agents, security) for existing Prometheus metrics
- Custom span-based build tracing stored in DB, waterfall visualization, OTLP JSON export (feature flag: `:tracing`)
- Precomputed daily/weekly build analytics: duration trends, success rates, percentiles, slowest stages, flakiness scores (feature flag: `:build-analytics`)
- MDC-like log correlation context: build-id, job-id, org-id, stage-name, step-name in all structured logs
- Browser push notifications for build completion via SSE + HTML5 Notification API (feature flag: `:browser-notifications`)
- Build cost attribution: agent-hours per build for chargeback and capacity planning (feature flag: `:cost-attribution`)
- Flaky test detection: JUnit XML, TAP, and generic parsers; statistical flakiness scoring (feature flag: `:flaky-test-detection`)
- 5 new feature flags, 13 new environment variables
- Migrations 040-043
- **678 tests, 2,529 assertions — all passing**

---

### Phase 6: Advanced SCM & Workflow

- PR/MR status checks with required check enforcement (`pr_check_store.clj`, `pr_checks.clj`, PR check views)
- Branch-based pipeline overrides with pattern matching (exact, glob, regex) (`branch_overrides.clj`)
- Monorepo support with path-based trigger filtering for changed files (`monorepo.clj`)
- Build dependencies with explicit job dependency graphs and downstream triggering (`dependency_store.clj`, `build_deps.clj`)
- Database-backed cron scheduling with missed-run detection (`cron_store.clj`, `cron.clj`)
- Additional SCM providers: Gitea and Bitbucket status reporters via `ScmStatusReporter` protocol (`gitea_status.clj`, `bitbucket_status.clj`)
- Webhook replay for re-delivering failed webhooks from stored payloads (`webhook_replay.clj`)
- Auto-merge on success: automatically merge PRs when all required checks pass (`auto_merge.clj`)
- 7 new feature flags (pr-status-checks, branch-overrides, monorepo-filtering, build-dependencies, cron-scheduling, webhook-replay, auto-merge)
- 16 new environment variables (`CHENGIS_FEATURE_*`, `CHENGIS_CRON_*`, `CHENGIS_AUTO_MERGE_*`, `CHENGIS_SCM_GITEA_*`, `CHENGIS_SCM_BITBUCKET_*`)
- New admin routes: `/admin/cron`, `/admin/webhook-replay`
- New API routes: `/api/cron`, `/api/webhooks/:id/replay`, `/api/jobs/:job-id/dependencies`, `/api/jobs/:job-id/checks`
- 14 new source files, 13 new test files
- 14 builtin plugins (was 12), supporting GitHub, GitLab, Gitea, Bitbucket status reporting
- Migrations 044-047
- **838 tests, 2,849 assertions — all passing**

---

### Phase 7: Supply Chain Security

- SLSA v1.0 build provenance attestations tracking builder, source, and materials (`provenance.clj`, `provenance_store.clj`)
- SBOM generation in CycloneDX and SPDX formats via Syft/cdxgen with graceful degradation (`sbom.clj`, `sbom_store.clj`)
- Container image scanning via Trivy/Grype with severity classification and graceful degradation (`vulnerability_scanner.clj`, `scan_store.clj`)
- Policy-as-code with OPA/Rego evaluation against build context, admin UI for policy management (`opa.clj`, `opa_store.clj`)
- License scanning from SBOMs with allow/deny policy enforcement (`license_scanner.clj`, `license_store.clj`)
- Artifact signing via cosign/GPG with signature verification API (`signing.clj`, `signature_store.clj`)
- Regulatory readiness dashboards for SOC 2 / ISO 27001 with automated framework scoring (`regulatory.clj`, `regulatory_store.clj`)
- 3 new view files: `supply_chain.clj`, `regulatory.clj`, `signatures.clj`
- 7 new feature flags (slsa-provenance, sbom-generation, container-scanning, opa-policies, license-scanning, artifact-signing, regulatory-dashboards)
- 14 new environment variables for tool paths, signing keys, and policy configuration
- External tool integrations: Trivy, Grype, Syft, cdxgen, cosign, GPG, OPA (all degrade gracefully)
- Admin routes: `/admin/supply-chain`, `/admin/supply-chain/opa`, `/admin/supply-chain/licenses`, `/admin/regulatory`
- API routes: `/api/supply-chain/builds/:build-id/{provenance,sbom,scans,licenses,verify}`, `/api/supply-chain/opa`, `/api/supply-chain/licenses/policy`, `/api/regulatory/{assess,frameworks}`
- 13 new source files, 7 new test files, 3 new view files, 6 migration pairs
- Migrations 048-050 (50 total migration versions)
- Code review: 16 issues fixed across 12 files (2 critical, 3 high, 7 medium, 4 low) — timestamp format alignment, OPA shell injection prevention, cross-tenant signature verification scoping, handler result propagation, and supply chain view wiring
- **928 tests, 3,152 assertions — all passing**

---

### Phase 8: Enterprise Identity & Access

- SAML 2.0 SP-initiated SSO with JIT provisioning and IdP metadata integration (`saml.clj`)
- LDAP/Active Directory bind authentication with group sync, JIT provisioning, and LDAP-first-then-local fallback (`ldap.clj`)
- Fine-grained RBAC with resource-level permissions, permission groups, and `wrap-require-permission` middleware (`permission_store.clj`, `permissions.clj`)
- MFA/TOTP with QR code enrollment, TOTP verification, recovery codes, and AES-256-GCM encrypted secrets (`mfa.clj`, views `mfa.clj`)
- Cross-org shared resources: share agent labels and pipeline templates across organizations (`shared_resource_store.clj`, views `shared_resources.clj`)
- Cloud secret backends: AWS Secrets Manager, Google Secret Manager, Azure Key Vault implementing `SecretBackend` protocol (`aws_secrets.clj`, `gcp_secrets.clj`, `azure_keyvault.clj`)
- Secret rotation: policy-driven rotation schedules with version history and pre-rotation notifications (`secret_rotation.clj`, `rotation_store.clj`, views `secret_rotation.clj`)
- 7 new feature flags (saml, ldap, fine-grained-rbac, mfa-totp, cross-org-sharing, cloud-secret-backends, secret-rotation) — 41 total
- ~20 new environment variables (`CHENGIS_SAML_*`, `CHENGIS_LDAP_*`, `CHENGIS_AWS_SM_*`, `CHENGIS_GCP_SM_*`, `CHENGIS_AZURE_KV_*`, feature flags)
- New library dependencies: java-saml 2.9.0, UnboundID LDAP SDK 6.0.11, samstevens/totp 1.7.1, AWS SDK 2.25.0, GCP Secret Manager 2.37.0, Azure Key Vault 4.8.0, Azure Identity 1.12.0
- SAML/MFA routes, permission admin routes, shared resource routes, rotation routes
- LDAP sync scheduler (HA lock 100005), rotation scheduler (HA lock 100006)
- 17 builtin plugins (was 14): added aws_secrets, gcp_secrets, azure_keyvault
- ~17 new source files, ~12 new test files, 4 new view files, 8 migration pairs
- Migrations 051-058 (58 total migration versions)
- Code review: 9 issues fixed across 6 files (3 high, 4 medium, 2 low) — form field name mismatches, MFA route URL corrections, function call signature fixes, missing permission group routes/handlers, database-agnostic SQL in rotation store
- **1,067 tests, 3,564 assertions — all passing**

---

### Phase 9: Developer Experience

- Pipeline linter: Comprehensive validation (structural, semantic, expression) for all three formats (CLJ, EDN, YAML), CLI `pipeline lint <file>`, web UI at `/admin/linter`
- Pipeline DAG visualization: Server-side DAG layout algorithm, SVG Bezier arrows, status-colored nodes, integrated in job/build detail pages
- Build log search: Full-text search across build logs with line highlighting, job/status filters, pagination, htmx results at `/search/logs`
- Mobile-responsive UI: CSS-only hamburger menu, responsive nav, viewport meta, responsive grid layouts
- Dark/light theme toggle: Tailwind `darkMode: 'class'`, localStorage persistence, pre-render flash prevention, dark mode on all 9 component types
- Build comparison: Side-by-side diff of stages/steps/timing/artifacts between two builds at `/compare`
- 6 new source files, 4 new test files, 1 new CLI command
- New routes: `/jobs/:name/pipeline`, `/search/logs`, `/compare`, `/admin/linter`, `/admin/linter/check`
- **1,187 tests, 3,876 assertions — all passing**

---

### Phase 10: Scale & Performance

- Build log streaming: Chunked log storage (1000-line chunks) in `build_log_chunks` table, streaming process execution with on-line/on-chunk callbacks, htmx infinite scroll, secret masking
- Cursor-based API pagination: Base64-encoded `"timestamp|id"` cursors, `pagination.clj` shared module, applied to builds/jobs/audit stores with backward-compatible envelope `{:items :has-more :next-cursor}`
- Database partitioning: Monthly range partitions for PostgreSQL on builds/events/audit tables, metadata tracking, automated maintenance cycle with future partition creation and expired partition cleanup
- Read replicas: `RoutedDatasource` record with `read-ds`/`write-ds` routing, opt-in for dashboard/analytics queries, passthrough for single-DB setups
- Agent connection pooling: Per-agent HTTP pool state with keep-alive headers, health tracking with configurable failure threshold, promise-based async requests
- Event bus backpressure: Critical event classification (build lifecycle = must-deliver), adaptive publish with `alt!!` timeout for critical / `offer!` for non-critical, queue depth sampling, SSE sliding buffers
- Multi-region support: Region-aware agent scoring with locality bonus (0.3 default), score capping at 1.5, agent region stored in DB
- 7 new feature flags (35 total), ~25 new env vars, 4 new migrations (059-062)
- 9 new source files, 9 new test files
- Code review: 5 bugs fixed across 4 files (1 high, 2 medium, 2 low) — agent_http atom corruption, partitioning SQL injection guard and HoneySQL CASE fix, region scoring Clojure falsiness, empty string region matching
- **1,257 tests, 4,085 assertions — all passing**

---

### Phase 11: Deployment & Release Orchestration

- Environment definitions: Ordered environments (dev→staging→prod) with atomic locking, approval gating, auto-promote, and JSON config
- Release management: Semver versioning with lifecycle states (draft→published→deprecated), auto-version from builds, build validation
- Artifact promotion: Environment-to-environment promotion pipeline with eligibility checks, approval gates, and transactional superseding
- Deployment strategies: Direct, blue-green, canary, and rolling strategies as first-class DB entities with type-specific JSON config
- Deployment execution: Lock-execute-unlock engine with strategy step generation, rollback support, cancellation, concurrent prevention
- Environment health checks: HTTP and command-based checks with polling loop (`wait-for-healthy!`), configurable timeout/retries, result tracking
- Deployment dashboard: Unified view with environment cards, promotion pipeline, deployment timeline, full route tree under `/deploy`
- 7 new feature flags (42 total), ~15 new env vars, 7 new migrations (063-069)
- 16 new source files, 11 new test files, 6 new view files
- **1,352 tests, 4,368 assertions — all passing**

---

### Phase 12: Infrastructure-as-Code Integration

- IaC project detection and configuration for Terraform, Pulumi, and CloudFormation (`iac.clj`, `iac_store.clj`)
- Step executor plugins for all three IaC tools: Terraform init/plan/apply, Pulumi preview/up/destroy, CloudFormation create/update/delete (`terraform.clj`, `pulumi.clj`, `cloudformation.clj`)
- State management with compressed snapshots, versioning, locking, and conflict detection (`iac_state.clj`)
- Plan parsing with resource change extraction and visualization
- Cost estimation from IaC plans with per-resource pricing (`iac_cost.clj`, `iac_cost_store.clj`)
- IaC dashboard with project listing, plan visualization, state history, and cost trends (`views/iac.clj`, `views/iac_plans.clj`)
- Policy integration points hooking IaC plans into existing OPA policy engine
- 7 new feature flags (49 total): infrastructure-as-code, terraform-execution, pulumi-execution, cloudformation-execution, iac-state-management, iac-cost-estimation, iac-policy-enforcement
- 20 builtin plugins (was 17): added Terraform, Pulumi, CloudFormation step executors
- 5 new tables: iac_projects, iac_plans, iac_states, iac_state_locks, iac_cost_estimates
- New routes: `/iac`, `/iac/projects`, `/iac/plans`, `/iac/states`
- 10 new source files, 8 new test files, 4 new migrations (070-073)
- Migrations 070-073 (73 total migration versions)
- **1,445 tests, 4,687 assertions — all passing**

---

## Future Exploration

These items are under consideration but not yet scheduled:

- **AI-powered recommendations** — Flaky test detection, build time prediction, auto-suggested pipeline optimizations
- **Plugin marketplace** — Curated community plugins with versioning and dependency resolution
- **GitOps pipeline sync** — Pipeline definitions synced from Git branches with PR-based review workflow
- **Chengis Cloud** — Managed SaaS offering with per-org isolation, auto-scaling agents, and usage-based billing
- **GitHub Actions compatibility layer** — Run GitHub Actions YAML workflows natively on Chengis agents
- **Build federation** — Cross-instance build triggers for multi-team, multi-region CI/CD

---

## Version History

| Version | Phase | Theme | Tests | Migrations |
|---------|-------|-------|-------|------------|
| 0.1.0 | Core engine | Pipeline DSL, Git, SSE, CLI | ~50 | 1-7 |
| 0.2.0 | Expansion | Plugins, Docker, YAML, Distributed | 100 | 8-10 |
| 0.3.0 | Security | Auth, RBAC, JWT, Audit, Metrics | ~200 | 11-17 |
| 0.4.0 | Hardening | Queue, Circuit Breaker, Orphan Monitor | ~220 | 18-20 |
| 0.5.0 | Integration | Approvals, Templates, SCM Status, Email | ~250 | 21-22 |
| 0.6.0 | Operations | Env config, Docker deploy, Matrix, Backup | 283 | — |
| 0.7.0 | PostgreSQL | Dual-driver DB, HikariCP, Portable SQL | 319 | ×2 drivers |
| 0.8.0 | Identity | SSO/OIDC, Token Scopes, Vault Secrets | 362 | 23-24 |
| 0.9.0 | Multi-tenancy | Orgs, Isolation, Multi-approver | 362 | 25-28 |
| Remediation | Security | 37 fixes, cross-org regression tests | 403 | — |
| 1.0.0 | Governance | Policy engine, Checksums, Compliance, Feature flags | 449 | 29-31 |
| Phase 2 | Hardening | Dispatcher, Attempts, Durable events, Plugin/Docker policy | 488 | 32-34 |
| Phase 3 | K8s & HA | Persistent agents, Leader election, K8s manifests, Helm | 516 | 35 |
| Security II | Review | Auth bypass, Org scoping, Hash-chain integrity | 525 | 36 |
| Phase 4 | Performance | DAG execution, Caching, Delta artifacts, Resource scheduling, Dedup | **587** | 37-39 |
| Phase 5 | Observability | Tracing, Analytics, Notifications, Cost, Flaky tests, Grafana, Logs | **678** | 40-43 |
| Phase 6 | Advanced SCM | PR checks, Branch overrides, Monorepo, Dependencies, Cron, Gitea/Bitbucket, Webhook replay, Auto-merge | **838** | 44-47 |
| Phase 7 | Supply Chain | SLSA provenance, SBOM, Container scanning, OPA, License scanning, Artifact signing, Regulatory dashboards | **928** | 48-50 |
| Phase 8 | Enterprise Identity | SAML 2.0, LDAP/AD, Fine-grained RBAC, MFA/TOTP, Cross-org sharing, Cloud secret backends, Secret rotation | **1,067** | 51-58 |
| Phase 9 | Developer Experience | Pipeline linter, DAG visualization, Log search, Responsive UI, Theme toggle, Build comparison | **1,187** | — |
| Phase 10 | Scale & Performance | Chunked logs, Cursor pagination, Partitioning, Read replicas, Connection pooling, Backpressure, Multi-region | **1,257** | 59-62 |
| Phase 11 | Deployment & Release | Environments, Releases, Artifact promotion, Deployment strategies, Execution engine, Health checks, Dashboard | **1,352** | 63-69 |
| Phase 12 | Infrastructure as Code | IaC detection, Terraform/Pulumi/CloudFormation plugins, State management, Plan parsing, Cost estimation, IaC dashboard, Policy integration | **1,445** | 70-73 |
