-- 对齐指导书 3.2：所有可修改聚合含 version（乐观锁）。V1/V2 建表时部分表遗漏 version，补列（幂等）。
-- workspaces / tasks / task_steps 已在初始迁移中含 version，此处不重复。
ALTER TABLE users           ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE memberships     ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE source_items    ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE evidence_assets ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE evidence_refs   ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE materials       ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
ALTER TABLE comments        ADD COLUMN IF NOT EXISTS version integer NOT NULL DEFAULT 1;
