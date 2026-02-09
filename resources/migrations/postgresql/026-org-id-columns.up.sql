-- Add org_id to core tables for multi-tenancy
-- All existing data is assigned to the 'default-org' organization

-- Jobs: add org_id, backfill, make NOT NULL, add org-scoped unique
ALTER TABLE jobs ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE jobs SET org_id = 'default-org' WHERE org_id IS NULL;

--;;

ALTER TABLE jobs ALTER COLUMN org_id SET NOT NULL;

--;;

ALTER TABLE jobs ALTER COLUMN org_id SET DEFAULT 'default-org';

--;;

-- Drop old global unique on name (PostgreSQL auto-names it jobs_name_key)
ALTER TABLE jobs DROP CONSTRAINT IF EXISTS jobs_name_key;

--;;

-- Add org-scoped unique constraint
CREATE UNIQUE INDEX idx_jobs_org_name ON jobs(org_id, name);

--;;

CREATE INDEX idx_jobs_org ON jobs(org_id);

--;;

-- Builds: denormalized org_id for fast queries without JOINs
ALTER TABLE builds ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE builds SET org_id = 'default-org' WHERE org_id IS NULL;

--;;

ALTER TABLE builds ALTER COLUMN org_id SET NOT NULL;

--;;

ALTER TABLE builds ALTER COLUMN org_id SET DEFAULT 'default-org';

--;;

CREATE INDEX idx_builds_org ON builds(org_id);

--;;

CREATE INDEX idx_builds_org_status ON builds(org_id, status);

--;;

-- Pipeline templates: add org_id, org-scoped unique
ALTER TABLE pipeline_templates ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE pipeline_templates SET org_id = 'default-org' WHERE org_id IS NULL;

--;;

ALTER TABLE pipeline_templates ALTER COLUMN org_id SET NOT NULL;

--;;

ALTER TABLE pipeline_templates ALTER COLUMN org_id SET DEFAULT 'default-org';

--;;

-- Drop old global unique on name
ALTER TABLE pipeline_templates DROP CONSTRAINT IF EXISTS pipeline_templates_name_key;

--;;

DROP INDEX IF EXISTS idx_templates_name;

--;;

CREATE UNIQUE INDEX idx_templates_org_name ON pipeline_templates(org_id, name);

--;;

CREATE INDEX idx_templates_org ON pipeline_templates(org_id);

--;;

-- Secrets: add org_id, org-scoped unique
ALTER TABLE secrets ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE secrets SET org_id = 'default-org' WHERE org_id IS NULL;

--;;

ALTER TABLE secrets ALTER COLUMN org_id SET NOT NULL;

--;;

ALTER TABLE secrets ALTER COLUMN org_id SET DEFAULT 'default-org';

--;;

-- Drop old unique(scope, name), add org-scoped unique
ALTER TABLE secrets DROP CONSTRAINT IF EXISTS secrets_scope_name_key;

--;;

CREATE UNIQUE INDEX idx_secrets_org_scope_name ON secrets(org_id, scope, name);

--;;

CREATE INDEX idx_secrets_org ON secrets(org_id);

--;;

-- Audit logs: add org_id (nullable — system events may have no org)
ALTER TABLE audit_logs ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE audit_logs SET org_id = 'default-org';

--;;

-- Webhook events: add org_id (nullable — unmatched webhooks have no org)
ALTER TABLE webhook_events ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

-- Secret access log: add org_id
ALTER TABLE secret_access_log ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE secret_access_log SET org_id = 'default-org';

--;;

-- Assign all existing users to default org with their current role
INSERT INTO org_memberships (id, org_id, user_id, role, created_at)
SELECT id || '-default-mem', 'default-org', id, role, CURRENT_TIMESTAMP
FROM users
ON CONFLICT DO NOTHING;
