CREATE TABLE cache_entries (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  cache_key TEXT NOT NULL,
  paths TEXT NOT NULL,
  size_bytes BIGINT NOT NULL DEFAULT 0,
  hit_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_hit_at TIMESTAMP,
  org_id TEXT DEFAULT 'default-org',
  UNIQUE(job_id, cache_key)
);
--;;
CREATE INDEX idx_cache_entries_job ON cache_entries(job_id);
--;;
CREATE INDEX idx_cache_entries_key_prefix ON cache_entries(job_id, cache_key);
