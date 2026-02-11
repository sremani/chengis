CREATE TABLE IF NOT EXISTS iac_states (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    project_id      TEXT NOT NULL,
    version         INTEGER NOT NULL DEFAULT 1,
    state_hash      TEXT NOT NULL,
    state_size      INTEGER NOT NULL,
    state_data      TEXT,
    tool_type       TEXT NOT NULL,
    workspace_name  TEXT DEFAULT 'default',
    created_by      TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, workspace_name, version)
);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_states_project ON iac_states(project_id, workspace_name, version DESC);
--;;
CREATE TABLE IF NOT EXISTS iac_state_locks (
    id              TEXT PRIMARY KEY,
    project_id      TEXT NOT NULL,
    workspace_name  TEXT NOT NULL DEFAULT 'default',
    locked_by       TEXT NOT NULL,
    locked_at       TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lock_reason     TEXT,
    expires_at      TEXT,
    UNIQUE(project_id, workspace_name)
);
