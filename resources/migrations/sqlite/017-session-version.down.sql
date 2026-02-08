-- Recreate users without session_version
CREATE TABLE users_backup AS SELECT id, username, password_hash, role, active, created_at, updated_at FROM users;
DROP TABLE users;
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'viewer',
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO users SELECT * FROM users_backup;
DROP TABLE users_backup;
CREATE UNIQUE INDEX idx_users_username ON users(username);
