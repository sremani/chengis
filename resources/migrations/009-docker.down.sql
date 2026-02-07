-- SQLite doesn't support DROP COLUMN before 3.35.0
-- For older versions this would require recreating the table
-- For now we rely on SQLite >= 3.35.0
ALTER TABLE build_steps DROP COLUMN container_image;
ALTER TABLE build_steps DROP COLUMN container_id;
