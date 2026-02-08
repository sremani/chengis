-- Recreate api_tokens without revoked_at
CREATE TABLE api_tokens_backup AS SELECT id, user_id, name, token_hash, last_used_at, expires_at, created_at FROM api_tokens;
DROP TABLE api_tokens;
CREATE TABLE api_tokens (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  name TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  last_used_at TEXT,
  expires_at TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO api_tokens SELECT * FROM api_tokens_backup;
DROP TABLE api_tokens_backup;
