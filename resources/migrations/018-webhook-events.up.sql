CREATE TABLE IF NOT EXISTS webhook_events (
  id TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  event_type TEXT,
  repo_url TEXT,
  repo_name TEXT,
  branch TEXT,
  commit_sha TEXT,
  signature_valid INTEGER NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'processed',
  matched_jobs INTEGER NOT NULL DEFAULT 0,
  triggered_builds INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  payload_size INTEGER,
  processing_ms INTEGER,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_webhook_events_created ON webhook_events(created_at);
CREATE INDEX idx_webhook_events_provider ON webhook_events(provider, created_at);
CREATE INDEX idx_webhook_events_status ON webhook_events(status);
