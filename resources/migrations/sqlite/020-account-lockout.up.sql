-- Account lockout support: track failed login attempts and lockout state.
ALTER TABLE users ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE users ADD COLUMN locked_until TEXT;
--;;
CREATE TABLE IF NOT EXISTS login_attempts (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  ip_address TEXT,
  success INTEGER NOT NULL DEFAULT 0,
  attempted_at TEXT NOT NULL DEFAULT (datetime('now'))
);
--;;
CREATE INDEX idx_login_attempts_user ON login_attempts(username, attempted_at);
