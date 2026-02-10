CREATE TABLE IF NOT EXISTS shared_resource_grants (
    id              TEXT PRIMARY KEY,
    source_org_id   TEXT NOT NULL,
    target_org_id   TEXT NOT NULL,
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    granted_by      TEXT REFERENCES users(id),
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TEXT,
    UNIQUE(source_org_id, target_org_id, resource_type, resource_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_shared_target ON shared_resource_grants(target_org_id, resource_type);
--;;
CREATE INDEX IF NOT EXISTS idx_shared_source ON shared_resource_grants(source_org_id);
