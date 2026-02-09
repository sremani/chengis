-- Add SHA-256 checksum column to build_artifacts for supply-chain integrity
ALTER TABLE build_artifacts ADD COLUMN sha256_hash TEXT;
