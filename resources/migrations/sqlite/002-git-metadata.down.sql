DROP INDEX IF EXISTS idx_builds_git_commit;

--;;

DROP INDEX IF EXISTS idx_builds_git_branch;

--;;

ALTER TABLE builds DROP COLUMN git_message;

--;;

ALTER TABLE builds DROP COLUMN git_author;

--;;

ALTER TABLE builds DROP COLUMN git_commit_short;

--;;

ALTER TABLE builds DROP COLUMN git_commit;

--;;

ALTER TABLE builds DROP COLUMN git_branch;
