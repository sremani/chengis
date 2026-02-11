CREATE INDEX IF NOT EXISTS idx_builds_cursor ON builds(created_at DESC, id DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_audit_cursor ON audit_logs(timestamp DESC, id DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_jobs_cursor ON jobs(created_at ASC, id ASC);
