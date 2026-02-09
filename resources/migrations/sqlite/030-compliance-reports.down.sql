-- Reverse compliance reports migration (SQLite).
-- SQLite lacks DROP COLUMN, so we recreate audit_logs without hash columns.

DROP TABLE IF EXISTS compliance_report_runs;
--;;
DROP TABLE IF EXISTS compliance_reports;
--;;
CREATE TABLE audit_logs_backup AS SELECT id, user_id, username, action, resource_type, resource_id, detail, ip_address, user_agent, timestamp, org_id FROM audit_logs;
--;;
DROP TABLE audit_logs;
--;;
CREATE TABLE audit_logs (
  id TEXT PRIMARY KEY,
  user_id TEXT,
  username TEXT,
  action TEXT NOT NULL,
  resource_type TEXT,
  resource_id TEXT,
  detail TEXT,
  ip_address TEXT,
  user_agent TEXT,
  timestamp TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  org_id TEXT
);
--;;
INSERT INTO audit_logs SELECT * FROM audit_logs_backup;
--;;
DROP TABLE audit_logs_backup;
--;;
CREATE INDEX idx_audit_logs_user ON audit_logs(user_id);
--;;
CREATE INDEX idx_audit_logs_action ON audit_logs(action);
--;;
CREATE INDEX idx_audit_logs_timestamp ON audit_logs(timestamp);
--;;
CREATE INDEX idx_audit_logs_org ON audit_logs(org_id);
