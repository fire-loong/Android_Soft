-- V202611210013__create_candidates_and_exports.sql
-- 候选卡片（AI/规则生成，未确认前不产生提醒）、数据导出、来源链接字段。对应指导书 4.3/13.2/11.7。

ALTER TABLE source_items ADD COLUMN IF NOT EXISTS link_url text;
ALTER TABLE source_items ADD COLUMN IF NOT EXISTS source_title text;
ALTER TABLE source_items ADD COLUMN IF NOT EXISTS last_checked_at timestamptz;

CREATE TABLE candidate_tasks (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_item_id          uuid NOT NULL REFERENCES source_items(id) ON DELETE CASCADE,
    title                   text NOT NULL,
    summary                 text,
    category                text,
    due_at                  timestamptz,
    due_date                date,
    due_timezone            text,
    time_precision          text CHECK (time_precision IN ('DATE','MINUTE','NONE')),
    location                text,
    materials_json          jsonb NOT NULL DEFAULT '[]'::jsonb,
    next_action             text,
    confidence              numeric(5,4),
    field_confidence_json   jsonb NOT NULL DEFAULT '{}'::jsonb,
    reasons_json            jsonb NOT NULL DEFAULT '[]'::jsonb,
    derivation              text NOT NULL DEFAULT 'RULE'
                            CHECK (derivation IN ('AI','RULE','USER','OFFICIAL','USER_CONFIRMED_AI')),
    model_version           text,
    prompt_version          text,
    status                  text NOT NULL DEFAULT 'PENDING'
                            CHECK (status IN ('PENDING','CONFIRMED','DISMISSED')),
    confirmed_task_id       uuid REFERENCES tasks(id) ON DELETE SET NULL,
    dismissed_reason        text,
    version                 integer NOT NULL DEFAULT 1,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    deleted_at              timestamptz
);
CREATE INDEX idx_candidate_ws_status ON candidate_tasks (workspace_id, status, created_at DESC)
    WHERE deleted_at IS NULL;
-- 同一来源同一标题只保留一条待确认候选，避免重复通知刷出多张卡片
CREATE UNIQUE INDEX uniq_candidate_pending_title ON candidate_tasks (workspace_id, source_item_id, lower(title))
    WHERE status = 'PENDING' AND deleted_at IS NULL;

CREATE TABLE exports (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id            uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    requested_by            uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    export_type             text NOT NULL DEFAULT 'WORKSPACE_JSON',
    state                   text NOT NULL DEFAULT 'PENDING'
                            CHECK (state IN ('PENDING','RUNNING','READY','FAILED','EXPIRED')),
    storage_key             text,
    size_bytes              bigint,
    error_code              text,
    attempts                integer NOT NULL DEFAULT 0,
    expires_at              timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_exports_requester ON exports (requested_by, created_at DESC);
