-- V202611210004__create_process_and_assist.sql
-- 流程模板/版本/步骤/来源，以及远程协助会话与事件。对应指导书 3.1/6/8/12.2。

CREATE TABLE process_templates (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_workspace_id      uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    title                   text NOT NULL,
    region_code             text,
    scope_json              jsonb,
    current_version_id      uuid,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    deleted_at              timestamptz
);
CREATE INDEX idx_process_templates_title ON process_templates USING gin (title gin_trgm_ops) WHERE deleted_at IS NULL;

CREATE TABLE process_versions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id             uuid NOT NULL REFERENCES process_templates(id) ON DELETE CASCADE,
    version_no              integer NOT NULL,
    review_state            text NOT NULL DEFAULT 'DRAFT'
                            CHECK (review_state IN ('DRAFT','IN_REVIEW','VERIFIED','PERSONAL_UNVERIFIED','EXPIRED')),
    source_snapshot_hash    text,
    valid_from              timestamptz,
    valid_to                timestamptz,
    published_by            uuid REFERENCES users(id),
    published_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    created_by              uuid,
    CONSTRAINT uniq_process_version_no UNIQUE (template_id, version_no)
);
ALTER TABLE process_templates ADD CONSTRAINT fk_template_current_version
    FOREIGN KEY (current_version_id) REFERENCES process_versions(id) ON DELETE SET NULL;

CREATE TABLE process_steps (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id              uuid NOT NULL REFERENCES process_versions(id) ON DELETE CASCADE,
    parent_step_id          uuid REFERENCES process_steps(id) ON DELETE SET NULL,
    position                integer NOT NULL,
    instruction             text NOT NULL,
    condition_json          jsonb,
    required                boolean NOT NULL DEFAULT true,
    official_entry_url      text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_process_step_position UNIQUE (version_id, position)
);

-- 事实性步骤发布时至少一条有效引用（指导书 6.2，由服务层校验，DB 保留关系）
CREATE TABLE process_step_sources (
    process_step_id         uuid NOT NULL REFERENCES process_steps(id) ON DELETE CASCADE,
    evidence_ref_id         uuid NOT NULL REFERENCES evidence_refs(id) ON DELETE CASCADE,
    claim_type              text NOT NULL CHECK (claim_type IN ('STEP','MATERIAL','CONDITION','FEE','CHANNEL','TIME','OTHER')),
    PRIMARY KEY (process_step_id, evidence_ref_id, claim_type)
);

-- 协助：单次授权、默认无媒体留存（指导书 8.7/15）
CREATE TABLE assist_sessions (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id            uuid NOT NULL REFERENCES users(id),
    helper_id               uuid REFERENCES users(id),
    task_step_id            uuid REFERENCES task_steps(id) ON DELETE SET NULL,
    mode                    text NOT NULL CHECK (mode IN ('LOCAL_GUIDANCE','REMOTE_ASSIST')),
    state                   text NOT NULL DEFAULT 'IDLE'
                            CHECK (state IN ('IDLE','CONSENT_PENDING','ACTIVE','PAUSED','COMPLETED','STOPPED','EXPIRED')),
    scopes_json             jsonb NOT NULL DEFAULT '[]'::jsonb,  -- 不含点击能力
    expires_at              timestamptz NOT NULL,
    stopped_reason          text,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_assist_sessions_state_expiry ON assist_sessions (state, expires_at);

-- 不保存原始屏幕帧；仅最小化控制与标注事件（指导书 8.7/13.5）
CREATE TABLE assist_events (
    id                      bigserial PRIMARY KEY,
    session_id              uuid NOT NULL REFERENCES assist_sessions(id) ON DELETE CASCADE,
    seq                     bigint NOT NULL,
    event_type              text NOT NULL CHECK (event_type IN
                            ('OFFER','ANSWER','ICE_CANDIDATE','HIGHLIGHT','TEXT_HINT','PAUSE','RESUME_REQUEST','STOP','HEARTBEAT','QUALITY')),
    payload_minimized       jsonb NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uniq_assist_event_seq UNIQUE (session_id, seq)
);
