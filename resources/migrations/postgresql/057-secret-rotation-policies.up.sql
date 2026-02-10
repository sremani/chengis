CREATE TABLE IF NOT EXISTS secret_rotation_policies (
    id                      TEXT PRIMARY KEY,
    org_id                  TEXT NOT NULL,
    secret_name             TEXT NOT NULL,
    secret_scope            TEXT NOT NULL DEFAULT 'global',
    rotation_interval_days  INTEGER NOT NULL DEFAULT 90,
    max_versions            INTEGER NOT NULL DEFAULT 3,
    notify_days_before      INTEGER NOT NULL DEFAULT 7,
    last_rotated_at         TEXT,
    next_rotation_at        TEXT,
    enabled                 INTEGER NOT NULL DEFAULT 1,
    created_by              TEXT REFERENCES users(id),
    created_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, secret_name, secret_scope)
);
--;;
CREATE INDEX IF NOT EXISTS idx_srp_org ON secret_rotation_policies(org_id);
--;;
CREATE INDEX IF NOT EXISTS idx_srp_next ON secret_rotation_policies(next_rotation_at);
