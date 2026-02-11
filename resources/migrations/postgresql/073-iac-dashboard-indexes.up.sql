CREATE INDEX IF NOT EXISTS idx_iac_projects_dashboard ON iac_projects(org_id, updated_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_iac_plans_dashboard ON iac_plans(org_id, status, created_at DESC);
