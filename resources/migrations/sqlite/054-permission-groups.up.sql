CREATE TABLE IF NOT EXISTS permission_groups (
    id          TEXT PRIMARY KEY,
    org_id      TEXT NOT NULL,
    name        TEXT NOT NULL,
    description TEXT,
    created_by  TEXT REFERENCES users(id),
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, name)
);
--;;
CREATE INDEX IF NOT EXISTS idx_pgrp_org ON permission_groups(org_id);
--;;
CREATE TABLE IF NOT EXISTS permission_group_entries (
    id              TEXT PRIMARY KEY,
    group_id        TEXT NOT NULL REFERENCES permission_groups(id),
    resource_type   TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    action          TEXT NOT NULL,
    UNIQUE(group_id, resource_type, resource_id, action)
);
--;;
CREATE INDEX IF NOT EXISTS idx_pge_group ON permission_group_entries(group_id);
--;;
CREATE TABLE IF NOT EXISTS permission_group_members (
    id          TEXT PRIMARY KEY,
    group_id    TEXT NOT NULL REFERENCES permission_groups(id),
    user_id     TEXT NOT NULL REFERENCES users(id),
    assigned_by TEXT REFERENCES users(id),
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(group_id, user_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_pgm_group ON permission_group_members(group_id);
--;;
CREATE INDEX IF NOT EXISTS idx_pgm_user ON permission_group_members(user_id);
