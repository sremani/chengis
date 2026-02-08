-- SQLite doesn't support DROP COLUMN before 3.35, use backup table pattern
CREATE TABLE api_tokens_backup AS SELECT id, user_id, name, token_hash, last_used_at, expires_at, revoked_at, created_at FROM api_tokens;
DROP TABLE api_tokens;
ALTER TABLE api_tokens_backup RENAME TO api_tokens;
CREATE INDEX idx_api_tokens_user ON api_tokens(user_id);
