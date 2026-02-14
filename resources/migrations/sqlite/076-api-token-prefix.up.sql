-- Add token_prefix column for O(1) token lookup instead of O(n) bcrypt scan.
-- The prefix is the first 12 characters of the plaintext token, stored in the clear.
-- It narrows the bcrypt search to at most 1 row.
ALTER TABLE api_tokens ADD COLUMN token_prefix TEXT;

-- Index for fast prefix-based lookup
CREATE INDEX IF NOT EXISTS idx_api_tokens_prefix ON api_tokens(token_prefix) WHERE revoked_at IS NULL;
