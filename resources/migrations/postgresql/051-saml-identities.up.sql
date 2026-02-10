CREATE TABLE IF NOT EXISTS saml_identities (
    id              TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL REFERENCES users(id),
    idp_entity_id   TEXT NOT NULL,
    name_id         TEXT NOT NULL,
    name_id_format  TEXT,
    email           TEXT,
    display_name    TEXT,
    attributes_json TEXT,
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at   TEXT,
    UNIQUE(idp_entity_id, name_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_saml_user ON saml_identities(user_id);
--;;
CREATE INDEX IF NOT EXISTS idx_saml_lookup ON saml_identities(idp_entity_id, name_id);
