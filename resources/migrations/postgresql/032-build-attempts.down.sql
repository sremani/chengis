DROP INDEX IF EXISTS idx_builds_root;
--;;
ALTER TABLE builds DROP COLUMN IF EXISTS root_build_id;
--;;
ALTER TABLE builds DROP COLUMN IF EXISTS attempt_number;
