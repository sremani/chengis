-- Rollback: remove org_id columns (SQLite)
-- WARNING: This will lose organization assignments

-- Remove auto-created memberships
DELETE FROM org_memberships WHERE org_id = 'default-org';

--;;

-- Secret access log: drop org_id
-- SQLite doesn't support DROP COLUMN before 3.35.0, use recreate
ALTER TABLE secret_access_log RENAME TO secret_access_log_old;

--;;

CREATE TABLE secret_access_log (
    id TEXT PRIMARY KEY,
    secret_name TEXT NOT NULL,
    scope TEXT NOT NULL DEFAULT 'global',
    action TEXT NOT NULL,
    user_id TEXT,
    ip_address TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

--;;

INSERT INTO secret_access_log (id, secret_name, scope, action, user_id, ip_address, created_at)
SELECT id, secret_name, scope, action, user_id, ip_address, created_at FROM secret_access_log_old;

--;;

DROP TABLE secret_access_log_old;

--;;

-- Recreate secrets without org_id
CREATE TABLE secrets_old (
    id TEXT PRIMARY KEY,
    scope TEXT NOT NULL DEFAULT 'global',
    name TEXT NOT NULL,
    encrypted_value TEXT NOT NULL,
    created_at TEXT DEFAULT (datetime('now')),
    updated_at TEXT DEFAULT (datetime('now')),
    UNIQUE(scope, name)
);

--;;

INSERT INTO secrets_old (id, scope, name, encrypted_value, created_at, updated_at)
SELECT id, scope, name, encrypted_value, created_at, updated_at FROM secrets;

--;;

DROP TABLE secrets;

--;;

ALTER TABLE secrets_old RENAME TO secrets;

--;;

-- Recreate pipeline_templates without org_id
CREATE TABLE pipeline_templates_old (
    id TEXT PRIMARY KEY,
    name TEXT UNIQUE NOT NULL,
    description TEXT,
    format TEXT NOT NULL DEFAULT 'edn',
    content TEXT NOT NULL,
    parameters TEXT,
    version INTEGER NOT NULL DEFAULT 1,
    created_by TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

--;;

INSERT INTO pipeline_templates_old (id, name, description, format, content, parameters, version, created_by, created_at, updated_at)
SELECT id, name, description, format, content, parameters, version, created_by, created_at, updated_at FROM pipeline_templates;

--;;

DROP TABLE pipeline_templates;

--;;

ALTER TABLE pipeline_templates_old RENAME TO pipeline_templates;

--;;

-- Recreate jobs without org_id
CREATE TABLE jobs_old (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    pipeline TEXT NOT NULL,
    triggers TEXT,
    parameters TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

--;;

INSERT INTO jobs_old (id, name, pipeline, triggers, parameters, created_at, updated_at)
SELECT id, name, pipeline, triggers, parameters, created_at, updated_at FROM jobs;

--;;

DROP TABLE jobs;

--;;

ALTER TABLE jobs_old RENAME TO jobs;
