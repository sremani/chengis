-- Phase 7: Artifact signatures and regulatory readiness

CREATE TABLE IF NOT EXISTS artifact_signatures (
  id TEXT PRIMARY KEY,
  artifact_id TEXT,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  signer TEXT NOT NULL,
  key_reference TEXT,
  signature_value TEXT NOT NULL,
  verified INTEGER DEFAULT 0,
  verified_at TEXT,
  target_digest TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_artsig_org ON artifact_signatures(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_artsig_build ON artifact_signatures(build_id);

--;;

CREATE TABLE IF NOT EXISTS regulatory_checks (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  framework TEXT NOT NULL,
  control_id TEXT NOT NULL,
  control_name TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'not-assessed',
  evidence_summary TEXT,
  last_assessed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, framework, control_id)
);

--;;

CREATE INDEX IF NOT EXISTS idx_regcheck_org ON regulatory_checks(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_regcheck_framework ON regulatory_checks(org_id, framework);
