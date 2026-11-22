package com.actionloop.api.web.dto;

import java.time.Instant;
import java.util.List;

/** 短期 TURN 凭证（指导书 15.5）：TTL 默认 10 分钟，服务端 HMAC，不持久化。 */
public record AssistTurnCredentials(
        List<String> urls,
        String username,
        String credential,
        long ttlSeconds,
        Instant expiresAt
) {
}
