CREATE TABLE IF NOT EXISTS releases (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    job_id          TEXT NOT NULL,
    build_id        TEXT NOT NULL,
    version         TEXT NOT NULL,
    title           TEXT,
    notes           TEXT,
    status          TEXT NOT NULL DEFAULT 'draft',
    created_by      TEXT,
    published_at    TEXT,
    deprecated_at   TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, job_id, version)
);
--;;
CREATE INDEX IF NOT EXISTS idx_releases_org_job ON releases(org_id, job_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_releases_build ON releases(build_id);
