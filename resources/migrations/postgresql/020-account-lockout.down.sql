-- Reverse account lockout: drop login_attempts table and added columns.
DROP INDEX IF EXISTS idx_login_attempts_user;
DROP TABLE IF EXISTS login_attempts;
--;;
ALTER TABLE users DROP COLUMN locked_until;
--;;
ALTER TABLE users DROP COLUMN failed_attempts;
