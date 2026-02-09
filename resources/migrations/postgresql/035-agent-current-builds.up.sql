-- Persistent agent registry: add current_builds column for write-through cache
ALTER TABLE agents ADD COLUMN current_builds INTEGER NOT NULL DEFAULT 0;
