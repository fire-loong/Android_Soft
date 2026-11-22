-- V202611210001__create_identity_and_workspace.sql
-- 身份、设备、空间、成员与审计。对应指导书 3.1/12.2。
-- 所有可修改聚合含 version/created_at/updated_at/created_by/deleted_at（3.2）。

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           text NOT NULL,
    username        text NOT NULL,
    password_hash   text NOT NULL,
    display_name    text,
    status          text NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','LOCKED','DELETED')),
    timezone        text NOT NULL DEFAULT 'Asia/Shanghai',
    locale          text NOT NULL DEFAULT 'zh-CN',
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    deleted_at      timestamptz
);
CREATE UNIQUE INDEX uniq_users_email_live ON users (lower(email)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uniq_users_username_live ON users (lower(username)) WHERE deleted_at IS NULL;

CREATE TABLE devices (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform        text NOT NULL CHECK (platform IN ('ANDROID','WEB','WINDOWS')),
    device_name     text NOT NULL,
    public_key      text,
    refresh_family  text,
    last_seen_at    timestamptz,
    status          text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user ON devices (user_id, id);

CREATE TABLE workspaces (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type            text NOT NULL CHECK (type IN ('PERSONAL','TEAM')),
    owner_id        uuid NOT NULL REFERENCES users(id),
    name            text NOT NULL,
    retention_policy jsonb,
    version         integer NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    deleted_at      timestamptz
);
-- 个人空间每人仅一个（指导书 3.1 Workspace 不变量）
CREATE UNIQUE INDEX uniq_workspace_personal_owner
    ON workspaces (owner_id) WHERE type = 'PERSONAL' AND deleted_at IS NULL;

CREATE TABLE memberships (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            text NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER','HELPER')),
    status          text NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','PENDING','REMOVED')),
    joined_at       timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid,
    deleted_at      timestamptz
);
CREATE UNIQUE INDEX uniq_membership_ws_user ON memberships (workspace_id, user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_memberships_user ON memberships (user_id);

-- 审计：追加写，普通成员不可删除（指导书 11.3/15.4）
CREATE TABLE audit_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid,
    actor_id        uuid,
    action          text NOT NULL,
    object_type     text,
    object_id       text,
    result          text NOT NULL DEFAULT 'SUCCESS',
    request_id      text,
    metadata        jsonb,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_object ON audit_logs (object_type, object_id, created_at);
CREATE INDEX idx_audit_workspace ON audit_logs (workspace_id, created_at);
CREATE INDEX idx_audit_actor ON audit_logs (actor_id, created_at);
