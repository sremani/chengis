-- Rollback: remove org_id columns and restore original constraints
-- WARNING: This will lose organization assignments

DELETE FROM org_memberships WHERE org_id = 'default-org';

--;;

ALTER TABLE secret_access_log DROP COLUMN IF EXISTS org_id;

--;;

ALTER TABLE webhook_events DROP COLUMN IF EXISTS org_id;

--;;

ALTER TABLE audit_logs DROP COLUMN IF EXISTS org_id;

--;;

DROP INDEX IF EXISTS idx_secrets_org;
DROP INDEX IF EXISTS idx_secrets_org_scope_name;
ALTER TABLE secrets DROP COLUMN IF EXISTS org_id;
ALTER TABLE secrets ADD CONSTRAINT secrets_scope_name_key UNIQUE(scope, name);

--;;

DROP INDEX IF EXISTS idx_templates_org;
DROP INDEX IF EXISTS idx_templates_org_name;
ALTER TABLE pipeline_templates DROP COLUMN IF EXISTS org_id;
ALTER TABLE pipeline_templates ADD CONSTRAINT pipeline_templates_name_key UNIQUE(name);

--;;

DROP INDEX IF EXISTS idx_builds_org_status;
DROP INDEX IF EXISTS idx_builds_org;
ALTER TABLE builds DROP COLUMN IF EXISTS org_id;

--;;

DROP INDEX IF EXISTS idx_jobs_org;
DROP INDEX IF EXISTS idx_jobs_org_name;
ALTER TABLE jobs DROP COLUMN IF EXISTS org_id;
ALTER TABLE jobs ADD CONSTRAINT jobs_name_key UNIQUE(name);
