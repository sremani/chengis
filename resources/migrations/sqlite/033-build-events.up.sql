-- Durable build events: persist events to DB so they survive restarts.
-- Enables event replay for SSE reconnection.

CREATE TABLE IF NOT EXISTS build_events (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  stage_name TEXT,
  step_name TEXT,
  data TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_build_events_build ON build_events(build_id);
--;;
CREATE INDEX idx_build_events_type ON build_events(event_type);
