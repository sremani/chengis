-- SQLite does not support DROP COLUMN before 3.35.0; use a no-op for ALTER TABLE
SELECT 1;
--;;
DROP TABLE IF EXISTS deployment_strategies;
