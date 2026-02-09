DROP INDEX IF EXISTS idx_audit_seq_num;
--;;
ALTER TABLE audit_logs DROP COLUMN IF EXISTS seq_num;
