-- V202611210003__create_sync_jobs_outbox.sql
-- 离线同步幂等、增量事件、异步作业与 Outbox。对应指导书 11.3/11.4/12.2。

CREATE TABLE sync_operations (
    op_id           uuid PRIMARY KEY,
    device_id       uuid NOT NULL,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    aggregate_id    uuid NOT NULL,
    aggregate_type  text NOT NULL,
    base_version    integer NOT NULL DEFAULT 0,
    operation_type  text NOT NULL,
    payload         jsonb NOT NULL,
    state           text NOT NULL DEFAULT 'PENDING'
                    CHECK (state IN ('PENDING','SYNCED','CONFLICT','FAILED','DEAD')),
    server_version  integer,
    error_code      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_sync_op_device_state ON sync_operations (device_id, state);

CREATE TABLE idempotency_keys (
    key             text NOT NULL,
    actor_id        uuid NOT NULL,
    request_hash    text NOT NULL,
    response        jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    PRIMARY KEY (actor_id, key)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);

-- 增量同步核心：(workspace_id, seq) 唯一单调
CREATE TABLE server_events (
    id              bigserial PRIMARY KEY,
    workspace_id    uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    seq             bigint NOT NULL,
    aggregate_id    uuid NOT NULL,
    aggregate_type  text NOT NULL,
    event_type      text NOT NULL,
    version         integer,
    payload_minimized jsonb NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_server_events_ws_seq UNIQUE (workspace_id, seq)
);
CREATE INDEX idx_server_events_aggregate ON server_events (workspace_id, aggregate_id, seq);

-- 异步作业：租约抢占 FOR UPDATE SKIP LOCKED（指导书 11.3）
CREATE TABLE jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type            text NOT NULL,
    payload_ref     text NOT NULL,
    state           text NOT NULL DEFAULT 'PENDING'
                    CHECK (state IN ('PENDING','RUNNING','SUCCEEDED','DEAD_LETTER')),
    available_at    timestamptz NOT NULL DEFAULT now(),
    lease_owner     text,
    lease_until     timestamptz,
    attempts        integer NOT NULL DEFAULT 0,
    max_attempts    integer NOT NULL DEFAULT 5,
    last_error_code text,
    last_error_at   timestamptz,
    idempotency_key text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_dispatch ON jobs (state, available_at);
CREATE UNIQUE INDEX uniq_jobs_idempotency ON jobs (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Outbox：业务写与事件发布在同一事务，提交后 relay 发布（指导书 11.3）
CREATE TABLE outbox_events (
    id              bigserial PRIMARY KEY,
    aggregate_id    uuid NOT NULL,
    aggregate_type  text NOT NULL,
    event_type      text NOT NULL,
    workspace_id    uuid,
    payload         jsonb NOT NULL,
    published_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at) WHERE published_at IS NULL;
