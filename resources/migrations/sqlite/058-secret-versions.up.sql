CREATE TABLE IF NOT EXISTS secret_versions (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    secret_name         TEXT NOT NULL,
    secret_scope        TEXT NOT NULL DEFAULT 'global',
    version             INTEGER NOT NULL,
    rotated_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_by          TEXT,
    rotation_reason     TEXT,
    previous_value_hash TEXT,
    UNIQUE(org_id, secret_name, secret_scope, version)
);
--;;
CREATE INDEX IF NOT EXISTS idx_sv_secret ON secret_versions(org_id, secret_name, secret_scope);
