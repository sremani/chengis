#!/usr/bin/env bash
# Setup Jenkins for benchmarking.
# Waits for Jenkins, installs plugins, creates benchmark jobs.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JENKINS_URL="${JENKINS_URL:-http://localhost:8081}"
CONTAINER_NAME="${CONTAINER_NAME:-benchmarks-jenkins-1}"

echo "=== Jenkins Benchmark Setup ==="

# Wait for Jenkins to be ready
echo "Waiting for Jenkins to start..."
for i in $(seq 1 120); do
  if curl -s -o /dev/null -w "%{http_code}" "$JENKINS_URL/login" 2>/dev/null | grep -q "200"; then
    echo "Jenkins is ready."
    break
  fi
  if [ "$i" -eq 120 ]; then
    echo "ERROR: Jenkins did not start within 120 seconds."
    exit 1
  fi
  sleep 1
done

# Get initial admin password
echo "Retrieving initial admin password..."
ADMIN_PASS=$(docker exec "$CONTAINER_NAME" cat /var/jenkins_home/secrets/initialAdminPassword 2>/dev/null || true)
if [ -z "$ADMIN_PASS" ]; then
  echo "Could not retrieve admin password. Jenkins may already be configured."
  echo "Set JENKINS_USER and JENKINS_TOKEN environment variables."
  echo "You can also skip setup with: export JENKINS_SETUP_SKIP=1"
  exit 1
fi

JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_TOKEN="${JENKINS_TOKEN:-$ADMIN_PASS}"

echo "Admin password: $ADMIN_PASS"
echo ""
echo "IMPORTANT: Complete initial Jenkins setup manually at $JENKINS_URL"
echo "  1. Enter the admin password above"
echo "  2. Install suggested plugins (or at minimum: Pipeline)"
echo "  3. Create admin user or continue as admin"
echo "  4. Generate an API token at $JENKINS_URL/user/admin/configure"
echo "  5. Set environment variables:"
echo "     export JENKINS_USER=admin"
echo "     export JENKINS_TOKEN=<your-api-token>"
echo ""
echo "Then re-run this script to create benchmark jobs."
echo ""

# If token is set, create jobs
if [ -n "${JENKINS_TOKEN:-}" ] && [ "$JENKINS_TOKEN" != "$ADMIN_PASS" ]; then
  echo "Creating benchmark jobs..."

  # Create job config XML from Groovy pipeline
  create_pipeline_job() {
    local job_name="$1"
    local pipeline_file="$2"
    local pipeline_script
    pipeline_script=$(cat "$pipeline_file")

    local config_xml
    config_xml=$(cat <<XMLEOF
<?xml version='1.1' encoding='UTF-8'?>
<flow-definition plugin="workflow-job">
  <definition class="org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition" plugin="workflow-cps">
    <script><![CDATA[$pipeline_script]]></script>
    <sandbox>true</sandbox>
  </definition>
</flow-definition>
XMLEOF
)

    # Check if job exists
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" -u "$JENKINS_USER:$JENKINS_TOKEN" \
      "$JENKINS_URL/job/$job_name/api/json")

    if [ "$status" = "200" ]; then
      echo "  Job '$job_name' already exists, updating..."
      curl -s -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" \
        -H "Content-Type: application/xml" \
        --data-binary "$config_xml" \
        "$JENKINS_URL/job/$job_name/config.xml"
    else
      echo "  Creating job '$job_name'..."
      curl -s -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" \
        -H "Content-Type: application/xml" \
        --data-binary "$config_xml" \
        "$JENKINS_URL/createItem?name=$job_name"
    fi
  }

  create_pipeline_job "bench-echo-overhead" "$SCRIPT_DIR/pipelines/echo-overhead.groovy"
  create_pipeline_job "bench-realistic" "$SCRIPT_DIR/pipelines/realistic-build.groovy"
  create_pipeline_job "bench-throughput" "$SCRIPT_DIR/pipelines/throughput-echo.groovy"

  echo "Done! Benchmark jobs created."
fi
