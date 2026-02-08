CREATE TABLE IF NOT EXISTS secrets (
  id TEXT PRIMARY KEY,
  scope TEXT NOT NULL DEFAULT 'global',
  name TEXT NOT NULL,
  encrypted_value TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now')),
  UNIQUE(scope, name)
);
