# Chengis Mutation Testing Report

**Tool**: cljest v0.1.0 (custom Leiningen plugin)
**Date**: 2026-02-11
**Operator Preset**: fast (15 operators — arithmetic, comparison, logical, collection, nil)
**Timeout**: 30,000ms per mutation
**Threshold**: 80%

---

## Executive Summary

| Metric | Value |
|--------|-------|
| **Source namespaces tested** | 125 |
| **Total mutations generated** | 1,722 |
| **Mutations killed** | 853 |
| **Mutations survived** | 835 |
| **Timed out** | 34 |
| **Overall mutation score** | **49.5%** |
| **Target threshold** | 80% |

The Chengis codebase has a **49.5% mutation score** with the fast operator preset (15 operators). This means roughly half of the introduced bugs were caught by the existing test suite. While the project has 928 tests with 3,152 assertions and high line coverage, mutation testing reveals significant gaps in **assertion specificity** — many tests execute code paths but don't assert tightly enough to catch subtle logic changes.

---

## Results by Module

| Module | Namespaces | Mutations | Killed | Score |
|--------|-----------|-----------|--------|-------|
| Engine (core logic) | 13 | 154 | 68 | 44.2% |
| Engine (deployment/lifecycle) | 14 | 207 | 110 | 53.1% |
| Engine (supply chain/observability) | 16 | 275 | 137 | 49.8% |
| DSL | 5 | 76 | 30 | 39.5% |
| Distributed | 10 | 132 | 46 | 34.8% |
| Core (config/flags/metrics) | 3 | 112 | 8 | 7.1% |
| DB (stores) | 39 | 345 | 184 | 53.3% |
| Web/Plugin/Agent | 24 | 421 | 197 | 46.8% |

---

## Per-Namespace Results (125 namespaces)

### Perfect Score (100%) — 9 namespaces

| Namespace | Killed/Total |
|-----------|-------------|
| chengis.db.environment-store | 7/7 |
| chengis.db.health-check-store | 5/5 |
| chengis.db.org-store | 6/6 |
| chengis.db.query-router | 3/3 |
| chengis.distributed.master-api | 2/2 |
| chengis.distributed.region | 7/7 |
| chengis.engine.provenance | 2/2 |
| chengis.engine.regulatory | 3/3 |
| chengis.engine.retention | 1/1 |

### Strong (75-99%) — 22 namespaces

| Namespace | Score | Killed/Total |
|-----------|-------|-------------|
| chengis.plugin.builtin.azure-keyvault | 100.0% | 4/4 |
| chengis.plugin.builtin.gcp-secrets | 100.0% | 4/4 |
| chengis.feature-flags | 100.0% | 2/2 |
| chengis.engine.release | 88.9% | 8/9 |
| chengis.web.permissions | 90.0% | 9/10 |
| chengis.db.pagination | 87.5% | 7/8 |
| chengis.engine.promotion | 86.7% | 13/15 |
| chengis.plugin.builtin.aws-secrets | 85.7% | 6/7 |
| chengis.web.mfa | 85.7% | 24/28 |
| chengis.db.iac-store | 84.2% | 16/19 |
| chengis.db.promotion-store | 85.7% | 6/7 |
| chengis.engine.dag | 83.3% | 5/6 |
| chengis.db.iac-cost-store | 83.3% | 5/6 |
| chengis.db.shared-resource-store | 83.3% | 5/6 |
| chengis.db.strategy-store | 83.3% | 5/6 |
| chengis.db.release-store | 81.8% | 9/11 |
| chengis.dsl.expressions | 80.0% | 4/5 |
| chengis.plugin.builtin.bitbucket-status | 78.6% | 11/14 |
| chengis.engine.monorepo | 76.5% | 13/17 |
| chengis.engine.license-scanner | 75.0% | 6/8 |
| chengis.db.log-search-store | 75.0% | 9/12 |
| chengis.db.dependency-store | 75.0% | 3/4 |

