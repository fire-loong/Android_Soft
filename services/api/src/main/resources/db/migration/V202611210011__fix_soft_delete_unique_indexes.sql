-- 审查修复（阶段 2）：软删除唯一约束与应用层 @SQLRestriction 对齐，防止删除行占用唯一键。
-- 1) task_steps.position：软删除后位置必须可复用（步骤回收站 30 天可恢复），改为部分唯一索引。
ALTER TABLE task_steps DROP CONSTRAINT IF EXISTS uniq_task_step_position;
CREATE UNIQUE INDEX IF NOT EXISTS uniq_task_step_position_active
    ON task_steps (task_id, position) WHERE deleted_at IS NULL;

-- 2) evidence_assets 同空间同内容去重：替代普通索引为部分唯一索引，防止并发上传插入重复行
--    指向同一 storage_key（任一清理会破坏另一行引用）。
DROP INDEX IF EXISTS idx_evidence_assets_hash;
CREATE UNIQUE INDEX IF NOT EXISTS uniq_evidence_assets_hash_active
    ON evidence_assets (workspace_id, sha256) WHERE deleted_at IS NULL;
