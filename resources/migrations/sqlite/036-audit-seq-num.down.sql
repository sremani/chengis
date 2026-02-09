-- SQLite < 3.35 cannot DROP COLUMN, so recreate the table without seq_num
CREATE TABLE audit_logs_backup AS SELECT
  id, timestamp, user_id, username, action, resource_type, resource_id,
  detail, ip_address, user_agent, prev_hash, entry_hash, org_id
FROM audit_logs;
--;;
DROP TABLE audit_logs;
--;;
CREATE TABLE audit_logs (
  id TEXT PRIMARY KEY,
  timestamp TEXT NOT NULL DEFAULT (datetime('now')),
  user_id TEXT,
  username TEXT,
  action TEXT NOT NULL,
  resource_type TEXT,
  resource_id TEXT,
  detail TEXT,
  ip_address TEXT,
  user_agent TEXT,
  prev_hash TEXT,
  entry_hash TEXT,
  org_id TEXT
);
--;;
INSERT INTO audit_logs SELECT * FROM audit_logs_backup;
--;;
DROP TABLE audit_logs_backup;
--;;
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
--;;
CREATE INDEX idx_audit_user ON audit_logs(user_id);
--;;
CREATE INDEX idx_audit_action ON audit_logs(action);
