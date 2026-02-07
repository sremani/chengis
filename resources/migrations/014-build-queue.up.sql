-- Persistent build queue for distributed dispatch.
-- Stores builds awaiting dispatch to agents, with retry tracking.
CREATE TABLE IF NOT EXISTS build_queue (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  payload TEXT NOT NULL,
  labels TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  agent_id TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  max_retries INTEGER NOT NULL DEFAULT 3,
  error TEXT,
  next_retry_at TEXT,
  enqueued_at TEXT NOT NULL,
  dispatched_at TEXT,
  completed_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_build_queue_status ON build_queue(status);
CREATE INDEX idx_build_queue_agent ON build_queue(agent_id, status);
CREATE INDEX idx_build_queue_next_retry ON build_queue(status, next_retry_at);
CREATE INDEX idx_build_queue_build_id ON build_queue(build_id);
