-- Test results: individual test outcomes per build
CREATE TABLE IF NOT EXISTS test_results (
  id TEXT PRIMARY KEY,
  build_id TEXT,
  job_id TEXT,
  org_id TEXT DEFAULT 'default-org',
  stage_name TEXT,
  step_name TEXT,
  test_name TEXT NOT NULL,
  test_suite TEXT,
  status TEXT NOT NULL,
  duration_ms BIGINT,
  error_msg TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_test_results_build_id
  ON test_results(build_id);

--;;

CREATE INDEX IF NOT EXISTS idx_test_results_job_test
  ON test_results(job_id, test_name);

--;;

CREATE INDEX IF NOT EXISTS idx_test_results_org_id
  ON test_results(org_id);

--;;

-- Flaky tests: aggregated flakiness scores
CREATE TABLE IF NOT EXISTS flaky_tests (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  job_id TEXT NOT NULL,
  test_name TEXT NOT NULL,
  test_suite TEXT,
  total_runs INTEGER,
  pass_count INTEGER,
  fail_count INTEGER,
  flakiness_score DOUBLE PRECISION,
  last_seen_at TIMESTAMP,
  first_flaky_at TIMESTAMP,
  computed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, job_id, test_name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_flaky_tests_org_score
  ON flaky_tests(org_id, flakiness_score DESC);
