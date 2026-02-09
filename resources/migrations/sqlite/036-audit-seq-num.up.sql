-- Add auto-increment sequence number to audit_logs for insertion-order tiebreaking.
-- SQLite rowid is implicit but not portable; this explicit column works cross-database.
ALTER TABLE audit_logs ADD COLUMN seq_num INTEGER;
--;;
-- Backfill existing rows with rowid-based ordering (SQLite-specific for migration only)
UPDATE audit_logs SET seq_num = rowid;
--;;
-- Create index for efficient ordering
CREATE INDEX idx_audit_seq_num ON audit_logs(seq_num);
