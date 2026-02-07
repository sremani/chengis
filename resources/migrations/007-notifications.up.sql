CREATE TABLE IF NOT EXISTS build_notifications (
  id TEXT PRIMARY KEY,
  build_id TEXT NOT NULL,
  type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  details TEXT,
  sent_at TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_notifications_build ON build_notifications(build_id);
