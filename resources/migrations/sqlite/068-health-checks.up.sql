CREATE TABLE IF NOT EXISTS health_check_definitions (
    id                  TEXT PRIMARY KEY,
    org_id              TEXT NOT NULL,
    environment_id      TEXT NOT NULL,
    name                TEXT NOT NULL,
    check_type          TEXT NOT NULL,
    config_json         TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_health_checks_env ON health_check_definitions(environment_id, enabled);
--;;
CREATE TABLE IF NOT EXISTS health_check_results (
    id                  TEXT PRIMARY KEY,
    health_check_id     TEXT NOT NULL,
    deployment_id       TEXT,
    status              TEXT NOT NULL,
    response_time_ms    INTEGER,
    output              TEXT,
    checked_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_health_results_check ON health_check_results(health_check_id, checked_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_health_results_deployment ON health_check_results(deployment_id);
