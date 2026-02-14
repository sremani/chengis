-- Phase 2 performance: missing indexes identified by profiling.

-- audit_logs(org_id) — added in migration 026 with no index.
CREATE INDEX IF NOT EXISTS idx_audit_logs_org_id ON audit_logs(org_id);

--;;

-- build_events(build_id, created_at) — replace single-column index.
DROP INDEX IF EXISTS idx_build_events_build;
--;;
CREATE INDEX IF NOT EXISTS idx_build_events_build_created ON build_events(build_id, created_at);

--;;

-- Composite pagination indexes for supply chain tables.
CREATE INDEX IF NOT EXISTS idx_sbom_reports_pagination ON sbom_reports(created_at DESC, id DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_vulnerability_scans_pagination ON vulnerability_scans(created_at DESC, id DESC);
--;;
CREATE INDEX IF NOT EXISTS idx_provenance_attestations_pagination ON provenance_attestations(created_at DESC, id DESC);