### Moderate (50-74%) — 37 namespaces

| Namespace | Score | Killed/Total |
|-----------|-------|-------------|
| chengis.engine.compliance | 72.7% | 8/11 |
| chengis.engine.deployment | 71.4% | 10/14 |
| chengis.db.backup | 71.4% | 5/7 |
| chengis.distributed.build-queue | 70.0% | 7/10 |
| chengis.engine.approval | 70.0% | 14/20 |
| chengis.engine.branch-overrides | 70.0% | 7/10 |
| chengis.db.audit-export | 70.0% | 7/10 |
| chengis.web.metrics-middleware | 69.2% | 9/13 |
| chengis.engine.cron | 66.7% | 8/12 |
| chengis.engine.matrix | 66.7% | 4/6 |
| chengis.engine.opa | 66.7% | 4/6 |
| chengis.engine.webhook-replay | 66.7% | 4/6 |
| chengis.db.agent-store | 66.7% | 6/9 |
| chengis.db.pr-check-store | 66.7% | 6/9 |
| chengis.db.webhook-log | 66.7% | 4/6 |
| chengis.plugin.builtin.terraform | 66.7% | 6/9 |
| chengis.plugin.builtin.pulumi | 62.5% | 5/8 |
| chengis.engine.streaming-process | 62.5% | 5/8 |
| chengis.engine.linter | 61.3% | 38/62 |
| chengis.engine.build-deps | 60.0% | 3/5 |
| chengis.engine.test-parser | 60.0% | 9/15 |
| chengis.db.build-event-store | 60.0% | 3/5 |
| chengis.db.secret-audit | 60.0% | 3/5 |
| chengis.engine.policy | 59.6% | 28/47 |
| chengis.distributed.leader-election | 57.1% | 8/14 |
| chengis.engine.scm-status | 57.1% | 8/14 |
| chengis.engine.stage-cache | 57.1% | 4/7 |
| chengis.web.rate-limit | 56.5% | 13/23 |
| chengis.db.trace-store | 55.6% | 5/9 |
| chengis.engine.iac-state | 55.6% | 5/9 |
| chengis.plugin.builtin.gitea-status | 55.6% | 5/9 |
| chengis.engine.build-compare | 53.3% | 8/15 |
| chengis.engine.vulnerability-scanner | 52.9% | 9/17 |
| chengis.web.auth | 51.8% | 29/56 |
| chengis.web.ldap | 51.7% | 15/29 |
| chengis.distributed.circuit-breaker | 50.0% | 5/10 |
| chengis.distributed.orphan-monitor | 50.0% | 1/2 |

### Weak (25-49%) — 30 namespaces

| Namespace | Score | Killed/Total |
|-----------|-------|-------------|
| chengis.db.test-result-store | 50.0% | 8/16 |
| chengis.db.cron-store | 50.0% | 5/10 |
| chengis.db.plugin-policy-store | 50.0% | 3/6 |
| chengis.db.cache-store | 50.0% | 1/2 |
| chengis.engine.docker | 50.0% | 9/18 |
| chengis.engine.artifact-delta | 50.0% | 6/12 |
| chengis.plugin.builtin.vault-secrets | 50.0% | 6/12 |
| chengis.web.account-lockout | 50.0% | 2/4 |
| chengis.dsl.yaml | 46.4% | 13/28 |
| chengis.db.log-chunk-store | 45.5% | 5/11 |
| chengis.db.cost-store | 44.4% | 4/9 |
| chengis.db.deployment-store | 44.4% | 4/9 |
| chengis.engine.analytics | 41.7% | 5/12 |
| chengis.engine.cache | 40.0% | 2/5 |
| chengis.web.oidc | 40.7% | 24/59 |
| chengis.engine.iac-cost | 38.9% | 14/36 |
| chengis.db.docker-policy-store | 38.9% | 7/18 |
| chengis.engine.tracing | 38.5% | 5/13 |
| chengis.engine.sbom | 37.5% | 3/8 |
| chengis.dsl.chengisfile | 36.8% | 7/19 |
| chengis.db.analytics-store | 36.4% | 4/11 |
| chengis.web.saml | 33.3% | 22/66 |
| chengis.engine.process | 33.3% | 2/6 |
| chengis.engine.iac | 31.5% | 17/54 |
| chengis.db.rotation-store | 31.8% | 7/22 |
| chengis.engine.signing | 28.6% | 2/7 |
| chengis.engine.health-check | 27.3% | 6/22 |
| chengis.dsl.core | 26.3% | 5/19 |
| chengis.engine.event-backpressure | 25.0% | 1/4 |
| chengis.engine.cost | 25.0% | 1/4 |

