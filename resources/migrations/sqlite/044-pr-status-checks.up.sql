-- PR/MR status checks: track required checks per job and their results
CREATE TABLE IF NOT EXISTS pr_status_checks (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  check_name TEXT NOT NULL,
  description TEXT,
  required INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(job_id, check_name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_pr_status_checks_job_id
  ON pr_status_checks(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_pr_status_checks_org_id
  ON pr_status_checks(org_id);

--;;

-- PR check results: per-build check status for PR enforcement
CREATE TABLE IF NOT EXISTS pr_check_results (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  check_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  target_url TEXT,
  description TEXT,
  commit_sha TEXT,
  pr_number INTEGER,
  repo_url TEXT,
  started_at TEXT,
  completed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id, check_name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_pr_check_results_build_id
  ON pr_check_results(build_id);

--;;

CREATE INDEX IF NOT EXISTS idx_pr_check_results_commit
  ON pr_check_results(commit_sha, check_name);

--;;

CREATE INDEX IF NOT EXISTS idx_pr_check_results_org_id
  ON pr_check_results(org_id);
