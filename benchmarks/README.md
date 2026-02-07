# Chengis Benchmark Suite

Performance benchmarks comparing Chengis CI/CD engine against Jenkins.

## Quick Start

### Chengis Only (no Docker required)

```bash
# Run all Chengis benchmarks
lein with-profile bench run

# Run specific benchmark with custom iterations
lein with-profile bench run -- --benchmark overhead --iterations 50

# Available benchmarks: overhead, realistic, throughput, all
lein with-profile bench run -- --benchmark throughput
```

### Full Comparison (requires Docker)

```bash
# Start Jenkins
cd benchmarks/jenkins
docker compose up -d
bash setup-jenkins.sh    # Follow instructions to configure

# Set Jenkins credentials
export JENKINS_USER=admin
export JENKINS_TOKEN=<your-api-token>

# Run complete suite
bash benchmarks/run-all.sh

# Or run only Chengis / only Jenkins
bash benchmarks/run-all.sh --chengis-only
bash benchmarks/run-all.sh --jenkins-only
```

## Benchmarks

### 1. Overhead (`--benchmark overhead`)
Measures pure CI/CD framework overhead using `echo hello`.

- **Executor-only mode**: Pipeline engine without DB persistence
- **Full-lifecycle mode**: Complete build lifecycle including DB writes
- Reports: total time, step execution time, framework overhead

### 2. Realistic (`--benchmark realistic`)
Multi-stage pipeline: git clone, compile, test.

- Uses `clojure/tools.cli` repo (small, fast)
- Per-stage timing breakdown
- Exercises real I/O and process management

### 3. Throughput (`--benchmark throughput`)
Concurrent build scalability.

- Tests concurrency levels: 1, 2, 4, 8, 16
- Measures builds/second and scaling efficiency
- Uses 100ms simulated workload per build

## Output

Results are written to `benchmarks/results/` in three formats:
- `.edn` — Clojure data (machine-readable)
- `.json` — JSON (for Jenkins comparison)
- `.txt` — Human-readable summary

## Configuration

Edit `benchmarks/src/chengis/bench/config.clj` defaults or override via CLI:

| Option | Default | Description |
|--------|---------|-------------|
| `--benchmark` | `all` | Which benchmark to run |
| `--iterations` | varies | Override iteration count |
| `--output` | `benchmarks/results` | Output directory |

## Jenkins Setup

1. `cd benchmarks/jenkins && docker compose up -d`
2. Open http://localhost:8081 and complete initial setup
3. Install Pipeline plugin (suggested plugins include it)
4. Generate API token at http://localhost:8081/user/admin/configure
5. `export JENKINS_TOKEN=<token>`
6. `bash setup-jenkins.sh` to create benchmark jobs

## Architecture

```
benchmarks/
  src/chengis/bench/     # Clojure benchmark harness
    config.clj           # Configuration defaults
    stats.clj            # Statistical functions
    system.clj           # System bootstrap
    resource_monitor.clj # JVM metrics sampling
    overhead.clj         # Overhead benchmark
    realistic.clj        # Realistic benchmark
    throughput.clj       # Throughput benchmark
    report.clj           # Result formatting
    runner.clj           # CLI entry point

  jenkins/               # Jenkins comparison scripts
    docker-compose.yml   # Jenkins container
    pipelines/*.groovy   # Equivalent Jenkinsfiles
    bench-*.sh           # Benchmark scripts
    lib/jenkins-api.sh   # REST API helpers

  compare/               # Side-by-side comparison
    generate-report.clj  # Report generator
```
