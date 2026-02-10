-- Build analytics: precomputed daily/weekly build statistics
CREATE TABLE IF NOT EXISTS build_analytics (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  job_id TEXT,
  period_type TEXT NOT NULL,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  total_builds INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  aborted_count INTEGER NOT NULL DEFAULT 0,
  success_rate REAL,
  avg_duration_s REAL,
  p50_duration_s REAL,
  p90_duration_s REAL,
  p99_duration_s REAL,
  max_duration_s REAL,
  computed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_build_analytics_org_period
  ON build_analytics(org_id, period_type, period_start);

--;;

CREATE INDEX IF NOT EXISTS idx_build_analytics_job_period
  ON build_analytics(job_id, period_type, period_start);

--;;

-- Stage analytics: precomputed stage-level statistics with flakiness scores
CREATE TABLE IF NOT EXISTS stage_analytics (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  job_id TEXT,
  stage_name TEXT NOT NULL,
  period_type TEXT NOT NULL,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  total_runs INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  avg_duration_s REAL,
  p90_duration_s REAL,
  max_duration_s REAL,
  flakiness_score REAL,
  computed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_stage_analytics_org_period
  ON stage_analytics(org_id, period_type, period_start);

--;;

CREATE INDEX IF NOT EXISTS idx_stage_analytics_job_period
  ON stage_analytics(job_id, stage_name, period_type, period_start);
