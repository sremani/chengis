-- Add org_id to core tables for multi-tenancy (SQLite)
-- SQLite requires table recreation for NOT NULL + unique constraint changes

-- ============================================================
-- JOBS: recreate with org_id NOT NULL, UNIQUE(org_id, name)
-- ============================================================
CREATE TABLE jobs_new (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    org_id      TEXT NOT NULL DEFAULT 'default-org' REFERENCES organizations(id),
    pipeline    TEXT NOT NULL,
    triggers    TEXT,
    parameters  TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(org_id, name)
);

--;;

INSERT INTO jobs_new (id, name, org_id, pipeline, triggers, parameters, created_at, updated_at)
SELECT id, name, 'default-org', pipeline, triggers, parameters, created_at, updated_at
FROM jobs;

--;;

DROP TABLE jobs;

--;;

ALTER TABLE jobs_new RENAME TO jobs;

--;;

CREATE INDEX IF NOT EXISTS idx_jobs_org ON jobs(org_id);

--;;

-- Rebuild indexes that existed before (from initial schema)
CREATE INDEX IF NOT EXISTS idx_builds_job_id ON builds(job_id);

--;;

-- ============================================================
-- BUILDS: add org_id (denormalized for fast queries)
-- ============================================================
ALTER TABLE builds ADD COLUMN org_id TEXT DEFAULT 'default-org' REFERENCES organizations(id);

--;;

UPDATE builds SET org_id = 'default-org' WHERE org_id IS NULL;

--;;

CREATE INDEX IF NOT EXISTS idx_builds_org ON builds(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_builds_org_status ON builds(org_id, status);

--;;

-- ============================================================
-- PIPELINE_TEMPLATES: recreate with org_id NOT NULL, UNIQUE(org_id, name)
-- ============================================================
CREATE TABLE pipeline_templates_new (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    org_id      TEXT NOT NULL DEFAULT 'default-org' REFERENCES organizations(id),
    description TEXT,
    format      TEXT NOT NULL DEFAULT 'edn',
    content     TEXT NOT NULL,
    parameters  TEXT,
    version     INTEGER NOT NULL DEFAULT 1,
    created_by  TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at  TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(org_id, name)
);

--;;

INSERT INTO pipeline_templates_new (id, name, org_id, description, format, content, parameters, version, created_by, created_at, updated_at)
SELECT id, name, 'default-org', description, format, content, parameters, version, created_by, created_at, updated_at
FROM pipeline_templates;

--;;

DROP TABLE pipeline_templates;

--;;

ALTER TABLE pipeline_templates_new RENAME TO pipeline_templates;

--;;

CREATE INDEX IF NOT EXISTS idx_templates_org ON pipeline_templates(org_id);

--;;

-- ============================================================
-- SECRETS: recreate with org_id NOT NULL, UNIQUE(org_id, scope, name)
-- ============================================================
CREATE TABLE secrets_new (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL DEFAULT 'default-org' REFERENCES organizations(id),
    scope           TEXT NOT NULL DEFAULT 'global',
    name            TEXT NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_at      TEXT DEFAULT (datetime('now')),
    updated_at      TEXT DEFAULT (datetime('now')),
    UNIQUE(org_id, scope, name)
);

--;;

INSERT INTO secrets_new (id, org_id, scope, name, encrypted_value, created_at, updated_at)
SELECT id, 'default-org', scope, name, encrypted_value, created_at, updated_at
FROM secrets;

--;;

DROP TABLE secrets;

--;;

ALTER TABLE secrets_new RENAME TO secrets;

--;;

CREATE INDEX IF NOT EXISTS idx_secrets_org ON secrets(org_id);

--;;

-- ============================================================
-- AUDIT_LOGS: add org_id (nullable — system events have no org)
-- ============================================================
ALTER TABLE audit_logs ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE audit_logs SET org_id = 'default-org';

--;;

-- ============================================================
-- WEBHOOK_EVENTS: add org_id (nullable)
-- ============================================================
ALTER TABLE webhook_events ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

-- ============================================================
-- SECRET_ACCESS_LOG: add org_id
-- ============================================================
ALTER TABLE secret_access_log ADD COLUMN org_id TEXT REFERENCES organizations(id);

--;;

UPDATE secret_access_log SET org_id = 'default-org';

--;;

-- ============================================================
-- Assign all existing users to default org with their current role
-- ============================================================
INSERT OR IGNORE INTO org_memberships (id, org_id, user_id, role, created_at)
SELECT id || '-default-mem', 'default-org', id, role, datetime('now')
FROM users;
