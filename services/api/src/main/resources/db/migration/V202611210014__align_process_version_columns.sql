-- V202611210014__align_process_version_columns.sql
-- 审查发现：process_templates/process_versions 映射 VersionedAggregate，但 V004 未建 version/deleted_at。
-- 补齐以通过 ddl-auto=validate，并保持乐观锁语义（指导书 3.2）。

ALTER TABLE process_templates ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE process_versions ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE process_versions ADD COLUMN IF NOT EXISTS deleted_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_process_versions_template ON process_versions (template_id, version_no DESC)
    WHERE deleted_at IS NULL;
