CREATE TABLE IF NOT EXISTS build_log_chunks (
    id           TEXT PRIMARY KEY,
    build_id     TEXT NOT NULL,
    step_id      TEXT NOT NULL,
    chunk_index  INTEGER NOT NULL,
    source       TEXT NOT NULL DEFAULT 'stdout',
    line_start   INTEGER NOT NULL,
    line_count   INTEGER NOT NULL,
    content      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(step_id, source, chunk_index)
);
--;;
CREATE INDEX IF NOT EXISTS idx_blc_build_step ON build_log_chunks(build_id, step_id);
--;;
CREATE INDEX IF NOT EXISTS idx_blc_step_source ON build_log_chunks(step_id, source, chunk_index);
