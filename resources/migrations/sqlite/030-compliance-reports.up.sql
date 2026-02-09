-- Compliance reports: hash-chained audit log + compliance report templates/runs.

ALTER TABLE audit_logs ADD COLUMN prev_hash TEXT;
--;;
ALTER TABLE audit_logs ADD COLUMN entry_hash TEXT;
--;;
CREATE TABLE IF NOT EXISTS compliance_reports (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  report_type TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  filters TEXT,
  created_by TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_compliance_reports_org ON compliance_reports(org_id);
--;;
CREATE INDEX idx_compliance_reports_type ON compliance_reports(report_type);
--;;
CREATE TABLE IF NOT EXISTS compliance_report_runs (
  id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL REFERENCES compliance_reports(id),
  org_id TEXT,
  status TEXT NOT NULL DEFAULT 'pending',
  generated_by TEXT,
  period_start TEXT,
  period_end TEXT,
  summary TEXT,
  report_hash TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TEXT
);
--;;
CREATE INDEX idx_compliance_runs_report ON compliance_report_runs(report_id);
--;;
CREATE INDEX idx_compliance_runs_org ON compliance_report_runs(org_id);
