-- Add auto-increment sequence number to audit_logs for insertion-order tiebreaking.
-- PostgreSQL uses a SERIAL-like sequence for guaranteed insertion order.
ALTER TABLE audit_logs ADD COLUMN seq_num SERIAL;
--;;
CREATE INDEX idx_audit_seq_num ON audit_logs(seq_num);
