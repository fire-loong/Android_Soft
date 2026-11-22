-- V202611210002__create_task_domain.sql
-- 来源、证据、事项、步骤、材料、提醒、完成、评论。对应指导书 3.1/12.2。

CREATE TABLE source_items (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_type         text NOT NULL CHECK (source_type IN ('NOTIFICATION','SHARE','SCREENSHOT','FILE','LINK','USER_STATEMENT','OFFICIAL')),
    source_app          text,
    source_key          text,
    received_at         timestamptz NOT NULL DEFAULT now(),
    content_ciphertext  text,
    content_preview     text,
    content_hash        text,
    privacy_level       text NOT NULL DEFAULT 'S2' CHECK (privacy_level IN ('S0','S1','S2','S3')),
    source_state        text NOT NULL DEFAULT 'USER_PROVIDED'
                        CHECK (source_state IN ('VERIFIED_OFFICIAL','USER_PROVIDED','COMMUNITY_UNVERIFIED','EXPIRED','FETCH_FAILED')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz
);
CREATE INDEX idx_source_items_ws ON source_items (workspace_id, received_at DESC);
-- 来源指纹去重：相同 (workspace, source_app, source_key, content_hash) 不重复
CREATE UNIQUE INDEX uniq_source_fingerprint ON source_items (workspace_id, coalesce(source_app,''), coalesce(source_key,''), content_hash)
    WHERE deleted_at IS NULL;

CREATE TABLE evidence_assets (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    sha256              text NOT NULL,
    mime                text NOT NULL,
    size_bytes          bigint NOT NULL,
    storage_key         text NOT NULL,            -- 私有桶 key，不暴露真实文件名
    page_count          integer,
    scan_state          text NOT NULL DEFAULT 'PENDING'
                        CHECK (scan_state IN ('PENDING','INDEXED','OCR_FAILED','PARSE_FAILED')),
    retention_class     text NOT NULL DEFAULT 'USER_CONTROLLED',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz
);
CREATE INDEX idx_evidence_assets_hash ON evidence_assets (workspace_id, sha256) WHERE deleted_at IS NULL;

CREATE TABLE evidence_refs (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    source_item_id      uuid REFERENCES source_items(id) ON DELETE SET NULL,
    asset_id            uuid REFERENCES evidence_assets(id) ON DELETE SET NULL,
    page_no             integer,
    bbox_json           jsonb,
    char_start          integer,
    char_end            integer,
    quote_text          text NOT NULL,
    content_hash        text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz,
    CONSTRAINT chk_evidence_ref_source CHECK (source_item_id IS NOT NULL OR asset_id IS NOT NULL)
);
CREATE INDEX idx_evidence_refs_source ON evidence_refs (source_item_id);
CREATE INDEX idx_evidence_refs_asset ON evidence_refs (asset_id);

CREATE TABLE tasks (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    title               text NOT NULL,
    status              text NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','PENDING_CONFIRMATION','ACTIVE','IN_PROGRESS','BLOCKED','PENDING_REVIEW','COMPLETED','CANCELLED','DISMISSED')),
    owner_id            uuid NOT NULL REFERENCES users(id),
    assignee_id         uuid REFERENCES users(id),
    due_at              timestamptz,
    due_date            date,
    due_timezone        text,
    time_precision      text CHECK (time_precision IN ('DATE','MINUTE','NONE')),
    priority            integer NOT NULL DEFAULT 0,
    description         text,
    derivation          text NOT NULL DEFAULT 'USER'
                        CHECK (derivation IN ('AI','RULE','USER','OFFICIAL','USER_CONFIRMED_AI')),
    confidence          numeric(5,4),
    model_version       text,
    prompt_version      text,
    version             integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz
);
CREATE INDEX idx_tasks_ws_status_due ON tasks (workspace_id, status, due_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_tasks_assignee ON tasks (assignee_id, status) WHERE deleted_at IS NULL;

CREATE TABLE task_sources (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    source_item_id      uuid NOT NULL REFERENCES source_items(id) ON DELETE CASCADE,
    relation_type       text NOT NULL CHECK (relation_type IN ('ORIGIN','DUE_DATE','MATERIAL','LOCATION','ACTION','OTHER')),
    quote               text,
    derivation          text NOT NULL DEFAULT 'USER_CONFIRMED_AI'
                        CHECK (derivation IN ('AI','RULE','USER','OFFICIAL','USER_CONFIRMED_AI')),
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_task_source UNIQUE (task_id, source_item_id, relation_type)
);

CREATE TABLE reminders (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    trigger_at          timestamptz NOT NULL,
    channel             text NOT NULL DEFAULT 'IN_APP'
                        CHECK (channel IN ('IN_APP','NOTIFICATION','HIGH_PRIORITY')),
    state               text NOT NULL DEFAULT 'SCHEDULED'
                        CHECK (state IN ('SCHEDULED','SENT','CANCELLED','FAILED')),
    dedupe_key          text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
-- 同一 dedupeKey 仅一个有效计划（指导书 4.4）
CREATE UNIQUE INDEX uniq_reminder_dedupe_active ON reminders (task_id, dedupe_key) WHERE state = 'SCHEDULED';
CREATE INDEX idx_reminders_due ON reminders (state, trigger_at);

CREATE TABLE task_steps (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    process_step_id     uuid,
    title               text NOT NULL,
    instruction         text,
    position            integer NOT NULL,
    assignee_id         uuid REFERENCES users(id),
    status              text NOT NULL DEFAULT 'TODO'
                        CHECK (status IN ('TODO','IN_PROGRESS','BLOCKED','PENDING_REVIEW','DONE','CANCELLED')),
    completion_rule_json jsonb NOT NULL DEFAULT '{"type":"SELF_CONFIRM"}'::jsonb,
    version             integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz,
    CONSTRAINT uniq_task_step_position UNIQUE (task_id, position)
);

CREATE TABLE materials (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             uuid NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    name                text NOT NULL,
    required_condition_json jsonb,
    format_rule_json    jsonb,
    state               text NOT NULL DEFAULT 'MISSING'
                        CHECK (state IN ('MISSING','PROVIDED','VERIFIED','NOT_REQUIRED')),
    evidence_ref_id     uuid REFERENCES evidence_refs(id) ON DELETE SET NULL,
    asset_id            uuid REFERENCES evidence_assets(id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid,
    deleted_at          timestamptz
);

CREATE TABLE completions (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    task_step_id        uuid NOT NULL REFERENCES task_steps(id) ON DELETE CASCADE,
    submitter_id        uuid NOT NULL REFERENCES users(id),
    statement           text,
    review_state        text NOT NULL DEFAULT 'PENDING'
                        CHECK (review_state IN ('PENDING','APPROVED','REJECTED')),
    reviewer_id         uuid REFERENCES users(id),
    review_reason       text,
    submitted_at        timestamptz NOT NULL DEFAULT now(),
    reviewed_at         timestamptz
);

CREATE TABLE completion_assets (
    completion_id       uuid NOT NULL REFERENCES completions(id) ON DELETE CASCADE,
    asset_id            uuid NOT NULL REFERENCES evidence_assets(id) ON DELETE CASCADE,
    PRIMARY KEY (completion_id, asset_id)
);

CREATE TABLE comments (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    task_id             uuid REFERENCES tasks(id) ON DELETE CASCADE,
    task_step_id        uuid REFERENCES task_steps(id) ON DELETE SET NULL,
    author_id           uuid NOT NULL REFERENCES users(id),
    body                text NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz
);
CREATE INDEX idx_comments_task ON comments (task_id, created_at);
