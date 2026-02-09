-- SQLite does not support DROP COLUMN before 3.35.0.
-- Recreate the builds table without the new columns.

CREATE TABLE builds_backup AS SELECT
  id, job_id, build_number, status, trigger_type, parameters, workspace,
  started_at, completed_at, created_at,
  git_branch, git_commit, git_commit_short, git_author, git_message,
  pipeline_source, parent_build_id,
  agent_id, dispatched_at, org_id
FROM builds;
--;;
DROP TABLE builds;
--;;
CREATE TABLE builds (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES jobs(id),
  build_number INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'queued',
  trigger_type TEXT,
  parameters TEXT,
  workspace TEXT,
  started_at TEXT,
  completed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  git_branch TEXT,
  git_commit TEXT,
  git_commit_short TEXT,
  git_author TEXT,
  git_message TEXT,
  pipeline_source TEXT DEFAULT 'server',
  parent_build_id TEXT,
  agent_id TEXT,
  dispatched_at TEXT,
  org_id TEXT DEFAULT 'default-org',
  UNIQUE(job_id, build_number)
);
--;;
INSERT INTO builds SELECT * FROM builds_backup;
--;;
DROP TABLE builds_backup;
--;;
CREATE INDEX idx_builds_job_id ON builds(job_id);
--;;
CREATE INDEX idx_builds_status ON builds(status);
--;;
CREATE INDEX idx_builds_git_branch ON builds(git_branch);
--;;
CREATE INDEX idx_builds_git_commit ON builds(git_commit);
--;;
CREATE INDEX idx_builds_org ON builds(org_id);
