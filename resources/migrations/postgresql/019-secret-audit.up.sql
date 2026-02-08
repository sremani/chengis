CREATE TABLE IF NOT EXISTS secret_access_log (
  id TEXT PRIMARY KEY,
  secret_name TEXT NOT NULL,
  scope TEXT NOT NULL DEFAULT 'global',
  action TEXT NOT NULL,
  user_id TEXT,
  ip_address TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_secret_access_name ON secret_access_log(secret_name, created_at);
CREATE INDEX idx_secret_access_created ON secret_access_log(created_at);
