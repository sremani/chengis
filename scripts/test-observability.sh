#!/usr/bin/env bash
# =============================================================================
# Chengis Phase 2: Observability E2E Test Suite
# =============================================================================
#
# Automated end-to-end tests for metrics, structured logging, and alerts.
# Starts a Chengis server with metrics enabled, exercises all observability
# features, and reports pass/fail for each check.
#
# Usage:
#   ./scripts/test-observability.sh           # Run all tests
#   ./scripts/test-observability.sh --skip-unit  # Skip lein test (faster)
#
# Requirements: curl, lein, bash 4+
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

PORT=9099
BASE_URL="http://localhost:${PORT}"
TEMP_DIR="/tmp/chengis-e2e-$$"
TEMP_DB="${TEMP_DIR}/chengis-e2e.db"
TEMP_LOG="${TEMP_DIR}/chengis-e2e.log"
TEMP_CONFIG="${TEMP_DIR}/config.edn"
ORIG_CONFIG="resources/config.edn"
ORIG_CONFIG_BACKUP="${TEMP_DIR}/config.edn.bak"
SERVER_PID=""
SKIP_UNIT=false
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# Test counters
PASS_COUNT=0
FAIL_COUNT=0
TOTAL_COUNT=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------

for arg in "$@"; do
  case "$arg" in
    --skip-unit) SKIP_UNIT=true ;;
    --help|-h)
      echo "Usage: $0 [--skip-unit] [--help]"
      echo "  --skip-unit  Skip 'lein test' (faster iteration)"
      exit 0
      ;;
  esac
done

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

log_header() {
  echo ""
  echo -e "${CYAN}${BOLD}━━━ $1 ━━━${NC}"
}

log_info() {
  echo -e "  ${CYAN}ℹ${NC}  $1"
}

check_pass() {
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "  ${GREEN}✓ PASS${NC}  $1"
}

check_fail() {
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "  ${RED}✗ FAIL${NC}  $1"
  if [ -n "${2:-}" ]; then
    echo -e "         ${RED}→ $2${NC}"
  fi
}

wait_for_server() {
  local max_wait=120  # seconds
  local elapsed=0
  log_info "Waiting for server on port ${PORT}..."
  while [ $elapsed -lt $max_wait ]; do
    if curl -sf "${BASE_URL}/health" >/dev/null 2>&1; then
      log_info "Server ready (${elapsed}s)"
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  echo -e "  ${RED}Server failed to start within ${max_wait}s${NC}"
  return 1
}

cleanup() {
  log_header "Cleanup"

  # Kill server if running
  if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    log_info "Stopping server (PID: ${SERVER_PID})..."
    kill "$SERVER_PID" 2>/dev/null || true
    # Wait briefly, then force kill
    sleep 2
    kill -9 "$SERVER_PID" 2>/dev/null || true
  fi

  # Restore original config
  if [ -f "${ORIG_CONFIG_BACKUP}" ]; then
    log_info "Restoring original config.edn"
    cp "${ORIG_CONFIG_BACKUP}" "${PROJECT_ROOT}/${ORIG_CONFIG}"
  fi

  # Clean temp files
  if [ -d "${TEMP_DIR}" ]; then
    log_info "Removing temp directory: ${TEMP_DIR}"
    rm -rf "${TEMP_DIR}"
  fi

  # Clean temp DB files (WAL/SHM)
  rm -f "${TEMP_DB}" "${TEMP_DB}-wal" "${TEMP_DB}-shm" 2>/dev/null || true

  log_info "Cleanup complete"
}

# Always cleanup on exit
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

cd "${PROJECT_ROOT}"

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║     Chengis Phase 2: Observability E2E Test Suite          ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ---------------------------------------------------------------------------
# Step 0: Setup
# ---------------------------------------------------------------------------

log_header "Setup"

mkdir -p "${TEMP_DIR}"
log_info "Temp directory: ${TEMP_DIR}"

# Backup original config
if [ -f "${ORIG_CONFIG}" ]; then
  cp "${ORIG_CONFIG}" "${ORIG_CONFIG_BACKUP}"
  log_info "Backed up original config.edn"
fi

