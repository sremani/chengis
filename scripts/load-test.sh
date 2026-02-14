#!/usr/bin/env bash
# =============================================================================
# Chengis — HTTP Load Test Script
# =============================================================================
#
# Lightweight HTTP load testing using `hey` (https://github.com/rakyll/hey).
# Tests key endpoints for latency, throughput, and error rate.
#
# Usage:
#   ./scripts/load-test.sh                           # Defaults: localhost:8080
#   ./scripts/load-test.sh --base-url http://host:9090
#   ./scripts/load-test.sh --concurrency 20 --requests 2000
#
# Thresholds (fail CI if exceeded):
#   - Error rate: > 1%
#   - p99 latency: > 2000ms
#   - p50 latency: > 500ms
#
# Requirements: hey (Go HTTP load generator)
#   Install: go install github.com/rakyll/hey@latest
#       or:  brew install hey
#       or:  apt-get install hey
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration (overridable via flags)
# ---------------------------------------------------------------------------

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONCURRENCY="${CONCURRENCY:-10}"
REQUESTS="${REQUESTS:-1000}"
P99_THRESHOLD_MS="${P99_THRESHOLD_MS:-2000}"
P50_THRESHOLD_MS="${P50_THRESHOLD_MS:-500}"
ERROR_RATE_THRESHOLD="${ERROR_RATE_THRESHOLD:-1.0}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

# Test counters
PASS_COUNT=0
FAIL_COUNT=0

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------

while [[ $# -gt 0 ]]; do
  case "$1" in
    --base-url)      BASE_URL="$2"; shift 2 ;;
    --concurrency)   CONCURRENCY="$2"; shift 2 ;;
    --requests)      REQUESTS="$2"; shift 2 ;;
    --p99-threshold) P99_THRESHOLD_MS="$2"; shift 2 ;;
    --p50-threshold) P50_THRESHOLD_MS="$2"; shift 2 ;;
    --error-threshold) ERROR_RATE_THRESHOLD="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --base-url URL        Base URL (default: http://localhost:8080)"
      echo "  --concurrency N       Concurrent workers (default: 10)"
      echo "  --requests N          Total requests per endpoint (default: 1000)"
      echo "  --p99-threshold MS    Max p99 latency in ms (default: 2000)"
      echo "  --p50-threshold MS    Max p50 latency in ms (default: 500)"
      echo "  --error-threshold PCT Max error rate % (default: 1.0)"
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

check_hey() {
  if ! command -v hey &>/dev/null; then
    echo -e "${RED}Error: 'hey' is not installed.${NC}"
    echo ""
    echo "Install with one of:"
    echo "  go install github.com/rakyll/hey@latest"
    echo "  brew install hey"
    echo "  apt-get install hey"
    echo ""
    echo "Or download from: https://github.com/rakyll/hey/releases"
    exit 1
  fi
}

# Run hey and parse results. Returns: requests/sec, p50_ms, p99_ms, error_rate
run_load_test() {
  local endpoint="$1"
  local n="${2:-$REQUESTS}"
  local c="${3:-$CONCURRENCY}"
  local url="${BASE_URL}${endpoint}"

  local output
  output=$(hey -n "$n" -c "$c" -t 30 "$url" 2>&1)

  # Parse summary metrics from hey output
  local rps p50 p99 status_200 total_reqs error_rate

  rps=$(echo "$output" | grep "Requests/sec:" | awk '{print $2}' || echo "0")
  p50=$(echo "$output" | grep "50% in" | awk '{print $3}' || echo "0")
  p99=$(echo "$output" | grep "99% in" | awk '{print $3}' || echo "0")

  # hey reports latency in seconds — convert to ms
  p50_ms=$(echo "$p50" | awk '{printf "%.1f", $1 * 1000}')
  p99_ms=$(echo "$p99" | awk '{printf "%.1f", $1 * 1000}')

  # Count non-200 responses
  status_200=$(echo "$output" | grep -E "^\s*\[200\]" | awk '{print $2}' || echo "0")
  total_reqs="$n"

  if [ "$status_200" = "0" ] || [ -z "$status_200" ]; then
    # If no [200] line, check if there are status code lines at all
    local any_status
    any_status=$(echo "$output" | grep -cE "^\s*\[" || echo "0")
    if [ "$any_status" = "0" ]; then
      # hey didn't output status breakdown — assume all OK if no errors reported
      status_200="$total_reqs"
    fi
  fi

  local errors=$((total_reqs - status_200))
  if [ "$total_reqs" -gt 0 ]; then
    error_rate=$(awk "BEGIN {printf \"%.2f\", ($errors / $total_reqs) * 100}")
  else
    error_rate="0.00"
  fi

  echo "${rps}|${p50_ms}|${p99_ms}|${error_rate}"
}

