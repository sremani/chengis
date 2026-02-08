-- Add revoked_at to api_tokens for soft-delete revocation
ALTER TABLE api_tokens ADD COLUMN revoked_at TIMESTAMPTZ;
