-- SQLite doesn't support DROP COLUMN; recreate table without sha256_hash
CREATE TABLE build_artifacts_backup AS SELECT id, build_id, filename, path, size_bytes, content_type, created_at FROM build_artifacts;
--;;
DROP TABLE build_artifacts;
--;;
CREATE TABLE build_artifacts (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  filename TEXT NOT NULL,
  path TEXT NOT NULL,
  size_bytes INTEGER,
  content_type TEXT,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP
);
--;;
INSERT INTO build_artifacts SELECT * FROM build_artifacts_backup;
--;;
DROP TABLE build_artifacts_backup;
--;;
CREATE INDEX idx_artifacts_build ON build_artifacts(build_id);
