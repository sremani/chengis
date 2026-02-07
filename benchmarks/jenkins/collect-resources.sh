#!/usr/bin/env bash
# Monitor Jenkins JVM resource usage during benchmarks.
# Runs in background, samples at interval. Kill to stop.
# Usage: collect-resources.sh [interval-sec] [output-file]
set -euo pipefail

INTERVAL="${1:-0.5}"
OUTPUT_FILE="${2:-benchmarks/results/jenkins-resources.json}"
CONTAINER_NAME="${CONTAINER_NAME:-benchmarks-jenkins-1}"

mkdir -p "$(dirname "$OUTPUT_FILE")"

echo "Monitoring Jenkins resources (interval: ${INTERVAL}s)..."
echo "Output: $OUTPUT_FILE"
echo "Press Ctrl+C to stop."

# Initialize output file
echo '{"samples": [' > "$OUTPUT_FILE"
first=true

cleanup() {
  # Close JSON array
  echo ']}' >> "$OUTPUT_FILE"
  echo ""
  echo "Resource monitoring stopped. Results in: $OUTPUT_FILE"
  exit 0
}
trap cleanup INT TERM

while true; do
  timestamp=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')

  # Get CPU and memory via docker stats
  stats=$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}},{{.MemPerc}}' \
    "$CONTAINER_NAME" 2>/dev/null || echo "0%,0MiB/0MiB,0%")

  IFS=',' read -r cpu mem_usage mem_pct <<< "$stats"
  cpu=$(echo "$cpu" | tr -d '%')
  mem_pct=$(echo "$mem_pct" | tr -d '%')

  if [ "$first" = true ]; then
    first=false
  else
    echo "," >> "$OUTPUT_FILE"
  fi

  printf '  {"timestamp_ns": %s, "cpu_pct": %s, "mem_usage": "%s", "mem_pct": %s}' \
    "$timestamp" "${cpu:-0}" "${mem_usage:-0}" "${mem_pct:-0}" >> "$OUTPUT_FILE"

  sleep "$INTERVAL"
done
