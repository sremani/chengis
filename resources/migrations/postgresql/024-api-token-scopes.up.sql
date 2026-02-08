-- API token scopes for least-privilege token access
-- NULL scopes means full user access (backward compatible)
ALTER TABLE api_tokens ADD COLUMN scopes TEXT;
