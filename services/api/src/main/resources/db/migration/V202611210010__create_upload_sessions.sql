-- V202611210010__create_upload_sessions.sql
-- 证据文件上传会话（指导书 11.6 / 13.2 POST /assets/uploads）。
-- 客户端先声明 sha256/mime/size，服务端建会话并分配临时对象；完成时 HeadObject 校验长度与哈希后生成 EvidenceAsset。
-- 同 workspace 内同 sha256 去重，不重复占用私有对象存储（指导书 11.x 去重不变量）。

CREATE TABLE upload_sessions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid NOT NULL,
    created_by      uuid NOT NULL,
    sha256          text NOT NULL CHECK (length(sha256) = 64),
    mime            text NOT NULL,
    size_bytes      bigint NOT NULL CHECK (size_bytes >= 0),
    file_name       text,
    storage_key     text NOT NULL,
    temp_key        text NOT NULL,
    state           text NOT NULL DEFAULT 'INITIATED'
                    CHECK (state IN ('INITIATED','COMPLETED','FAILED','EXPIRED')),
    asset_id        uuid,
    expires_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    failure_reason  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_upload_sessions_ws ON upload_sessions (workspace_id);
CREATE INDEX idx_upload_sessions_state_expires ON upload_sessions (state, expires_at);
