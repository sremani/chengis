-- Build attempt tracking: explicit attempt numbers for retry chains.

ALTER TABLE builds ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;
--;;
ALTER TABLE builds ADD COLUMN root_build_id TEXT;
--;;
CREATE INDEX idx_builds_root ON builds(root_build_id);
