-- Policy engine: org-scoped policies with evaluation logging.

CREATE TABLE IF NOT EXISTS policies (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  name TEXT NOT NULL,
  description TEXT,
  policy_type TEXT NOT NULL,
  rules JSONB NOT NULL,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_policies_org ON policies(org_id);
--;;
CREATE INDEX idx_policies_type ON policies(policy_type);
--;;
CREATE UNIQUE INDEX idx_policies_org_name ON policies(org_id, name);
--;;
CREATE TABLE IF NOT EXISTS policy_evaluations (
  id TEXT PRIMARY KEY,
  policy_id TEXT NOT NULL REFERENCES policies(id),
  build_id TEXT NOT NULL,
  stage_name TEXT,
  result TEXT NOT NULL,
  reason TEXT,
  context TEXT,
  evaluated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_policy_eval_build ON policy_evaluations(build_id);
--;;
CREATE INDEX idx_policy_eval_policy ON policy_evaluations(policy_id);