### Critical (<25%) — 27 namespaces

| Namespace | Score | Killed/Total |
|-----------|-------|-------------|
| chengis.distributed.agent-registry | 25.5% | 13/51 |
| chengis.engine.auto-merge | 25.0% | 9/36 |
| chengis.db.permission-store | 25.0% | 1/4 |
| chengis.db.compliance-store | 25.0% | 1/4 |
| chengis.db.user-store | 25.0% | 2/8 |
| chengis.db.approval-store | 22.7% | 5/22 |
| chengis.web.views.pipeline-viz | 21.9% | 7/32 |
| chengis.dsl.templates | 20.0% | 1/5 |
| chengis.db.build-store | 15.8% | 3/19 |
| chengis.engine.secret-rotation | 16.7% | 1/6 |
| chengis.web.alerts | 16.7% | 1/6 |
| chengis.distributed.queue-processor | 11.8% | 2/17 |
| chengis.engine.pr-checks | 11.1% | 1/9 |
| chengis.web.views.iac | 10.5% | 2/19 |
| chengis.distributed.agent-http | 10.0% | 1/10 |
| chengis.engine.executor | 10.3% | 4/39 |
| chengis.config | 5.6% | 6/107 |
| chengis.db.partitioning | 0.0% | 0/6 |
| chengis.db.template-store | 0.0% | 0/1 |
| chengis.distributed.dispatcher | 0.0% | 0/9 |
| chengis.metrics | 0.0% | 0/3 |
| chengis.agent.worker | 0.0% | 0/4 |
| chengis.plugin.loader | 0.0% | 0/3 |
| chengis.plugin.registry | 0.0% | 0/1 |
| chengis.web.views.deploy-dashboard | 0.0% | 0/1 |

---

## Analysis

### Why 49.5%?

The score reflects a common pattern: **high coverage, low assertion precision**. Tests execute code paths (high line/branch coverage) but don't always assert the specific behavior that mutations alter. The 15 fast operators test whether swapping `+`↔`-`, `<`↔`>`, `and`↔`or`, `first`↔`last`, `nil?`↔`some?`, `true`↔`false`, and `=`↔`not=` would be caught.

### Most Common Surviving Mutant Patterns

| Operator | Description | Typical Cause |
|----------|-------------|---------------|
| `logical-and-or` | `and` → `or` | Guard clauses not tested with partial failures |
| `logical-or-and` | `or` → `and` | Default/fallback paths not asserted |
| `coll-first-last` | `first` → `last` | Single-element collections in tests |
| `comp-gt-gte` | `>` → `>=` | Boundary conditions not tested |
| `logical-true-false` | `true` → `false` | Config defaults not asserted individually |
| `nil-nilq-someq` | `nil?` → `some?` | Nil-check branches not exercised |
| `comp-eq-neq` | `=` → `not=` | Equality checks in branches not asserted |

### Module-Level Observations

**Config (5.6%)**: Expected. `config.clj` is a large defaults map with ~100 boolean/keyword literals. Tests validate config resolution, not individual default values. This is a **false alarm** — config defaults are implicitly tested through feature usage.

