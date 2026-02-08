-- Approval gates for pipeline stages
CREATE TABLE IF NOT EXISTS approval_gates (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  stage_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  required_role TEXT NOT NULL DEFAULT 'developer',
  message TEXT,
  timeout_minutes INTEGER DEFAULT 1440,
  approved_by TEXT,
  approved_at TIMESTAMPTZ,
  rejected_by TEXT,
  rejected_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id, stage_name)
);
--;;
CREATE INDEX idx_approval_gates_build ON approval_gates(build_id);
--;;
CREATE INDEX idx_approval_gates_status ON approval_gates(status);
