#!/usr/bin/env bash
# Run the complete Chengis vs Jenkins benchmark suite.
# Usage: benchmarks/run-all.sh [--chengis-only] [--jenkins-only]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$SCRIPT_DIR/results"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

RUN_CHENGIS=true
RUN_JENKINS=true

for arg in "$@"; do
  case "$arg" in
    --chengis-only) RUN_JENKINS=false ;;
    --jenkins-only) RUN_CHENGIS=false ;;
    --help|-h)
      echo "Usage: run-all.sh [--chengis-only] [--jenkins-only]"
      echo ""
      echo "  --chengis-only   Run only Chengis benchmarks"
      echo "  --jenkins-only   Run only Jenkins benchmarks (requires Docker)"
      echo ""
      echo "Results are written to: benchmarks/results/"
      exit 0
      ;;
  esac
done

mkdir -p "$RESULTS_DIR"

echo "================================================================"
echo "       CHENGIS vs JENKINS BENCHMARK SUITE"
echo "================================================================"
echo "  Timestamp: $TIMESTAMP"
echo "  Results:   $RESULTS_DIR"
echo "================================================================"
echo ""

# Phase 1: Chengis benchmarks
if [ "$RUN_CHENGIS" = true ]; then
  echo "--- Phase 1: Chengis Benchmarks ---"
  cd "$PROJECT_ROOT"
  lein with-profile bench run -- \
    --benchmark all \
    --output "$RESULTS_DIR"
  echo ""
fi

# Phase 2: Jenkins setup + benchmarks
if [ "$RUN_JENKINS" = true ]; then
  echo "--- Phase 2: Jenkins Setup ---"
  cd "$SCRIPT_DIR/jenkins"

  # Start Jenkins if not running
  if ! docker compose ps --quiet jenkins 2>/dev/null | grep -q .; then
    echo "Starting Jenkins..."
    docker compose up -d
    echo "Waiting for Jenkins to be ready..."
    for i in $(seq 1 120); do
      if curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/login 2>/dev/null | grep -q "200"; then
        echo "Jenkins is ready."
        break
      fi
      if [ "$i" -eq 120 ]; then
        echo "ERROR: Jenkins did not start within 120 seconds."
        echo "Skipping Jenkins benchmarks."
        RUN_JENKINS=false
        break
      fi
      sleep 1
    done
  fi

  if [ "$RUN_JENKINS" = true ]; then
    # Check if JENKINS_TOKEN is set
    if [ -z "${JENKINS_TOKEN:-}" ]; then
      echo ""
      echo "WARNING: JENKINS_TOKEN not set."
      echo "Run setup-jenkins.sh first, then set JENKINS_USER and JENKINS_TOKEN."
      echo "Skipping Jenkins benchmarks."
      RUN_JENKINS=false
    fi
  fi

  if [ "$RUN_JENKINS" = true ]; then
    # Setup jobs if needed
    bash setup-jenkins.sh

    echo ""
    echo "--- Phase 3: Jenkins Benchmarks ---"

    # Start resource monitoring in background
    bash collect-resources.sh 0.5 "$RESULTS_DIR/jenkins-resources-$TIMESTAMP.json" &
    MONITOR_PID=$!

    # Run benchmarks
    bash bench-overhead.sh 100 5 "$RESULTS_DIR"
    bash bench-realistic.sh 10 2 "$RESULTS_DIR"
    bash bench-throughput.sh "$RESULTS_DIR"

    # Stop resource monitor
    kill $MONITOR_PID 2>/dev/null || true
    wait $MONITOR_PID 2>/dev/null || true
  fi
fi

# Phase 3/4: Generate comparison report
if [ "$RUN_CHENGIS" = true ] && [ "$RUN_JENKINS" = true ]; then
  echo ""
  echo "--- Comparison Report ---"
  cd "$PROJECT_ROOT"

  # Find latest Chengis results
  CHENGIS_FILE=$(ls -t "$RESULTS_DIR"/chengis-bench-*.edn 2>/dev/null | head -1 || true)
  if [ -n "$CHENGIS_FILE" ]; then
    lein with-profile bench run -m chengis.bench.compare \
      "$CHENGIS_FILE" "$RESULTS_DIR"
  else
    echo "No Chengis results found to compare."
  fi
fi

echo ""
echo "================================================================"
echo "  Benchmark suite complete!"
echo "  Results in: $RESULTS_DIR"
echo "================================================================"
