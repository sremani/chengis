-- Job dependencies: defines upstream/downstream relationships between jobs
CREATE TABLE IF NOT EXISTS job_dependencies (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL,
  depends_on_job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  trigger_on TEXT NOT NULL DEFAULT 'success',
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(job_id, depends_on_job_id)
);

--;;

CREATE INDEX IF NOT EXISTS idx_job_deps_job_id
  ON job_dependencies(job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_job_deps_depends_on
  ON job_dependencies(depends_on_job_id);

--;;

CREATE INDEX IF NOT EXISTS idx_job_deps_org_id
  ON job_dependencies(org_id);

--;;

-- Dependency triggers: records of builds triggered by upstream completions
CREATE TABLE IF NOT EXISTS dependency_triggers (
  id TEXT PRIMARY KEY,
  source_build_id TEXT NOT NULL,
  source_job_id TEXT NOT NULL,
  target_build_id TEXT NOT NULL,
  target_job_id TEXT NOT NULL,
  org_id TEXT NOT NULL DEFAULT 'default-org',
  trigger_status TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX IF NOT EXISTS idx_dep_triggers_source
  ON dependency_triggers(source_build_id);

--;;

CREATE INDEX IF NOT EXISTS idx_dep_triggers_target
  ON dependency_triggers(target_build_id);
