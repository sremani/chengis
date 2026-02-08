DROP TABLE IF EXISTS agents;
ALTER TABLE builds DROP COLUMN agent_id;
ALTER TABLE builds DROP COLUMN dispatched_at;