check_threshold() {
  local label="$1"
  local metric="$2"
  local threshold="$3"
  local unit="$4"

  local passed
  passed=$(awk "BEGIN {print ($metric <= $threshold) ? 1 : 0}")

  if [ "$passed" = "1" ]; then
    PASS_COUNT=$((PASS_COUNT + 1))
    echo -e "    ${GREEN}✓${NC} ${label}: ${metric}${unit} (threshold: ${threshold}${unit})"
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    echo -e "    ${RED}✗${NC} ${label}: ${metric}${unit} ${RED}exceeds threshold ${threshold}${unit}${NC}"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

check_hey

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║           Chengis — HTTP Load Test Suite                    ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  Base URL:     ${CYAN}${BASE_URL}${NC}"
echo -e "  Concurrency:  ${CONCURRENCY}"
echo -e "  Requests:     ${REQUESTS} per endpoint"
echo -e "  Thresholds:   p50 < ${P50_THRESHOLD_MS}ms, p99 < ${P99_THRESHOLD_MS}ms, errors < ${ERROR_RATE_THRESHOLD}%"
echo ""

# Wait for server to be ready
echo -e "${CYAN}Waiting for server...${NC}"
for i in $(seq 1 30); do
  if curl -sf "${BASE_URL}/health" >/dev/null 2>&1; then
    echo -e "${GREEN}Server ready${NC}"
    break
  fi
  if [ "$i" = "30" ]; then
    echo -e "${RED}Server not responding at ${BASE_URL}/health after 30s${NC}"
    exit 1
  fi
  sleep 1
done
echo ""

# ---------------------------------------------------------------------------
# Endpoint tests
# ---------------------------------------------------------------------------

ENDPOINTS=(
  "/health|${REQUESTS}|${CONCURRENCY}"
  "/api/builds|$((REQUESTS / 2))|${CONCURRENCY}"
  "/metrics|$((REQUESTS / 5))|$((CONCURRENCY / 2 > 0 ? CONCURRENCY / 2 : 1))"
  "/login|$((REQUESTS / 5))|$((CONCURRENCY / 2 > 0 ? CONCURRENCY / 2 : 1))"
)

for entry in "${ENDPOINTS[@]}"; do
  IFS='|' read -r endpoint n c <<< "$entry"
  echo -e "${BOLD}━━━ ${endpoint} (${n} requests, ${c} concurrent) ━━━${NC}"

  result=$(run_load_test "$endpoint" "$n" "$c")
  IFS='|' read -r rps p50_ms p99_ms error_rate <<< "$result"

  echo -e "    Requests/sec: ${CYAN}${rps}${NC}"

  check_threshold "p50 latency" "$p50_ms" "$P50_THRESHOLD_MS" "ms"
  check_threshold "p99 latency" "$p99_ms" "$P99_THRESHOLD_MS" "ms"
  check_threshold "Error rate" "$error_rate" "$ERROR_RATE_THRESHOLD" "%"
  echo ""
done

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

TOTAL=$((PASS_COUNT + FAIL_COUNT))

echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                    LOAD TEST SUMMARY                        ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}  ${GREEN}Passed: ${PASS_COUNT}${NC}"
echo -e "${BOLD}║${NC}  ${RED}Failed: ${FAIL_COUNT}${NC}"
echo -e "${BOLD}║${NC}  Total:  ${TOTAL}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

if [ "$FAIL_COUNT" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}All load test thresholds met!${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}${FAIL_COUNT} threshold(s) exceeded.${NC}"
  exit 1
fi
