CREATE TABLE IF NOT EXISTS iac_projects (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    job_id          TEXT NOT NULL,
    tool_type       TEXT NOT NULL,
    working_dir     TEXT DEFAULT '.',
    config_json     TEXT,
    auto_detect     INTEGER NOT NULL DEFAULT 1,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, job_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_projects_org ON iac_projects(org_id);
--;;
CREATE TABLE IF NOT EXISTS iac_plans (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    project_id      TEXT NOT NULL,
    build_id        TEXT,
    action          TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    plan_json       TEXT,
    resources_add   INTEGER DEFAULT 0,
    resources_change INTEGER DEFAULT 0,
    resources_destroy INTEGER DEFAULT 0,
    output          TEXT,
    error_output    TEXT,
    duration_ms     INTEGER,
    initiated_by    TEXT,
    approved_by     TEXT,
    approved_at     TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_plans_project ON iac_plans(project_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_plans_org ON iac_plans(org_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_plans_build ON iac_plans(build_id);
