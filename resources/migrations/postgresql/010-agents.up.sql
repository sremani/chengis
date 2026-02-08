-- Distributed builds: agent registry
CREATE TABLE IF NOT EXISTS agents (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  url TEXT NOT NULL,
  labels TEXT,  -- JSON array of labels
  status TEXT NOT NULL DEFAULT 'online',
  max_builds INTEGER NOT NULL DEFAULT 2,
  system_info TEXT,  -- JSON object
  last_heartbeat TIMESTAMPTZ,
  registered_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Track which agent executed each build
ALTER TABLE builds ADD COLUMN agent_id TEXT;
ALTER TABLE builds ADD COLUMN dispatched_at TIMESTAMPTZ;
