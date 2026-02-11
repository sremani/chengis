CREATE TABLE IF NOT EXISTS partition_metadata (
    id              TEXT PRIMARY KEY,
    table_name      TEXT NOT NULL,
    partition_name  TEXT NOT NULL,
    range_start     TEXT NOT NULL,
    range_end       TEXT NOT NULL,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status          TEXT NOT NULL DEFAULT 'active',
    UNIQUE(table_name, partition_name)
);
--;;
CREATE INDEX IF NOT EXISTS idx_pm_table ON partition_metadata(table_name, status);
