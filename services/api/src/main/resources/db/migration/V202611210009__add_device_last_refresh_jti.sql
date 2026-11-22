-- 刷新令牌轮换与重用检测：记录设备当前有效 refresh token 的 jti（指导书 5.x / 13.x）。
-- refresh 时若上送 jti 与本列不一致，视为令牌被重用，立即撤销整个 refresh_family。
ALTER TABLE devices ADD COLUMN IF NOT EXISTS last_refresh_jti text;

-- 按 family 快速定位设备（用于重用检测与设备撤销）。
CREATE INDEX IF NOT EXISTS idx_devices_refresh_family ON devices (refresh_family) WHERE refresh_family IS NOT NULL;
