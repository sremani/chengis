-- Cron schedules: time-based triggers for jobs
CREATE TABLE IF NOT EXISTS cron_schedules (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  cron_expression TEXT NOT NULL,
  description TEXT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  timezone TEXT NOT NULL DEFAULT 'UTC',
  parameters TEXT,
  last_run_at TIMESTAMP WITH TIME ZONE,
  next_run_at TIMESTAMP WITH TIME ZONE,
  last_status TEXT,
  run_count BIGINT NOT NULL DEFAULT 0,
  miss_count BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
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

-- Cron run history: log of each scheduled execution attempt
CREATE TABLE IF NOT EXISTS cron_run_history (
  id TEXT PRIMARY KEY,
  schedule_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  build_id TEXT,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
  triggered_at TIMESTAMP WITH TIME ZONE,
  status TEXT NOT NULL DEFAULT 'pending',
  missed BOOLEAN NOT NULL DEFAULT FALSE,
  error TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_history_schedule
  ON cron_run_history(schedule_id);

--;;

CREATE INDEX IF NOT EXISTS idx_cron_history_job
  ON cron_run_history(job_id);
