-- JWT blacklist for early invalidation (e.g., password change, forced logout)
CREATE TABLE IF NOT EXISTS jwt_blacklist (
  jti TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_jwt_blacklist_expires ON jwt_blacklist(expires_at);
