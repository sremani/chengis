-- Plugin trust policies: allowlist for external plugin loading
CREATE TABLE IF NOT EXISTS plugin_policies (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  plugin_name TEXT NOT NULL,
  trust_level TEXT NOT NULL DEFAULT 'untrusted',
  allowed BOOLEAN NOT NULL DEFAULT false,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE UNIQUE INDEX idx_plugin_policies_org_name ON plugin_policies(org_id, plugin_name);
--;;
-- Docker image policies: registry allowlists and image denylists
CREATE TABLE IF NOT EXISTS docker_policies (
  id TEXT PRIMARY KEY,
  org_id TEXT,
  policy_type TEXT NOT NULL,
  pattern TEXT NOT NULL,
  action TEXT NOT NULL DEFAULT 'allow',
  description TEXT,
  priority INTEGER NOT NULL DEFAULT 100,
  enabled BOOLEAN NOT NULL DEFAULT true,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_docker_policies_org ON docker_policies(org_id);
--;;
CREATE INDEX idx_docker_policies_type ON docker_policies(policy_type);
