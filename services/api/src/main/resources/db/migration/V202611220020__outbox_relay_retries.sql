-- V202611220020__outbox_relay_retries.sql
-- outbox relay 租约、重试与毒消息跳过：防止单条失败永久阻塞后续事件投递。

ALTER TABLE outbox_events
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN lease_owner text,
    ADD COLUMN lease_until timestamptz,
    ADD COLUMN next_retry_at timestamptz,
    ADD COLUMN last_error_code text,
    ADD COLUMN last_error_at timestamptz,
    ADD COLUMN skipped_at timestamptz;

CREATE INDEX idx_outbox_pending ON outbox_events (id)
    WHERE published_at IS NULL AND skipped_at IS NULL;
