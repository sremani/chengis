CREATE TABLE IF NOT EXISTS deployment_strategies (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    name            TEXT NOT NULL,
    strategy_type   TEXT NOT NULL,
    config_json     TEXT,
    description     TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, name)
);
--;;
ALTER TABLE environments ADD COLUMN default_strategy_id TEXT;
