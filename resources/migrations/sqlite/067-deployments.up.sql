CREATE TABLE IF NOT EXISTS deployments (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    environment_id      TEXT NOT NULL,
    release_id          TEXT,
    build_id            TEXT NOT NULL,
    strategy_id         TEXT,
    status              TEXT NOT NULL DEFAULT 'pending',
    initiated_by        TEXT,
    started_at          TEXT,
    completed_at        TEXT,
    rollback_of         TEXT,
    metadata_json       TEXT,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_deployments_org_env ON deployments(org_id, environment_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_deployments_status ON deployments(status);
--;;
CREATE TABLE IF NOT EXISTS deployment_steps (
    id              TEXT PRIMARY KEY,
    deployment_id   TEXT NOT NULL,
    step_name       TEXT NOT NULL,
    step_order      INTEGER NOT NULL,
    status          TEXT NOT NULL DEFAULT 'pending',
    started_at      TEXT,
    completed_at    TEXT,
    output          TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_dep_steps_deployment ON deployment_steps(deployment_id, step_order);
