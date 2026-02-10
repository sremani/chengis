-- Phase 7: Supply Chain Security — Core tables
-- SLSA provenance attestations, SBOMs, vulnerability scans

CREATE TABLE IF NOT EXISTS provenance_attestations (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  slsa_level TEXT NOT NULL DEFAULT 'L1',
  predicate_type TEXT NOT NULL DEFAULT 'https://slsa.dev/provenance/v1',
  subject_json TEXT NOT NULL,
  predicate_json TEXT NOT NULL,
  envelope_json TEXT,
  builder_id TEXT NOT NULL DEFAULT 'chengis',
  build_type TEXT NOT NULL DEFAULT 'chengis/pipeline/v1',
  source_repo TEXT,
  source_branch TEXT,
  source_commit TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id)
);

--;;

CREATE INDEX IF NOT EXISTS idx_provenance_org ON provenance_attestations(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_provenance_build ON provenance_attestations(build_id);

--;;

CREATE TABLE IF NOT EXISTS sbom_reports (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  sbom_format TEXT NOT NULL,
  sbom_version TEXT,
  component_count INTEGER DEFAULT 0,
  content_hash TEXT,
  sbom_content TEXT NOT NULL,
  tool_name TEXT,
  tool_version TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id, sbom_format)
);

--;;

CREATE INDEX IF NOT EXISTS idx_sbom_org ON sbom_reports(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_sbom_build ON sbom_reports(build_id);

--;;

CREATE TABLE IF NOT EXISTS vulnerability_scans (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  scan_target TEXT NOT NULL,
  scanner TEXT NOT NULL,
  scanner_version TEXT,
  critical_count INTEGER DEFAULT 0,
  high_count INTEGER DEFAULT 0,
  medium_count INTEGER DEFAULT 0,
  low_count INTEGER DEFAULT 0,
  total_count INTEGER DEFAULT 0,
  pass_threshold TEXT,
  passed INTEGER DEFAULT 1,
  results_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id, scan_target, scanner)
);

--;;

CREATE INDEX IF NOT EXISTS idx_vulnscan_org ON vulnerability_scans(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_vulnscan_build ON vulnerability_scans(build_id);
