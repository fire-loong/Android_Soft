package com.actionloop.api.security;

import com.actionloop.api.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 按 IP 的固定窗口限流（指导书 15.3）。
 * Redis 不可用时 fail-open（放行并告警），避免认证端点整体不可用；限流能力在 Redis 恢复后自动生效。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final String KEY_PREFIX = "actionloop:rl:";
    private static final org.springframework.data.redis.core.script.RedisScript<Long> INCR_WITH_TTL =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local c = redis.call('INCR', KEYS[1]) "
                            + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                            + "return c",
                    Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public enum AuthEndpoint { REGISTER, LOGIN, REFRESH }

    /** @return true 表示放行，false 表示已超限 */
    public boolean tryAcquire(AuthEndpoint endpoint, String ip) {
        RateLimitProperties.Endpoint cfg = switch (endpoint) {
            case REGISTER -> properties.getRegister();
            case LOGIN -> properties.getLogin();
            case REFRESH -> properties.getRefresh();
        };
        if (cfg.getMaxRequests() <= 0) {
            return true;
        }
        String key = KEY_PREFIX + endpoint.name().toLowerCase() + ":" + ip;
        try {
            // INCR 与 EXPIRE 必须原子执行，否则 EXPIRE 丢失会导致该 IP 被永久限流
            Long count = redis.execute(INCR_WITH_TTL, java.util.List.of(key),
                    String.valueOf(cfg.getWindowSeconds()));
            return count == null || count <= cfg.getMaxRequests();
        } catch (Exception e) {
            // Redis 故障：放行但记录，保证登录/注册可用（指导书可用性优先于限流降级）
            log.warn("rate-limit redis unavailable, fail-open for {} ip={}: {}", endpoint, ip, e.getMessage());
            return true;
        }
    }
}
