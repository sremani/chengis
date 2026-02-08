-- Reverse account lockout: drop login_attempts table.
-- SQLite cannot DROP COLUMN directly; failed_attempts and locked_until
-- will remain as harmless unused columns. The table recreation pattern
-- is too risky for a down migration.

DROP INDEX IF EXISTS idx_login_attempts_user;
DROP TABLE IF EXISTS login_attempts;
