-- Audit trail for security and compliance
CREATE TABLE IF NOT EXISTS audit_logs (
  id TEXT PRIMARY KEY,
  timestamp TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  user_id TEXT,
  username TEXT,
  action TEXT NOT NULL,
  resource_type TEXT,
  resource_id TEXT,
  detail TEXT,
  ip_address TEXT,
  user_agent TEXT
);
CREATE INDEX idx_audit_timestamp ON audit_logs(timestamp);
CREATE INDEX idx_audit_user ON audit_logs(user_id);
CREATE INDEX idx_audit_action ON audit_logs(action);
