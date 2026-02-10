-- Build cost attribution: track agent-hours per build
CREATE TABLE IF NOT EXISTS build_cost_entries (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  agent_id TEXT,
  started_at TEXT NOT NULL,
  ended_at TEXT,
  duration_s REAL,
  cost_per_hour REAL NOT NULL DEFAULT 1.0,
  computed_cost REAL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_build_cost_entries_org_id
  ON build_cost_entries(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_build_cost_entries_job_id
  ON build_cost_entries(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_build_cost_entries_build_id
  ON build_cost_entries(build_id);

--;;

CREATE INDEX IF NOT EXISTS idx_build_cost_entries_created_at
  ON build_cost_entries(created_at);
