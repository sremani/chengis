#!/usr/bin/env bash
# Run overhead benchmark against Jenkins.
# Usage: bench-overhead.sh [iterations] [warmup] [results-dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/jenkins-api.sh"

ITERATIONS="${1:-100}"
WARMUP="${2:-5}"
RESULTS_DIR="${3:-$SCRIPT_DIR/../results}"
JOB_NAME="bench-echo-overhead"
TIMESTAMP=$(date +%s)

mkdir -p "$RESULTS_DIR"

echo "=== Jenkins Overhead Benchmark ==="
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
  if [ $((i % 25)) -eq 0 ]; then
    echo "  Progress: $i/$ITERATIONS"
  fi
done

# Write results as JSON
echo ""
echo "Writing results..."
{
  echo "{"
  echo "  \"benchmark\": \"overhead\","
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
} > "$RESULTS_DIR/jenkins-overhead-$TIMESTAMP.json"

echo "Results written to: $RESULTS_DIR/jenkins-overhead-$TIMESTAMP.json"

# Quick summary
echo ""
echo "--- Quick Summary ---"
wall_times=()
for r in "${results[@]}"; do
  IFS=',' read -r wall_ms _ _ <<< "$r"
  wall_times+=("$wall_ms")
done

# Calculate median using sort + awk
median=$(printf '%s\n' "${wall_times[@]}" | sort -n | awk '{a[NR]=$1} END {
  if (NR%2) print a[(NR+1)/2]
  else print (a[NR/2]+a[NR/2+1])/2
}')

total=0
for t in "${wall_times[@]}"; do total=$((total + t)); done
mean=$((total / ${#wall_times[@]}))

echo "  Wall-clock median: ${median}ms"
echo "  Wall-clock mean:   ${mean}ms"
echo "  Iterations:        $ITERATIONS"
