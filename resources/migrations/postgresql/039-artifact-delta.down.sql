ALTER TABLE build_artifacts DROP COLUMN IF EXISTS original_size_bytes;
--;;
ALTER TABLE build_artifacts DROP COLUMN IF EXISTS is_delta;
--;;
ALTER TABLE build_artifacts DROP COLUMN IF EXISTS delta_base_id;
