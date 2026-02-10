-- Add SCM-related columns to jobs table
ALTER TABLE jobs ADD COLUMN branch_overrides TEXT;

--;;

ALTER TABLE jobs ADD COLUMN path_filters TEXT;

--;;

ALTER TABLE jobs ADD COLUMN auto_merge_enabled BOOLEAN NOT NULL DEFAULT FALSE;

--;;

ALTER TABLE jobs ADD COLUMN default_branch TEXT;

--;;

-- Webhook replay: store raw payload for re-delivery
ALTER TABLE webhook_events ADD COLUMN payload_body TEXT;

--;;

ALTER TABLE webhook_events ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0;

--;;

ALTER TABLE webhook_events ADD COLUMN last_replayed_at TIMESTAMP WITH TIME ZONE;
