-- SQLite <3.35 cannot DROP COLUMN; recreate table without current_builds
CREATE TABLE agents_backup AS SELECT id, name, url, labels, status,
  max_builds, system_info, last_heartbeat, registered_at, org_id FROM agents;
--;;
DROP TABLE agents;
--;;
CREATE TABLE agents (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  url TEXT NOT NULL,
  labels TEXT,
  status TEXT NOT NULL DEFAULT 'online',
  max_builds INTEGER NOT NULL DEFAULT 2,
  system_info TEXT,
  last_heartbeat TEXT,
  registered_at TEXT DEFAULT CURRENT_TIMESTAMP,
  org_id TEXT DEFAULT 'default-org'
);
--;;
INSERT INTO agents SELECT * FROM agents_backup;
--;;
DROP TABLE agents_backup;