# Write test config (metrics enabled, JSON logging, temp DB, custom port)
cat > "${TEMP_CONFIG}" <<'CONFEOF'
{:database {:path "/tmp/chengis-e2e-PLACEHOLDER/chengis-e2e.db"}
 :workspace {:root "/tmp/chengis-e2e-PLACEHOLDER/workspaces"}
 :log {:level :info :format :json :file "/tmp/chengis-e2e-PLACEHOLDER/chengis-e2e.log"}
 :scheduler {:enabled false}
 :server {:port 9099 :host "0.0.0.0"}
 :auth {:enabled false}
 :metrics {:enabled true :path "/metrics" :auth-required false}
 :audit {:enabled false}}
CONFEOF

# Replace PLACEHOLDER with actual temp dir (portable sed)
if [[ "$OSTYPE" == "darwin"* ]]; then
  sed -i '' "s|/tmp/chengis-e2e-PLACEHOLDER|${TEMP_DIR}|g" "${TEMP_CONFIG}"
else
  sed -i "s|/tmp/chengis-e2e-PLACEHOLDER|${TEMP_DIR}|g" "${TEMP_CONFIG}"
fi

# Install test config as resources/config.edn so lein uses it
cp "${TEMP_CONFIG}" "${ORIG_CONFIG}"
log_info "Installed test config (port: ${PORT}, metrics: enabled, log: json)"

mkdir -p "${TEMP_DIR}/workspaces"

# ---------------------------------------------------------------------------
# Step 1: Unit tests
# ---------------------------------------------------------------------------

log_header "Test 1: Unit Tests"

if [ "$SKIP_UNIT" = true ]; then
  log_info "Skipped (--skip-unit flag)"
else
  if lein test > "${TEMP_DIR}/unit-test-output.txt" 2>&1; then
    # Extract test counts from output
    UNIT_SUMMARY=$(tail -5 "${TEMP_DIR}/unit-test-output.txt" | grep -E "^Ran [0-9]+ tests" || echo "")
    check_pass "Unit tests pass: ${UNIT_SUMMARY}"
  else
    UNIT_ERRORS=$(tail -20 "${TEMP_DIR}/unit-test-output.txt")
    check_fail "Unit tests failed" "See ${TEMP_DIR}/unit-test-output.txt"
  fi
fi

# ---------------------------------------------------------------------------
# Step 2: Start server
# ---------------------------------------------------------------------------

log_header "Starting Server"

log_info "Starting Chengis server with metrics enabled on port ${PORT}..."
lein run serve > "${TEMP_DIR}/server-stdout.log" 2>&1 &
SERVER_PID=$!
log_info "Server PID: ${SERVER_PID}"

if ! wait_for_server; then
  echo ""
  echo -e "${RED}${BOLD}Server failed to start. Stdout:${NC}"
  tail -30 "${TEMP_DIR}/server-stdout.log" 2>/dev/null || true
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Health endpoint
# ---------------------------------------------------------------------------

log_header "Test 2: Health Endpoint"

HEALTH_RESP=$(curl -sf "${BASE_URL}/health" 2>/dev/null || echo "CURL_FAILED")
if echo "$HEALTH_RESP" | grep -q '"status":"ok"'; then
  check_pass "/health returns {\"status\":\"ok\"}"
else
  check_fail "/health endpoint" "Got: ${HEALTH_RESP}"
fi

# ---------------------------------------------------------------------------
# Step 4: Metrics endpoint
# ---------------------------------------------------------------------------

log_header "Test 3: Metrics Endpoint"

METRICS_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/metrics" 2>/dev/null || echo "000")
METRICS_BODY=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

if [ "$METRICS_STATUS" = "200" ]; then
  check_pass "/metrics returns HTTP 200"
else
  check_fail "/metrics HTTP status" "Expected 200, got: ${METRICS_STATUS}"
fi

if echo "$METRICS_BODY" | grep -q "jvm_memory"; then
  check_pass "/metrics contains JVM memory metrics"
else
  check_fail "/metrics JVM metrics" "jvm_memory not found in response"
fi

if echo "$METRICS_BODY" | grep -q "jvm_threads"; then
  check_pass "/metrics contains JVM thread metrics"
else
  check_fail "/metrics JVM thread metrics" "jvm_threads not found in response"
fi

