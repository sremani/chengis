-- Rollback: remove org_id from agents
DROP INDEX IF EXISTS idx_agents_org;

--;;

ALTER TABLE agents DROP COLUMN IF EXISTS org_id;
