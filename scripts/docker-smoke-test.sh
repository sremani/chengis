#!/usr/bin/env bash
# =============================================================================
# Chengis — Docker Smoke Test Script
# =============================================================================
#
# Builds (or uses a pre-built) Docker image, starts a container, and validates
# that the application starts correctly and health endpoints respond.
#
# Usage:
#   ./scripts/docker-smoke-test.sh                    # Build and test
#   ./scripts/docker-smoke-test.sh --image chengis:ci  # Test pre-built image
#   ./scripts/docker-smoke-test.sh --skip-build        # Skip build, use chengis:smoke-test
#
# Requirements: docker
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

IMAGE_NAME="chengis:smoke-test"
CONTAINER_NAME="chengis-smoke-$$"
HOST_PORT=8090
MAX_WAIT=90  # seconds to wait for healthy
SKIP_BUILD=false

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
    --image)      IMAGE_NAME="$2"; SKIP_BUILD=true; shift 2 ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --port)       HOST_PORT="$2"; shift 2 ;;
    --timeout)    MAX_WAIT="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: $0 [OPTIONS]"
      echo ""
      echo "Options:"
      echo "  --image NAME    Use pre-built image (skip build)"
      echo "  --skip-build    Skip Docker build step"
      echo "  --port PORT     Host port to bind (default: 8090)"
      echo "  --timeout SECS  Max seconds to wait for healthy (default: 90)"
      exit 0
      ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL="http://localhost:${HOST_PORT}"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

check_pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo -e "  ${GREEN}✓ PASS${NC}  $1"
}

check_fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo -e "  ${RED}✗ FAIL${NC}  $1"
  if [ -n "${2:-}" ]; then
    echo -e "         ${RED}→ $2${NC}"
  fi
}

cleanup() {
  echo ""
  echo -e "${CYAN}${BOLD}━━━ Cleanup ━━━${NC}"

  if docker ps -q --filter "name=${CONTAINER_NAME}" 2>/dev/null | grep -q .; then
    echo -e "  Stopping container ${CONTAINER_NAME}..."
    docker stop "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  fi

  if docker ps -aq --filter "name=${CONTAINER_NAME}" 2>/dev/null | grep -q .; then
    echo -e "  Removing container ${CONTAINER_NAME}..."
    docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true
  fi

  echo -e "  ${GREEN}Cleanup complete${NC}"
}

trap cleanup EXIT

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

echo ""
echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║          Chengis — Docker Smoke Test                        ║${NC}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ---------------------------------------------------------------------------
# Step 1: Build Docker image
# ---------------------------------------------------------------------------

echo -e "${CYAN}${BOLD}━━━ Docker Build ━━━${NC}"

if [ "$SKIP_BUILD" = true ]; then
  echo -e "  Skipping build, using image: ${CYAN}${IMAGE_NAME}${NC}"
  # Verify image exists
  if ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
    echo -e "  ${RED}Image ${IMAGE_NAME} not found${NC}"
    exit 1
  fi
  check_pass "Image exists: ${IMAGE_NAME}"
else
  echo -e "  Building image: ${CYAN}${IMAGE_NAME}${NC}"
  echo -e "  Context: ${PROJECT_ROOT}"
  echo ""

  BUILD_START=$(date +%s)
  if docker build -t "${IMAGE_NAME}" "${PROJECT_ROOT}" 2>&1; then
    BUILD_END=$(date +%s)
    BUILD_DURATION=$((BUILD_END - BUILD_START))
    check_pass "Docker build succeeded (${BUILD_DURATION}s)"
  else
    check_fail "Docker build failed"
    exit 1
  fi
fi
echo ""

# ---------------------------------------------------------------------------
# Step 2: Start container
# ---------------------------------------------------------------------------

echo -e "${CYAN}${BOLD}━━━ Start Container ━━━${NC}"

docker run -d \
  --name "${CONTAINER_NAME}" \
  -p "${HOST_PORT}:8080" \
  -e CHENGIS_AUTH_ENABLED=false \
  -e CHENGIS_METRICS_ENABLED=true \
  -e CHENGIS_AUDIT_ENABLED=false \
  -e CHENGIS_LOG_LEVEL=":info" \
  "${IMAGE_NAME}" >/dev/null

echo -e "  Container: ${CYAN}${CONTAINER_NAME}${NC}"
echo -e "  Port:      ${CYAN}${HOST_PORT}→8080${NC}"
echo -e "  Image:     ${CYAN}${IMAGE_NAME}${NC}"
echo ""

# ---------------------------------------------------------------------------
# Step 3: Wait for healthy
# ---------------------------------------------------------------------------

echo -e "${CYAN}${BOLD}━━━ Health Check ━━━${NC}"
echo -e "  Waiting for ${BASE_URL}/health (max ${MAX_WAIT}s)..."

