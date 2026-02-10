ALTER TABLE build_artifacts ADD COLUMN delta_base_id TEXT;
--;;
ALTER TABLE build_artifacts ADD COLUMN is_delta BOOLEAN NOT NULL DEFAULT FALSE;
--;;
ALTER TABLE build_artifacts ADD COLUMN original_size_bytes BIGINT;
