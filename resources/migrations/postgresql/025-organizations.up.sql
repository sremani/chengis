-- Organizations and membership tables for multi-tenancy
CREATE TABLE IF NOT EXISTS organizations (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    slug        TEXT UNIQUE NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--;;

CREATE INDEX idx_organizations_slug ON organizations(slug);

--;;

CREATE TABLE IF NOT EXISTS org_memberships (
    id          TEXT PRIMARY KEY,
    org_id      TEXT NOT NULL REFERENCES organizations(id),
    user_id     TEXT NOT NULL REFERENCES users(id),
    role        TEXT NOT NULL DEFAULT 'viewer',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(org_id, user_id)
);

--;;

CREATE INDEX idx_org_memberships_user ON org_memberships(user_id);

--;;

CREATE INDEX idx_org_memberships_org ON org_memberships(org_id);

--;;

-- Seed default organization for backward compatibility
INSERT INTO organizations (id, name, slug, description, created_at, updated_at)
VALUES ('default-org', 'Default', 'default', 'Auto-created default organization',
        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT DO NOTHING;
