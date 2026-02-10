-- Migration 040: Trace spans for distributed tracing
CREATE TABLE IF NOT EXISTS trace_spans (
  id TEXT PRIMARY KEY,
  trace_id TEXT NOT NULL,
  span_id TEXT NOT NULL,
  parent_span_id TEXT,
  service_name TEXT NOT NULL DEFAULT 'chengis-master',
  operation TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT 'INTERNAL',
  status TEXT NOT NULL DEFAULT 'OK',
  started_at TEXT NOT NULL,
  ended_at TEXT,
  duration_ms INTEGER,
  attributes TEXT,
  build_id TEXT,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trace_spans_trace_id ON trace_spans(trace_id);
CREATE INDEX IF NOT EXISTS idx_trace_spans_build_id ON trace_spans(build_id);
CREATE INDEX IF NOT EXISTS idx_trace_spans_created_at ON trace_spans(created_at);
CREATE INDEX IF NOT EXISTS idx_trace_spans_org_id ON trace_spans(org_id);
