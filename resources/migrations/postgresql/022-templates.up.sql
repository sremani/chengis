-- Pipeline templates for reusable pipeline definitions
CREATE TABLE IF NOT EXISTS pipeline_templates (
  id TEXT PRIMARY KEY,
  name TEXT UNIQUE NOT NULL,
  description TEXT,
  format TEXT NOT NULL DEFAULT 'edn',
  content TEXT NOT NULL,
  parameters TEXT,
  version INTEGER NOT NULL DEFAULT 1,
  created_by TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS idx_templates_name ON pipeline_templates(name);