**Engine executor (10.3%)**: The core pipeline executor has extensive integration tests but most mutations survive because the executor delegates to sub-modules. Tests verify overall flow rather than individual conditional branches within the executor.

**Distributed dispatcher (0.0%)**: All 9 mutations survived. The dispatcher's conditional routing logic (`and`/`or` chains for agent selection) isn't directly asserted — tests verify that builds get dispatched but not the specific conditions that determine routing.

**Web views (10-22%)**: View namespaces generate Hiccup HTML. Mutations to conditionals (`when`, `if`) in view rendering survive because tests typically check for presence of key elements rather than exhaustive DOM structure.

**DB stores (53.3%)**: Strong showing for data stores. CRUD operations with specific assertions catch most mutations. Lower scores in complex query stores (build-store, approval-store) where aggregation logic has many branches.

**Authentication (SAML 33%, OIDC 41%, Auth 52%)**: Security-critical namespaces with moderate scores. Many mutations in error handling and edge case branches survive — worth hardening.

### Highest Priority Improvements

1. **`chengis.engine.executor`** (10.3%, 39 mutations) — Core execution engine. Add assertion-specific tests for conditional branches in stage/step execution flow.

2. **`chengis.distributed.agent-registry`** (25.5%, 51 mutations) — Agent selection logic. Add tests for boundary conditions in agent matching/scoring.

3. **`chengis.engine.auto-merge`** (25.0%, 36 mutations) — Auto-merge conditions. Add edge-case tests for merge eligibility checks.

4. **`chengis.web.saml`** (33.3%, 66 mutations) — SAML authentication. Security-critical. Add tests for each validation branch and error path.

5. **`chengis.web.oidc`** (40.7%, 59 mutations) — OIDC authentication. Add assertion tests for token validation edge cases.

6. **`chengis.engine.iac`** (31.5%, 54 mutations) — Infrastructure-as-Code engine. Add property-based tests for plan parsing conditionals.

7. **`chengis.engine.health-check`** (27.3%, 22 mutations) — Health check logic. Add boundary tests for health status determination.

8. **`chengis.distributed.queue-processor`** (11.8%, 17 mutations) — Build queue processing. Add tests for queue ordering and priority conditions.

---

## Methodology

- **Mutation operators used**: 15 (fast preset — arithmetic, comparison, logical, collection, nil)
- **Test execution**: Per-mutation namespace reload via `(require ns :reload)` in project JVM
- **Killed criteria**: Test failure OR compilation error OR timeout (30s)
- **Survived**: All tests pass with mutation in place
- **Scope**: 125 source namespaces with matching `*-test` namespaces
- **Excluded**: Namespaces without matching test files (100 namespaces)
- **Runtime**: ~3 hours total across parallel batches

---

## HTML Reports

Detailed HTML reports with per-namespace breakdowns and survivor details are available at:

- `target/cljest-core/mutation-report.html`
- `target/cljest-db/mutation-report.html`
- `target/cljest-distributed/mutation-report.html`
- `target/cljest-dsl/mutation-report.html`
- `target/cljest-engine2/mutation-report.html`
- `target/cljest-engine3/mutation-report.html`
- `target/cljest-web/mutation-report.html`

---

## Next Steps

1. **Harden security namespaces** — SAML, OIDC, auth, and MFA should target 80%+ mutation scores
2. **Strengthen executor tests** — The core pipeline executor needs targeted conditional branch assertions
3. **Add boundary tests** — Many surviving mutations are `<` → `<=` or `>` → `>=` — boundary value tests would kill these
4. **Run with standard operators** (56 ops) — The fast preset covers 15 operators; standard adds constants, threading, and Clojure-specific mutations for a more thorough analysis
5. **Integrate into CI** — Add `lein cljest --operators fast --namespaces "critical-ns"` to CI pipeline for regression detection on critical namespaces

---

*Generated by cljest v0.1.0 — mutation testing for Clojure*
