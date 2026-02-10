ALTER TABLE build_artifacts ADD COLUMN delta_base_id TEXT;
--;;
ALTER TABLE build_artifacts ADD COLUMN is_delta INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE build_artifacts ADD COLUMN original_size_bytes INTEGER;
