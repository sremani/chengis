ALTER TABLE jobs DROP COLUMN IF EXISTS default_branch;
--;;
ALTER TABLE jobs DROP COLUMN IF EXISTS auto_merge_enabled;
--;;
ALTER TABLE jobs DROP COLUMN IF EXISTS path_filters;
--;;
ALTER TABLE jobs DROP COLUMN IF EXISTS branch_overrides;
