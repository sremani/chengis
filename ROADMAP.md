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

## Phase 7: Supply Chain Security

**Theme:** Build provenance, software bill of materials, and compliance automation.

| Feature | Description | Priority |
|---------|-------------|----------|
| SLSA provenance | Generate SLSA v1.0 provenance attestations for build outputs | High |
| SBOM generation | CycloneDX and SPDX bill-of-materials from build artifacts | High |
| Container image scanning | Trivy/Grype integration for CVE detection before deployment | High |
| Policy-as-code | Define build policies in OPA/Rego for complex decision logic | Medium |
| License scanning | Dependency license detection and policy enforcement | Medium |
| Signed artifacts | GPG/Sigstore signing of build artifacts and attestations | Medium |
| Regulatory dashboards | SOC 2 / ISO 27001 readiness indicators based on audit trail completeness | Low |

---

## Phase 8: Enterprise Identity & Access

**Theme:** Advanced authentication, fine-grained permissions, and cross-org capabilities.

| Feature | Description | Priority |
|---------|-------------|----------|
| SAML 2.0 | Enterprise SSO via SAML alongside existing OIDC | High |
| LDAP/Active Directory | Directory-based user provisioning and group sync | High |
| Fine-grained RBAC | Resource-level permissions (e.g., user X can trigger Job Y but not Job Z) | High |
| Multi-factor authentication | TOTP-based 2FA for user accounts | Medium |
| Cross-org shared resources | Shared agent pools and pipeline templates across organizations | Medium |
| Additional secret backends | AWS Secrets Manager, Google Secret Manager, Azure Key Vault | Medium |
| Automatic secret rotation | Policy-driven rotation schedules for credentials | Low |

---

## Phase 9: Developer Experience

**Theme:** Quality-of-life improvements for pipeline authors and operators.

| Feature | Description | Priority |
|---------|-------------|----------|
| Pipeline linter CLI | Offline validation of Chengisfile/YAML with detailed error messages before push | High |
| Pipeline visualization | DAG-style graphical pipeline view showing stage/step relationships | High |
| Build log search | Full-text search across build logs with highlighting | Medium |
| Mobile-responsive UI | Responsive layout for monitoring builds on mobile devices | Medium |
| Light theme option | User-selectable light/dark theme toggle | Medium |
| Customizable dashboard | Drag-and-drop widget layout for the home page | Low |
| Build comparison | Side-by-side diff of two build runs (timing, logs, artifacts) | Low |
| IDE integration | VS Code extension for triggering builds and viewing results | Low |

---

## Phase 10: Scale & Performance

**Theme:** Handle large-scale deployments with thousands of builds per day.

| Feature | Description | Priority |
|---------|-------------|----------|
| Build log streaming optimization | Chunked log storage with lazy loading for builds with 100k+ lines | High |
| API pagination | Cursor-based pagination on all list endpoints for large datasets | High |
| Database partitioning | Time-based partitioning of builds, events, and audit tables in PostgreSQL | Medium |
| Read replicas | Query routing to PostgreSQL read replicas for dashboard/analytics | Medium |
| Agent connection pooling | Persistent HTTP connections to agents to reduce dispatch latency | Medium |
| Event bus backpressure | Adaptive backpressure on the core.async event channel under high load | Low |
| Multi-region support | Agents spanning geographic regions with locality-aware dispatch | Low |

---

## Future Exploration

These items are under consideration but not yet scheduled:

- **AI-powered recommendations** — Flaky test detection, build time prediction, auto-suggested pipeline optimizations
- **Plugin marketplace** — Curated community plugins with versioning and dependency resolution
- **GitOps pipeline sync** — Pipeline definitions synced from Git branches with PR-based review workflow
- **Chengis Cloud** — Managed SaaS offering with per-org isolation, auto-scaling agents, and usage-based billing
- **GitHub Actions compatibility layer** — Run GitHub Actions YAML workflows natively on Chengis agents
- **Deployment orchestration** — Blue/green, canary, and rolling deployment strategies as first-class pipeline stages
- **Build federation** — Cross-instance build triggers for multi-team, multi-region CI/CD
- **Terraform/Pulumi integration** — Infrastructure provisioning as pipeline steps with state management

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
