-- SQLite <3.35 cannot DROP COLUMN; these columns are harmless to leave
-- For clean environments, recreate table without delta columns
SELECT 1;
