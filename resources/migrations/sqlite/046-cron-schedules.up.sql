-- Cron schedules: persistent database-backed cron jobs
CREATE TABLE IF NOT EXISTS cron_schedules (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  cron_expression TEXT NOT NULL,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  timezone TEXT NOT NULL DEFAULT 'UTC',
  parameters TEXT,
  last_run_at TEXT,
  next_run_at TEXT,
  last_status TEXT,
  run_count INTEGER NOT NULL DEFAULT 0,
  miss_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(job_id, cron_expression)
);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_schedules_job_id
  ON cron_schedules(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_schedules_org_id
  ON cron_schedules(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_schedules_next_run
  ON cron_schedules(enabled, next_run_at);

--;;

-- Cron run history: track each cron-triggered build
CREATE TABLE IF NOT EXISTS cron_run_history (
  id TEXT PRIMARY KEY,
  schedule_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  build_id TEXT,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  scheduled_at TEXT NOT NULL,
  triggered_at TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  missed INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_history_schedule
  ON cron_run_history(schedule_id);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_history_job
  ON cron_run_history(job_id);
