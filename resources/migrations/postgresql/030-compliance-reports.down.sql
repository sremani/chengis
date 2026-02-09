-- Reverse compliance reports migration (PostgreSQL).

DROP TABLE IF EXISTS compliance_report_runs;
--;;
DROP TABLE IF EXISTS compliance_reports;
--;;
ALTER TABLE audit_logs DROP COLUMN IF EXISTS entry_hash;
--;;
ALTER TABLE audit_logs DROP COLUMN IF EXISTS prev_hash;