# ---------------------------------------------------------------------------
# Step 5: HTTP request metrics
# ---------------------------------------------------------------------------

log_header "Test 4: HTTP Request Metrics"

# Generate some traffic
curl -sf "${BASE_URL}/" >/dev/null 2>&1 || true
curl -sf "${BASE_URL}/jobs" >/dev/null 2>&1 || true
curl -sf "${BASE_URL}/health" >/dev/null 2>&1 || true
sleep 1

# Check metrics updated
METRICS_AFTER=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

if echo "$METRICS_AFTER" | grep -q "http_request_duration_seconds"; then
  check_pass "HTTP request duration histogram present"
else
  check_fail "HTTP request duration histogram" "http_request_duration_seconds not found"
fi

if echo "$METRICS_AFTER" | grep -q "http_requests_total"; then
  check_pass "HTTP requests total counter present"
else
  check_fail "HTTP requests total counter" "http_requests_total not found"
fi

# Check method and path labels
if echo "$METRICS_AFTER" | grep -q 'method="get"'; then
  check_pass "HTTP metrics contain method label"
else
  check_fail "HTTP metrics method label" "method=\"get\" not found"
fi

# ---------------------------------------------------------------------------
# Step 6: Path normalization
# ---------------------------------------------------------------------------

log_header "Test 5: Path Normalization"

# Hit a UUID-like path
curl -sf "${BASE_URL}/builds/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee" >/dev/null 2>&1 || true
sleep 1

METRICS_NORM=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

if echo "$METRICS_NORM" | grep -q 'path="/builds/{id}"'; then
  check_pass "UUID path normalized to /builds/{id}"
else
  # Check if UUID appears literally (which would mean normalization failed)
  if echo "$METRICS_NORM" | grep -q "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"; then
    check_fail "Path normalization" "UUID appeared literally in metrics labels"
  else
    check_fail "Path normalization" "Could not verify /builds/{id} in metrics"
  fi
fi

# ---------------------------------------------------------------------------
# Step 7: Alerts API
# ---------------------------------------------------------------------------

log_header "Test 6: Alerts API"

ALERTS_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/api/alerts" 2>/dev/null || echo "000")
ALERTS_BODY=$(curl -sf "${BASE_URL}/api/alerts" 2>/dev/null || echo "")

if [ "$ALERTS_STATUS" = "200" ]; then
  check_pass "/api/alerts returns HTTP 200"
else
  check_fail "/api/alerts HTTP status" "Expected 200, got: ${ALERTS_STATUS}"
fi

if echo "$ALERTS_BODY" | grep -q '"alerts"'; then
  check_pass "/api/alerts returns JSON with 'alerts' key"
else
  check_fail "/api/alerts JSON structure" "Got: ${ALERTS_BODY}"
fi

# Alerts fragment (HTML)
FRAG_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/api/alerts/fragment" 2>/dev/null || echo "000")
FRAG_CONTENT_TYPE=$(curl -sf -o /dev/null -D - "${BASE_URL}/api/alerts/fragment" 2>/dev/null | grep -i "content-type" || echo "")

if [ "$FRAG_STATUS" = "200" ]; then
  check_pass "/api/alerts/fragment returns HTTP 200"
else
  check_fail "/api/alerts/fragment HTTP status" "Expected 200, got: ${FRAG_STATUS}"
fi

if echo "$FRAG_CONTENT_TYPE" | grep -qi "text/html"; then
  check_pass "/api/alerts/fragment returns text/html"
else
  check_fail "/api/alerts/fragment content type" "Expected text/html, got: ${FRAG_CONTENT_TYPE}"
fi

# ---------------------------------------------------------------------------
# Step 8: Build metrics (create + trigger build)
# ---------------------------------------------------------------------------

log_header "Test 7: Build Metrics"

log_info "Creating test job from pipelines/example.clj..."
JOB_OUTPUT=$(lein run -- job create pipelines/example.clj 2>&1 || echo "JOB_CREATE_FAILED")
log_info "Job create output: ${JOB_OUTPUT}"

log_info "Triggering build..."
BUILD_OUTPUT=$(lein run -- build trigger example 2>&1 || echo "BUILD_TRIGGER_FAILED")
log_info "Build trigger output: ${BUILD_OUTPUT}"

