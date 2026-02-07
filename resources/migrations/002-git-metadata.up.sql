ALTER TABLE builds ADD COLUMN git_branch TEXT;

--;;

ALTER TABLE builds ADD COLUMN git_commit TEXT;

--;;

ALTER TABLE builds ADD COLUMN git_commit_short TEXT;

--;;

ALTER TABLE builds ADD COLUMN git_author TEXT;

--;;

ALTER TABLE builds ADD COLUMN git_message TEXT;

--;;

CREATE INDEX IF NOT EXISTS idx_builds_git_branch ON builds(git_branch);

--;;

CREATE INDEX IF NOT EXISTS idx_builds_git_commit ON builds(git_commit);
