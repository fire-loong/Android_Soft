package com.actionloop.api.security;

import java.util.UUID;

/**
 * 已认证主体：从 access token 解析，避免每次请求查用户表。
 * deviceId 用于注销当前设备与刷新令牌轮换。
 */
public record UserPrincipal(
        UUID userId,
        UUID deviceId,
        String email
) {
}
