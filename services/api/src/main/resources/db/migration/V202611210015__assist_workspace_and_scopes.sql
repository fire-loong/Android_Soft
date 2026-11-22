-- V202611210015__assist_workspace_and_scopes.sql
-- 修复审查发现：协助会话缺少空间归属（跨空间接受/读信令/取 TURN 凭证）、
-- 信令事件类型与服务端白名单不一致导致 DB CHECK 500、流程版本缺少复核人落库。

ALTER TABLE assist_sessions ADD COLUMN workspace_id uuid REFERENCES workspaces(id) ON DELETE CASCADE;

-- 能从关联步骤回溯空间的会话自动回填；其余历史会话保持 NULL（接受时会被拒绝，30 分钟内自然过期）
UPDATE assist_sessions s
SET workspace_id = t.workspace_id
FROM task_steps ts
JOIN tasks t ON t.id = ts.task_id
WHERE s.task_step_id = ts.id
  AND s.workspace_id IS NULL;

CREATE INDEX idx_assist_sessions_workspace ON assist_sessions (workspace_id, created_at DESC);

-- 服务端白名单（AssistScopes）包含 HIGHLIGHT_CLEAR/RESUME/POINTER/META，DB 约束必须同集合
ALTER TABLE assist_events DROP CONSTRAINT assist_events_event_type_check;
ALTER TABLE assist_events ADD CONSTRAINT assist_events_event_type_check CHECK (event_type IN
    ('OFFER','ANSWER','ICE_CANDIDATE','HIGHLIGHT','HIGHLIGHT_CLEAR','TEXT_HINT','PAUSE','RESUME',
     'RESUME_REQUEST','STOP','HEARTBEAT','QUALITY','POINTER','META'));

-- 流程复核人必须落库，避免仅凭请求体 UUID 声称“他人复核”
ALTER TABLE process_versions ADD COLUMN reviewer_id uuid REFERENCES users(id);
