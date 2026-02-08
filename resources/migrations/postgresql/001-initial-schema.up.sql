CREATE TABLE IF NOT EXISTS jobs (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL UNIQUE,
    pipeline    TEXT NOT NULL,
    triggers    TEXT,
    parameters  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE TABLE IF NOT EXISTS builds (
    id           TEXT PRIMARY KEY,
    job_id       TEXT NOT NULL REFERENCES jobs(id),
    build_number INTEGER NOT NULL,
    status       TEXT NOT NULL DEFAULT 'queued',
    trigger_type TEXT,
    parameters   TEXT,
    workspace    TEXT,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(job_id, build_number)
);

--;;

CREATE TABLE IF NOT EXISTS build_stages (
    id           TEXT PRIMARY KEY,
    build_id     TEXT NOT NULL REFERENCES builds(id),
    stage_name   TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    UNIQUE(build_id, stage_name)
);

--;;

CREATE TABLE IF NOT EXISTS build_steps (
    id           TEXT PRIMARY KEY,
    build_id     TEXT NOT NULL REFERENCES builds(id),
    stage_name   TEXT NOT NULL,
    step_name    TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'pending',
    exit_code    INTEGER,
    stdout       TEXT,
    stderr       TEXT,
    started_at   TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

--;;

CREATE TABLE IF NOT EXISTS build_logs (
    id         SERIAL PRIMARY KEY,
    build_id   TEXT NOT NULL REFERENCES builds(id),
    timestamp  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    level      TEXT NOT NULL,
    source     TEXT,
    message    TEXT NOT NULL
);

--;;

CREATE INDEX IF NOT EXISTS idx_builds_job_id ON builds(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_builds_status ON builds(status);

--;;

CREATE INDEX IF NOT EXISTS idx_build_logs_build_id ON build_logs(build_id);
