#!/usr/bin/env bash
# Run realistic benchmark against Jenkins.
# Usage: bench-realistic.sh [iterations] [warmup] [results-dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/jenkins-api.sh"

ITERATIONS="${1:-10}"
WARMUP="${2:-2}"
RESULTS_DIR="${3:-$SCRIPT_DIR/../results}"
JOB_NAME="bench-realistic"
TIMESTAMP=$(date +%s)

mkdir -p "$RESULTS_DIR"

echo "=== Jenkins Realistic Benchmark ==="
echo "  Job:        $JOB_NAME"
echo "  Iterations: $ITERATIONS"
echo "  Warm-up:    $WARMUP"
echo ""

# Warm-up
echo "Warm-up ($WARMUP iterations)..."
for i in $(seq 1 "$WARMUP"); do
  result=$(jenkins_run_build "$JOB_NAME")
  echo "  Warm-up $i: $result"
done

# Measured iterations
echo ""
echo "Measuring ($ITERATIONS iterations)..."
results=()
for i in $(seq 1 "$ITERATIONS"); do
  result=$(jenkins_run_build "$JOB_NAME")
  results+=("$result")
  IFS=',' read -r wall_ms jenkins_ms status <<< "$result"
  echo "  Iteration $i: wall=${wall_ms}ms jenkins=${jenkins_ms}ms status=$status"
done

# Write results as JSON
{
  echo "{"
  echo "  \"benchmark\": \"realistic\","
  echo "  \"system\": \"jenkins\","
  echo "  \"timestamp\": $TIMESTAMP,"
  echo "  \"iterations\": $ITERATIONS,"
  echo "  \"warmup\": $WARMUP,"
  echo "  \"results\": ["
  first=true
  for r in "${results[@]}"; do
    IFS=',' read -r wall_ms jenkins_ms status <<< "$r"
    if [ "$first" = true ]; then
      first=false
    else
      echo ","
    fi
    printf '    {"wall_ms": %s, "jenkins_ms": %s, "status": "%s"}' \
      "$wall_ms" "$jenkins_ms" "$status"
  done
  echo ""
  echo "  ]"
  echo "}"
} > "$RESULTS_DIR/jenkins-realistic-$TIMESTAMP.json"

echo ""
echo "Results written to: $RESULTS_DIR/jenkins-realistic-$TIMESTAMP.json"
