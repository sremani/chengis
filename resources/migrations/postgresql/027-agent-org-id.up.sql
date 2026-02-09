-- Add optional org_id to agents for tenant-scoped agent pools
-- NULL = shared agent (available to all organizations)
ALTER TABLE agents ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

CREATE INDEX IF NOT EXISTS idx_agents_org ON agents(org_id);