ELAPSED=0
HEALTHY=false
while [ $ELAPSED -lt $MAX_WAIT ]; do
  if curl -sf "${BASE_URL}/health" >/dev/null 2>&1; then
    HEALTHY=true
    break
  fi

  # Check if container is still running
  if ! docker ps -q --filter "name=${CONTAINER_NAME}" 2>/dev/null | grep -q .; then
    echo -e "  ${RED}Container exited unexpectedly!${NC}"
    echo -e "  ${YELLOW}Container logs:${NC}"
    docker logs "${CONTAINER_NAME}" 2>&1 | tail -30
    check_fail "Container stayed running"
    exit 1
  fi

  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

if [ "$HEALTHY" = true ]; then
  check_pass "Server healthy after ${ELAPSED}s"
else
  echo -e "  ${RED}Server failed to become healthy within ${MAX_WAIT}s${NC}"
  echo -e "  ${YELLOW}Container logs:${NC}"
  docker logs "${CONTAINER_NAME}" 2>&1 | tail -40
  check_fail "Server became healthy within ${MAX_WAIT}s"
  exit 1
fi
echo ""

# ---------------------------------------------------------------------------
# Step 4: Validate endpoints
# ---------------------------------------------------------------------------

echo -e "${CYAN}${BOLD}━━━ Endpoint Validation ━━━${NC}"

# /health
HEALTH_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/health" 2>/dev/null || echo "000")
HEALTH_BODY=$(curl -sf "${BASE_URL}/health" 2>/dev/null || echo "")

if [ "$HEALTH_STATUS" = "200" ]; then
  check_pass "/health → HTTP 200"
else
  check_fail "/health HTTP status" "Expected 200, got: ${HEALTH_STATUS}"
fi

if echo "$HEALTH_BODY" | grep -q '"status"'; then
  check_pass "/health returns JSON with status field"
else
  check_fail "/health JSON body" "Got: ${HEALTH_BODY}"
fi

# /ready
READY_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/ready" 2>/dev/null || echo "000")

if [ "$READY_STATUS" = "200" ]; then
  check_pass "/ready → HTTP 200"
else
  check_fail "/ready HTTP status" "Expected 200, got: ${READY_STATUS}"
fi

# /startup
STARTUP_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/startup" 2>/dev/null || echo "000")

if [ "$STARTUP_STATUS" = "200" ]; then
  check_pass "/startup → HTTP 200"
else
  check_fail "/startup HTTP status" "Expected 200, got: ${STARTUP_STATUS}"
fi

# /metrics
METRICS_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/metrics" 2>/dev/null || echo "000")
METRICS_BODY=$(curl -sf "${BASE_URL}/metrics" 2>/dev/null || echo "")

if [ "$METRICS_STATUS" = "200" ]; then
  check_pass "/metrics → HTTP 200"
else
  check_fail "/metrics HTTP status" "Expected 200, got: ${METRICS_STATUS}"
fi

if echo "$METRICS_BODY" | grep -q "jvm_memory"; then
  check_pass "/metrics contains JVM metrics"
else
  check_fail "/metrics JVM metrics" "jvm_memory not found"
fi

# /login (should return HTML page)
LOGIN_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}/login" 2>/dev/null || echo "000")

if [ "$LOGIN_STATUS" = "200" ]; then
  check_pass "/login → HTTP 200"
else
  check_fail "/login HTTP status" "Expected 200, got: ${LOGIN_STATUS}"
fi

echo ""

# ---------------------------------------------------------------------------
# Step 5: Container info
# ---------------------------------------------------------------------------

echo -e "${CYAN}${BOLD}━━━ Container Info ━━━${NC}"

# Show Java version inside container
JAVA_VERSION=$(docker exec "${CONTAINER_NAME}" java -version 2>&1 | head -1 || echo "unknown")
echo -e "  Java version: ${CYAN}${JAVA_VERSION}${NC}"

# Show container resource usage
CONTAINER_STATS=$(docker stats "${CONTAINER_NAME}" --no-stream --format "CPU: {{.CPUPerc}}, MEM: {{.MemUsage}}" 2>/dev/null || echo "unavailable")
echo -e "  Resources:    ${CYAN}${CONTAINER_STATS}${NC}"

echo ""

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

TOTAL=$((PASS_COUNT + FAIL_COUNT))

echo -e "${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                  SMOKE TEST SUMMARY                         ║${NC}"
echo -e "${BOLD}╠══════════════════════════════════════════════════════════════╣${NC}"
echo -e "${BOLD}║${NC}  ${GREEN}Passed: ${PASS_COUNT}${NC}"
echo -e "${BOLD}║${NC}  ${RED}Failed: ${FAIL_COUNT}${NC}"
echo -e "${BOLD}║${NC}  Total:  ${TOTAL}"
echo -e "${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

if [ "$FAIL_COUNT" -eq 0 ]; then
  echo -e "${GREEN}${BOLD}All smoke tests passed!${NC}"
  exit 0
else
  echo -e "${RED}${BOLD}${FAIL_COUNT} smoke test(s) failed.${NC}"
  exit 1
fi
