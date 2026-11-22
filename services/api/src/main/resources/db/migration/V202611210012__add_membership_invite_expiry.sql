-- #19 邀请有效期（指导书 15.3）：PENDING 邀请必须有过期时间，过期后接受/拒绝均返回 INVITE_EXPIRED。
ALTER TABLE memberships ADD COLUMN IF NOT EXISTS expires_at timestamptz;

-- 回填存量 PENDING 邀请：自创建起 7 天有效（与默认 app.membership.invite-ttl-days 一致）。
UPDATE memberships
SET expires_at = created_at + interval '7 days'
WHERE status = 'PENDING' AND expires_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_memberships_pending_expires
    ON memberships (user_id, expires_at)
    WHERE status = 'PENDING' AND deleted_at IS NULL;
