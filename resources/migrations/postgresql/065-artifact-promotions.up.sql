CREATE TABLE IF NOT EXISTS artifact_promotions (
    id                      TEXT PRIMARY KEY,
    org_id                  TEXT NOT NULL,
    build_id                TEXT NOT NULL,
    artifact_id             TEXT,
    from_environment_id     TEXT,
    to_environment_id       TEXT NOT NULL,
    status                  TEXT NOT NULL DEFAULT 'pending',
    promoted_by             TEXT,
    promoted_at             TEXT,
    rejection_reason        TEXT,
    created_at              TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_promotions_org ON artifact_promotions(org_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_promotions_to_env ON artifact_promotions(to_environment_id, status);
--;;
CREATE TABLE IF NOT EXISTS environment_artifacts (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    environment_id      TEXT NOT NULL,
    build_id            TEXT NOT NULL,
    artifact_id         TEXT,
    release_id          TEXT,
    deployed_at         TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status              TEXT NOT NULL DEFAULT 'active',
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_env_artifacts_env ON environment_artifacts(environment_id, status, deployed_at DESC);
