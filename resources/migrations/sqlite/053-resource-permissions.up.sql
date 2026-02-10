CREATE TABLE IF NOT EXISTS resource_permissions (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    user_id         TEXT NOT NULL REFERENCES users(id),
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    action          TEXT NOT NULL,
    granted_by      TEXT REFERENCES users(id),
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TEXT,
    UNIQUE(org_id, user_id, resource_type, resource_id, action)
);
--;;
CREATE INDEX IF NOT EXISTS idx_rperm_user ON resource_permissions(user_id, org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_rperm_resource ON resource_permissions(resource_type, resource_id, org_id);
