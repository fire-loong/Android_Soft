-- V202611250022__account_deletion.sql
-- 账户注销冷却（指导书 5.x / 13.2 / 15.3 / 15.5）：
-- 请求注销时写入 requested_at 与 scheduled_at，冷却期内可取消；到期后由 worker 清除数据并写墓碑。
-- 冷却期内账户保持 ACTIVE（用户仍可登录取消），到期清除时置为 DELETED 并匿名化。

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS deletion_requested_at timestamptz,
    ADD COLUMN IF NOT EXISTS deletion_scheduled_at timestamptz;

-- 到期扫描：仅索引有注销计划的账户
CREATE INDEX IF NOT EXISTS idx_users_deletion_due
    ON users (deletion_scheduled_at)
    WHERE deletion_scheduled_at IS NOT NULL AND deleted_at IS NULL;
