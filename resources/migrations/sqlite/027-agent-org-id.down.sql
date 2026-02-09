-- Rollback: remove org_id from agents (SQLite recreate)
CREATE TABLE agents_old (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    url TEXT NOT NULL,
    labels TEXT,
    status TEXT NOT NULL DEFAULT 'online',
    max_builds INTEGER NOT NULL DEFAULT 2,
    system_info TEXT,
    last_heartbeat TEXT,
    registered_at TEXT DEFAULT (datetime('now'))
);

--;;

INSERT INTO agents_old (id, name, url, labels, status, max_builds, system_info, last_heartbeat, registered_at)
SELECT id, name, url, labels, status, max_builds, system_info, last_heartbeat, registered_at FROM agents;

--;;

DROP TABLE agents;

--;;

ALTER TABLE agents_old RENAME TO agents;
