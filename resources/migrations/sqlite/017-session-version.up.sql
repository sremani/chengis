-- Session version for mass invalidation (bump version → all old sessions invalid)
ALTER TABLE users ADD COLUMN session_version INTEGER NOT NULL DEFAULT 1;
