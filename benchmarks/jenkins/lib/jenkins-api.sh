#!/usr/bin/env bash
# Shared Jenkins REST API helper functions for benchmarks.

JENKINS_URL="${JENKINS_URL:-http://localhost:8081}"
JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_TOKEN="${JENKINS_TOKEN:-}"

jenkins_crumb() {
  curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
    "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null | jq -r '.crumb // empty'
}

jenkins_trigger_build() {
  local job_name="$1"
  local crumb
  crumb=$(jenkins_crumb)

  local crumb_header=""
  if [ -n "$crumb" ]; then
    crumb_header="-H Jenkins-Crumb:$crumb"
  fi

  # Trigger build and capture queue URL from Location header
  curl -s -I -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" \
    $crumb_header \
    "$JENKINS_URL/job/$job_name/build" 2>/dev/null \
    | grep -i "Location:" | awk '{print $2}' | tr -d '\r\n'
}

jenkins_get_build_number_from_queue() {
  local queue_url="$1"
  local timeout="${2:-60}"
  local elapsed=0

  while [ "$elapsed" -lt "$timeout" ]; do
    local build_num
    build_num=$(curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
      "${queue_url}api/json" 2>/dev/null | jq -r '.executable.number // empty')
    if [ -n "$build_num" ] && [ "$build_num" != "null" ]; then
      echo "$build_num"
      return 0
    fi
    sleep 0.5
    elapsed=$((elapsed + 1))
  done
  echo ""
  return 1
}

jenkins_wait_for_build() {
  local job_name="$1"
  local build_number="$2"
  local timeout="${3:-300}"
  local start_ns
  start_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')

  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    local result
    result=$(curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
      "$JENKINS_URL/job/$job_name/$build_number/api/json" 2>/dev/null \
      | jq -r '.result // "RUNNING"')

    if [ "$result" != "RUNNING" ] && [ "$result" != "null" ]; then
      local end_ns
      end_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time; print(int(time.time()*1e9))')
      local duration_ms=$(( (end_ns - start_ns) / 1000000 ))
      echo "$duration_ms"
      return 0
    fi
    sleep 0.2
    elapsed=$((elapsed + 1))
  done
  echo "-1"
  return 1
}

jenkins_get_build_duration() {
  local job_name="$1"
  local build_number="$2"
  curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
    "$JENKINS_URL/job/$job_name/$build_number/api/json" 2>/dev/null \
    | jq '.duration'
}

jenkins_get_build_result() {
  local job_name="$1"
  local build_number="$2"
  curl -s -u "$JENKINS_USER:$JENKINS_TOKEN" \
    "$JENKINS_URL/job/$job_name/$build_number/api/json" 2>/dev/null \
    | jq -r '.result'
}

# Trigger and wait for a single build. Returns: wall_ms,jenkins_ms,status
jenkins_run_build() {
  local job_name="$1"

  local queue_url
  queue_url=$(jenkins_trigger_build "$job_name")

  if [ -z "$queue_url" ]; then
    echo "-1,-1,ERROR"
    return 1
  fi

  local build_num
  build_num=$(jenkins_get_build_number_from_queue "$queue_url" 60)

  if [ -z "$build_num" ]; then
    echo "-1,-1,QUEUE_TIMEOUT"
    return 1
  fi

  local wall_ms
  wall_ms=$(jenkins_wait_for_build "$job_name" "$build_num" 300)

  local jenkins_ms
  jenkins_ms=$(jenkins_get_build_duration "$job_name" "$build_num")

  local status
  status=$(jenkins_get_build_result "$job_name" "$build_num")

  echo "$wall_ms,$jenkins_ms,$status"
}
