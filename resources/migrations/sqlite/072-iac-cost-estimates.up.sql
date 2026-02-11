CREATE TABLE IF NOT EXISTS iac_cost_estimates (
    id              TEXT PRIMARY KEY,
    org_id          TEXT NOT NULL,
    plan_id         TEXT NOT NULL,
    total_monthly   REAL DEFAULT 0.0,
    total_hourly    REAL DEFAULT 0.0,
    currency        TEXT DEFAULT 'USD',
    resources_json  TEXT,
    estimation_method TEXT DEFAULT 'basic',
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_costs_plan ON iac_cost_estimates(plan_id);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_costs_org ON iac_cost_estimates(org_id, created_at DESC);
