package com.actionloop.worker.reminder;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的租约锁：保证同一时刻只有一个 worker 执行提醒派发，避免多实例重复发送。
 * 锁带 TTL，worker 崩溃后租约自动过期，其它实例可接管（重启恢复不丢提醒）。
 */
@Component
public class RedisLeaseLock {

    private final StringRedisTemplate redis;

    public RedisLeaseLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String key, String owner, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, ttl));
    }

    /** 仅释放自己持有的锁（get-then-delete 之间的极小竞态由 TTL 兜底）。 */
    public void release(String key, String owner) {
        try {
            String current = redis.opsForValue().get(key);
            if (owner.equals(current)) {
                redis.delete(key);
            }
        } catch (Exception ignored) {
            // 释放失败不阻塞，TTL 会兜底
        }
    }
}
