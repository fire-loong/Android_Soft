-- m7c：异步 job 的授权上下文、短期 payload，以及 outbox 与持久化事件的一一关联。
ALTER TABLE jobs
    ADD COLUMN workspace_id uuid REFERENCES workspaces(id) ON DELETE CASCADE,
    ADD COLUMN requested_by uuid REFERENCES users(id) ON DELETE CASCADE;

CREATE INDEX idx_jobs_requester ON jobs (requested_by, created_at DESC);
CREATE INDEX idx_jobs_workspace ON jobs (workspace_id, created_at DESC);

-- 原始输入只存在于执行所需 payload 表；jobs 本身仅保存引用与可公开状态。
CREATE TABLE job_payloads (
    job_id      uuid PRIMARY KEY REFERENCES jobs(id) ON DELETE CASCADE,
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE outbox_events
    ADD COLUMN server_event_id bigint REFERENCES server_events(id) ON DELETE CASCADE;
CREATE UNIQUE INDEX uniq_outbox_server_event ON outbox_events (server_event_id)
    WHERE server_event_id IS NOT NULL;
