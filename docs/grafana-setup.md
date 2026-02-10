# Grafana Dashboard Setup

Chengis ships with pre-built Grafana dashboards for monitoring build performance, agent health, and security metrics.

## Prerequisites

- Prometheus scraping the Chengis `/metrics` endpoint
- Grafana 9.x+ with Prometheus datasource

## Quick Start with Docker Compose

Add Prometheus and Grafana to your Docker Compose stack:

```yaml
services:
  prometheus:
    image: prom/prometheus:latest
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    restart: unless-stopped

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
    volumes:
      - ./resources/grafana/provisioning:/etc/grafana/provisioning
      - ./resources/grafana/dashboards:/var/lib/grafana/dashboards/chengis
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    restart: unless-stopped
```

Create `prometheus.yml`:

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: chengis
    metrics_path: /metrics
    static_configs:
      - targets: ['master:8080']
```

## Dashboards

### Build Overview (`chengis-overview`)
- Active builds gauge
- Build success rate (1h rolling)
- Queue depth and oldest pending build
- Build completion rate by status (success/failure/aborted)
- Build duration heatmap
- HTTP request rate and latency percentiles (p50/p90/p99)
- Stage duration (p90) by stage name
- Dispatch queue depth over time

### Agent Health (`chengis-agents`)
- Agent utilization gauge and time series
- Circuit breaker state
- Orphaned build recovery count
- Dispatch attempt results (success/failure/no-agent/retry)
- Artifact transfer success/failure rate

### Security & Compliance (`chengis-security`)
- Login attempts (success/failure)
- Account lockout events
- Rate-limited request count
- Policy evaluation results (allow/deny)
- Secret access events by action
- SCM status report results
- Hash chain integrity verifications

## Configuration

Ensure metrics are enabled in Chengis:

```bash
export CHENGIS_METRICS_ENABLED=true
export CHENGIS_METRICS_PATH=/metrics  # default
```

## Alerting

Suggested Grafana alert rules:

| Alert | Condition | Severity |
|-------|-----------|----------|
| High failure rate | Success rate < 80% for 15m | Warning |
| Queue backup | Queue depth > 20 for 10m | Warning |
| Agent down | Circuit breakers open > 0 for 5m | Critical |
| Auth brute force | Login failures > 50/h | Critical |
| Policy violations | Policy denials > 10/h | Warning |