# Wait for build to complete (max 60s)
log_info "Waiting for build to complete..."
WAIT_SECS=0
while [ $WAIT_SECS -lt 60 ]; do
  BUILD_LIST=$(lein run -- build list example 2>&1 || echo "")
  if echo "$BUILD_LIST" | grep -q "success\|failure\|completed"; then
    break
  fi
  sleep 3
  WAIT_SECS=$((WAIT_SECS + 3))
done
log_info "Build completed after ~${WAIT_SECS}s"

# Check build metrics
sleep 2
METRICS_BUILD=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

if echo "$METRICS_BUILD" | grep -q "builds_total"; then
  check_pass "builds_total counter present after build"
else
  check_fail "builds_total counter" "Not found in /metrics after build"
fi

if echo "$METRICS_BUILD" | grep -q "builds_duration_seconds"; then
  check_pass "builds_duration_seconds histogram present after build"
else
  check_fail "builds_duration_seconds histogram" "Not found in /metrics after build"
fi

if echo "$METRICS_BUILD" | grep -q "stages_duration_seconds"; then
  check_pass "stages_duration_seconds histogram present (stage metrics)"
else
  check_fail "stages_duration_seconds histogram" "Not found in /metrics after build"
fi

if echo "$METRICS_BUILD" | grep -q "steps_duration_seconds"; then
  check_pass "steps_duration_seconds histogram present (step metrics)"
else
  check_fail "steps_duration_seconds histogram" "Not found in /metrics after build"
fi

if echo "$METRICS_BUILD" | grep -q "events_published_total"; then
  check_pass "events_published_total counter present"
else
  check_fail "events_published_total counter" "Not found in /metrics"
fi

# ---------------------------------------------------------------------------
# Step 9: Structured JSON logging
# ---------------------------------------------------------------------------

log_header "Test 8: Structured JSON Logging"

# Check server stdout for JSON-formatted log lines
if [ -f "${TEMP_DIR}/server-stdout.log" ]; then
  # JSON logs typically start with { and contain "timestamp" or "level"
  if grep -q '"timestamp"' "${TEMP_DIR}/server-stdout.log" 2>/dev/null || \
     grep -q '"level"' "${TEMP_DIR}/server-stdout.log" 2>/dev/null; then
    check_pass "Server stdout contains JSON-structured log lines"
  else
    # Check the log file if stdout didn't capture JSON
    if [ -f "${TEMP_LOG}" ] && grep -q '"timestamp"\|"level"' "${TEMP_LOG}" 2>/dev/null; then
      check_pass "Log file contains JSON-structured log lines"
    else
      # It's possible the JSON appender writes to stderr or a different format
      check_fail "Structured JSON logging" "No JSON-formatted log lines found (check server output format)"
    fi
  fi
else
  check_fail "Structured JSON logging" "Server stdout log not found"
fi

# ---------------------------------------------------------------------------
# Step 10: Metrics disabled (verify no-op behavior)
# ---------------------------------------------------------------------------

log_header "Test 9: Metrics Registry Labels"

FULL_METRICS=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

# Auth metrics should be registered even if not exercised
if echo "$FULL_METRICS" | grep -q "auth_login_total\|auth_token_auth_total"; then
  check_pass "Auth metric counters registered"
else
  # These may show 0 with no labels if never incremented — that's OK for iapetos
  log_info "(Auth metrics may not appear until first login/token auth — skipping)"
fi

# Verify Prometheus text format headers
METRICS_HEADERS=$(curl -sf -D - -o /dev/null "${BASE_URL}/metrics" 2>/dev/null || echo "")
if echo "$METRICS_HEADERS" | grep -qi "text/plain"; then
  check_pass "/metrics Content-Type is text/plain (Prometheus format)"
else
  check_fail "/metrics Content-Type" "Expected text/plain"
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                       TEST SUMMARY                         ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}  ${GREEN}Passed: ${PASS_COUNT}${NC}"
echo -e "${BOLD}║${NC}  ${RED}Failed: ${FAIL_COUNT}${NC}"
echo -e "${BOLD}║${NC}  Total:  ${TOTAL_COUNT}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

if [ "$FAIL_COUNT" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}All tests passed!${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}${FAIL_COUNT} test(s) failed.${NC}"
  exit 1
fi
