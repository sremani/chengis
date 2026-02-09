-- Rollback multi-approver support
DROP TABLE IF EXISTS approval_responses;
--;;
-- SQLite doesn't support DROP COLUMN, so we recreate approval_gates without new columns
CREATE TABLE approval_gates_old AS SELECT
  id, build_id, stage_name, status, required_role, message, timeout_minutes,
  approved_by, approved_at, rejected_by, rejected_at, created_at
FROM approval_gates;
--;;
DROP TABLE approval_gates;
--;;
CREATE TABLE approval_gates (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  stage_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  required_role TEXT NOT NULL DEFAULT 'developer',
  message TEXT,
  timeout_minutes INTEGER DEFAULT 1440,
  approved_by TEXT,
  approved_at TEXT,
  rejected_by TEXT,
  rejected_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(build_id, stage_name)
);
--;;
INSERT INTO approval_gates SELECT * FROM approval_gates_old;
--;;
DROP TABLE approval_gates_old;
--;;
CREATE INDEX idx_approval_gates_build ON approval_gates(build_id);
--;;
CREATE INDEX idx_approval_gates_status ON approval_gates(status);
