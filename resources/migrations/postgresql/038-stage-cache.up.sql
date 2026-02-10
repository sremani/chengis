CREATE TABLE stage_cache (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  fingerprint TEXT NOT NULL,
  stage_name TEXT NOT NULL,
  stage_result TEXT NOT NULL,
  git_commit TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  org_id TEXT DEFAULT 'default-org',
  UNIQUE(job_id, fingerprint)
);
--;;
CREATE INDEX idx_stage_cache_lookup ON stage_cache(job_id, fingerprint);
