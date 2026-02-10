# Log Aggregation Guide

Chengis supports structured JSON logging with correlation context for integration with centralized log management systems.

## Enable JSON Logging

```bash
export CHENGIS_LOG_FORMAT=":json"
export CHENGIS_LOG_LEVEL=":info"
```

Or in `config.edn`:
```edn
{:log {:format :json :level :info}}
```

## Correlation Context

When JSON logging is enabled, every log entry within a build execution includes:

| Key | Scope | Description |
|-----|-------|-------------|
| `build-id` | Build | Unique build identifier |
| `job-id` | Build | Job that triggered the build |
| `org-id` | Build | Organization context |
| `stage-name` | Stage | Current pipeline stage |
| `step-name` | Step | Current pipeline step |
| `trace-id` | Trace | Distributed trace ID (when tracing enabled) |
| `span-id` | Trace | Current span ID (when tracing enabled) |

Example JSON log entry:
```json
{
  "timestamp": "2024-01-15T10:30:45.123Z",
  "level": "INFO",
  "msg": "Running step: compile",
  "build-id": "abc123",
  "job-id": "my-pipeline",
  "org-id": "acme-corp",
  "stage-name": "Build",
  "step-name": "compile"
}
```

## ELK Stack (Elasticsearch + Logstash + Kibana)

### Logstash Pipeline

```conf
input {
  file {
    path => "/var/log/chengis/*.log"
    codec => json
  }
}

filter {
  date {
    match => ["timestamp", "ISO8601"]
  }
  mutate {
    rename => {
      "build-id" => "build_id"
      "job-id" => "job_id"
      "org-id" => "org_id"
      "stage-name" => "stage_name"
      "step-name" => "step_name"
      "trace-id" => "trace_id"
      "span-id" => "span_id"
    }
  }
}

output {
  elasticsearch {
    hosts => ["elasticsearch:9200"]
    index => "chengis-logs-%{+YYYY.MM.dd}"
  }
}
```

### Kibana Queries

- All logs for a build: `build_id: "abc123"`
- Failed steps: `level: "ERROR" AND step_name: *`
- Stage-level view: `build_id: "abc123" AND stage_name: "Build"`

## Grafana Loki

### Promtail Config

```yaml
server:
  http_listen_port: 9080

positions:
  filename: /tmp/positions.yaml

clients:
  - url: http://loki:3100/loki/api/v1/push

scrape_configs:
  - job_name: chengis
    static_configs:
      - targets: [localhost]
        labels:
          job: chengis
          __path__: /var/log/chengis/*.log
    pipeline_stages:
      - json:
          expressions:
            level: level
            build_id: build-id
            job_id: job-id
            org_id: org-id
            stage_name: stage-name
            step_name: step-name
      - labels:
          level:
          build_id:
          job_id:
          org_id:
```

### LogQL Queries

- All logs for a build: `{job="chengis"} | json | build_id="abc123"`
- Errors only: `{job="chengis"} | json | level="ERROR"`
- Specific stage: `{job="chengis"} | json | build_id="abc123" | stage_name="Build"`

## Datadog

### Log Pipeline

1. Create a pipeline for source `chengis`
2. Add a JSON parser processor
3. Map attributes:
   - `build-id` → `build_id` (facet)
   - `job-id` → `job_id` (facet)
   - `org-id` → `org_id` (facet)
   - `stage-name` → `stage_name` (facet)
   - `step-name` → `step_name` (facet)

### Log Queries

- Build logs: `source:chengis @build_id:abc123`
- Failed builds: `source:chengis @level:ERROR`
- Organization view: `source:chengis @org_id:acme-corp`

## Docker Compose Example

```yaml
services:
  master:
    environment:
      CHENGIS_LOG_FORMAT: ":json"
    logging:
      driver: json-file
      options:
        max-size: "50m"
        max-file: "5"
```

For Docker-based deployments, use the Docker logging driver to forward JSON logs to your aggregation platform.
