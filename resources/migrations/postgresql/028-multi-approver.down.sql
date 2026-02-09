-- Rollback multi-approver support
DROP TABLE IF EXISTS approval_responses;
--;;
ALTER TABLE approval_gates DROP COLUMN IF EXISTS approver_group;
--;;
ALTER TABLE approval_gates DROP COLUMN IF EXISTS min_approvals;
