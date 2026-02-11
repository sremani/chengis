CREATE INDEX IF NOT EXISTS idx_deployments_dashboard ON deployments(org_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_promotions_dashboard ON artifact_promotions(org_id, created_at DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_env_artifacts_dashboard ON environment_artifacts(environment_id, deployed_at DESC);
