-- Build analytics: precomputed daily/weekly build statistics
CREATE TABLE IF NOT EXISTS build_analytics (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  job_id TEXT,
  period_type TEXT NOT NULL,
  period_start TIMESTAMP NOT NULL,
  period_end TIMESTAMP NOT NULL,
  total_builds INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  aborted_count INTEGER NOT NULL DEFAULT 0,
  success_rate DOUBLE PRECISION,
  avg_duration_s DOUBLE PRECISION,
  p50_duration_s DOUBLE PRECISION,
  p90_duration_s DOUBLE PRECISION,
  p99_duration_s DOUBLE PRECISION,
  max_duration_s DOUBLE PRECISION,
  computed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  period_start TIMESTAMP NOT NULL,
  period_end TIMESTAMP NOT NULL,
  total_runs INTEGER NOT NULL DEFAULT 0,
  success_count INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  avg_duration_s DOUBLE PRECISION,
  p90_duration_s DOUBLE PRECISION,
  max_duration_s DOUBLE PRECISION,
  flakiness_score DOUBLE PRECISION,
  computed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_stage_analytics_org_period
  ON stage_analytics(org_id, period_type, period_start);

--;;

CREATE INDEX IF NOT EXISTS idx_stage_analytics_job_period
  ON stage_analytics(job_id, stage_name, period_type, period_start);
