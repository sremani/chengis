CREATE TABLE IF NOT EXISTS environments (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    name                TEXT NOT NULL,
    slug                TEXT NOT NULL,
    env_order           INTEGER NOT NULL,
    description         TEXT,
    requires_approval   INTEGER DEFAULT 0,
    auto_promote        INTEGER DEFAULT 0,
    locked              INTEGER DEFAULT 0,
    locked_by           TEXT,
    locked_at           TEXT,
    config_json         TEXT,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, slug)
);
--;;
CREATE INDEX IF NOT EXISTS idx_environments_org ON environments(org_id, env_order);
