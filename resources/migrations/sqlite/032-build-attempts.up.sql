-- Build attempt tracking: explicit attempt numbers for retry chains.
-- Each retry of a build gets an attempt_number, and root_build_id links
-- back to the original build that started the chain.

ALTER TABLE builds ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;
--;;
ALTER TABLE builds ADD COLUMN root_build_id TEXT;
--;;
CREATE INDEX idx_builds_root ON builds(root_build_id);
