#!/usr/bin/env bash
# Run throughput benchmark against Jenkins.
# Triggers N concurrent builds at various concurrency levels.
# Usage: bench-throughput.sh [results-dir]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/lib/jenkins-api.sh"

RESULTS_DIR="${1:-$SCRIPT_DIR/../results}"
JOB_NAME="bench-throughput"
TIMESTAMP=$(date +%s)
CONCURRENCY_LEVELS=(1 2 4 8 16)
BUILDS_PER_LEVEL=20
WARMUP=2

mkdir -p "$RESULTS_DIR"

echo "=== Jenkins Throughput Benchmark ==="
echo "  Job:         $JOB_NAME"
echo "  Levels:      ${CONCURRENCY_LEVELS[*]}"
echo "  Builds/level: $BUILDS_PER_LEVEL"
echo ""

# Warm-up
echo "Warm-up ($WARMUP iterations)..."
for i in $(seq 1 "$WARMUP"); do
  jenkins_run_build "$JOB_NAME" > /dev/null
done

# For each concurrency level, trigger N builds concurrently
level_results=()
for concurrency in "${CONCURRENCY_LEVELS[@]}"; do
  echo ""
  echo "Concurrency level: $concurrency"
  batches=$((BUILDS_PER_LEVEL / concurrency))
  if [ "$batches" -lt 1 ]; then batches=1; fi

  batch_results=()
  for batch in $(seq 1 "$batches"); do
    echo "  Batch $batch/$batches..."
    wall_start_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')

    # Trigger all builds concurrently
    pids=()
    tmpdir=$(mktemp -d)
    for i in $(seq 1 "$concurrency"); do
      (
        result=$(jenkins_run_build "$JOB_NAME")
        echo "$result" > "$tmpdir/result-$i"
      ) &
      pids+=($!)
    done

    # Wait for all to complete
    for pid in "${pids[@]}"; do
      wait "$pid" 2>/dev/null || true
    done

    wall_end_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')
    wall_ms=$(( (wall_end_ns - wall_start_ns) / 1000000 ))
    bps=$(echo "scale=2; $concurrency * 1000 / $wall_ms" | bc 2>/dev/null || echo "0")

    batch_results+=("$wall_ms,$bps")
    echo "    Wall: ${wall_ms}ms, Builds/sec: $bps"

    rm -rf "$tmpdir"
  done

  # Collect level summary
  level_results+=("$concurrency:${batch_results[*]}")
done

# Write results as JSON
{
  echo "{"
  echo "  \"benchmark\": \"throughput\","
  echo "  \"system\": \"jenkins\","
  echo "  \"timestamp\": $TIMESTAMP,"
  echo "  \"levels\": ["
  first_level=true
  for lr in "${level_results[@]}"; do
    IFS=':' read -r concurrency batches_str <<< "$lr"
    if [ "$first_level" = true ]; then
      first_level=false
    else
      echo ","
    fi
    echo "    {"
    echo "      \"concurrency\": $concurrency,"
    echo "      \"batches\": ["
    first_batch=true
    for b in $batches_str; do
      IFS=',' read -r wall_ms bps <<< "$b"
      if [ "$first_batch" = true ]; then
        first_batch=false
      else
        echo ","
      fi
      printf '        {"wall_ms": %s, "builds_per_sec": %s}' "$wall_ms" "$bps"
    done
    echo ""
    echo "      ]"
    echo -n "    }"
  done
  echo ""
  echo "  ]"
  echo "}"
} > "$RESULTS_DIR/jenkins-throughput-$TIMESTAMP.json"

echo ""
echo "Results written to: $RESULTS_DIR/jenkins-throughput-$TIMESTAMP.json"
