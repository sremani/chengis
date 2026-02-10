CREATE TABLE IF NOT EXISTS ldap_identities (
    id                  TEXT PRIMARY KEY,
    user_id             TEXT NOT NULL REFERENCES users(id),
    ldap_server         TEXT NOT NULL,
    distinguished_name  TEXT NOT NULL,
    ldap_uid            TEXT NOT NULL,
    email               TEXT,
    display_name        TEXT,
    groups_json         TEXT,
    last_sync_at        TEXT,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at       TEXT,
    UNIQUE(ldap_server, ldap_uid)
);
--;;
CREATE INDEX IF NOT EXISTS idx_ldap_user ON ldap_identities(user_id);
--;;
CREATE INDEX IF NOT EXISTS idx_ldap_lookup ON ldap_identities(ldap_server, ldap_uid);
