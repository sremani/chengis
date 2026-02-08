CREATE TABLE IF NOT EXISTS build_artifacts (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  filename TEXT NOT NULL,
  path TEXT NOT NULL,
  size_bytes INTEGER,
  content_type TEXT,
  created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_artifacts_build ON build_artifacts(build_id);
