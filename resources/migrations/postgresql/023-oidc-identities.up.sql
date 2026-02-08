-- OIDC identity mapping: links external IdP subject IDs to Chengis users
CREATE TABLE IF NOT EXISTS oidc_identities (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  issuer TEXT NOT NULL,
  subject TEXT NOT NULL,
  email TEXT,
  display_name TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at TIMESTAMPTZ,
  UNIQUE(issuer, subject)
);
CREATE INDEX idx_oidc_identities_user ON oidc_identities(user_id);
CREATE INDEX idx_oidc_identities_issuer_subject ON oidc_identities(issuer, subject);
