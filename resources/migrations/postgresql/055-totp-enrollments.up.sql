CREATE TABLE IF NOT EXISTS totp_enrollments (
    id               TEXT PRIMARY KEY,
    user_id          TEXT NOT NULL REFERENCES users(id),
    secret_encrypted TEXT NOT NULL,
    algorithm        TEXT NOT NULL DEFAULT 'SHA1',
    digits           INTEGER NOT NULL DEFAULT 6,
    period           INTEGER NOT NULL DEFAULT 30,
    verified         INTEGER NOT NULL DEFAULT 0,
    created_at       TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id)
);
--;;
CREATE INDEX IF NOT EXISTS idx_totp_user ON totp_enrollments(user_id);
--;;
CREATE TABLE IF NOT EXISTS totp_recovery_codes (
    id          TEXT PRIMARY KEY,
    user_id     TEXT NOT NULL REFERENCES users(id),
    code_hash   TEXT NOT NULL,
    used        INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
--;;
CREATE INDEX IF NOT EXISTS idx_totp_recovery_user ON totp_recovery_codes(user_id);
