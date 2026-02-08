-- Docker integration: track container metadata for build steps
ALTER TABLE build_steps ADD COLUMN container_image TEXT;
ALTER TABLE build_steps ADD COLUMN container_id TEXT;
