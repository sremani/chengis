-- Phase 7: OPA policies and license scanning

CREATE TABLE IF NOT EXISTS opa_policies (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  name TEXT NOT NULL,
  description TEXT,
  rego_source TEXT NOT NULL,
  package_name TEXT NOT NULL,
  input_schema TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, name)
);

--;;

CREATE INDEX IF NOT EXISTS idx_opa_org ON opa_policies(org_id);

--;;

CREATE TABLE IF NOT EXISTS license_reports (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  total_deps INTEGER DEFAULT 0,
  allowed_count INTEGER DEFAULT 0,
  denied_count INTEGER DEFAULT 0,
  unknown_count INTEGER DEFAULT 0,
  policy_passed INTEGER DEFAULT 1,
  licenses_json TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(build_id)
);

--;;

CREATE INDEX IF NOT EXISTS idx_license_org ON license_reports(org_id);

--;;

CREATE INDEX IF NOT EXISTS idx_license_build ON license_reports(build_id);

--;;

CREATE TABLE IF NOT EXISTS license_policies (
  id TEXT PRIMARY KEY,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  license_id TEXT NOT NULL,
  action TEXT NOT NULL DEFAULT 'allow',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, license_id)
);

--;;

CREATE INDEX IF NOT EXISTS idx_licpol_org ON license_policies(org_id);
