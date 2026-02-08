-- Account lockout support: track failed login attempts and lockout state.
ALTER TABLE users ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0;
--;;
ALTER TABLE users ADD COLUMN locked_until TIMESTAMPTZ;
--;;
CREATE TABLE IF NOT EXISTS login_attempts (
  id TEXT PRIMARY KEY,
  username TEXT NOT NULL,
  ip_address TEXT,
  success INTEGER NOT NULL DEFAULT 0,
  attempted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_login_attempts_user ON login_attempts(username, attempted_at);
