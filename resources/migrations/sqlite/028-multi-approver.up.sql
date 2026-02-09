-- Multi-approver support for approval gates
-- Adds approver group and minimum approval count to gates,
-- plus a separate table for individual approval responses.

ALTER TABLE approval_gates ADD COLUMN approver_group TEXT;
--;;
ALTER TABLE approval_gates ADD COLUMN min_approvals INTEGER NOT NULL DEFAULT 1;
--;;

CREATE TABLE IF NOT EXISTS approval_responses (
  id TEXT PRIMARY KEY,
  gate_id TEXT NOT NULL REFERENCES approval_gates(id),
  user_id TEXT NOT NULL,
  decision TEXT NOT NULL,
  comment TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(gate_id, user_id)
);
--;;
CREATE INDEX idx_approval_responses_gate ON approval_responses(gate_id);
