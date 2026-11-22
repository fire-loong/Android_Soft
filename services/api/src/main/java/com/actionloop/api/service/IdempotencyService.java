package com.actionloop.api.service;

import com.actionloop.api.domain.IdempotencyKey;
import com.actionloop.api.repository.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * 写接口幂等服务（指导书 13.3 / 11.4）。同一 actor + key：
 *  - 新键：占位 IN_PROGRESS（response 为空），控制器执行后回填响应。
 *  - response 为空：409 IDEMPOTENCY_IN_PROGRESS。
 *  - response 非空：返回首次缓存响应。
 *  - request_hash 不一致：409 IDEMPOTENCY_MISMATCH。
 *  - 已过期：删除后按新请求处理（TTL 边界外不保证幂等，由客户端使用新 key）。
 *  - 控制器抛异常 / 5xx：释放 key，允许重试。
 */
@Service
public class IdempotencyService {

    public enum Outcome { NEW, CACHED, IN_PROGRESS, MISMATCH }

    public record AcquireResult(Outcome outcome, String cachedResponse) {
    }

    private final IdempotencyKeyRepository repository;
    private final int ttlHours;

    public IdempotencyService(IdempotencyKeyRepository repository,
                              @Value("${app.idempotency.ttl-hours:24}") int ttlHours) {
        this.repository = repository;
        this.ttlHours = ttlHours;
    }

    /**
     * 不加 @Transactional：过期键删除与新键占位必须各自独立提交。
     * 若在同一事务内 delete+insert，Hibernate 会先执行 insert，触发唯一约束冲突；
     * 且并发冲突后事务已不可继续使用。
     */
    public AcquireResult tryAcquire(UUID actorId, String key, String requestHash) {
        Optional<IdempotencyKey> existing = repository.findByActorIdAndKey(actorId, key);
        if (existing.isPresent()) {
            IdempotencyKey k = existing.get();
            if (k.getExpiresAt().isBefore(Instant.now())) {
                repository.delete(k); // 过期：清理后按新请求处理
            } else if (!k.getRequestHash().equals(requestHash)) {
                return new AcquireResult(Outcome.MISMATCH, null);
            } else if (k.getResponse() == null) {
                return new AcquireResult(Outcome.IN_PROGRESS, null);
            } else {
                return new AcquireResult(Outcome.CACHED, k.getResponse());
            }
        }
        IdempotencyKey fresh = new IdempotencyKey();
        fresh.setActorId(actorId);
        fresh.setKey(key);
        fresh.setRequestHash(requestHash);
        fresh.setExpiresAt(Instant.now().plus(ttlHours, ChronoUnit.HOURS));
        try {
            repository.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException race) {
            // 并发同 key：另一个请求已占位
            IdempotencyKey winner = repository.findByActorIdAndKey(actorId, key).orElse(null);
            if (winner != null && winner.getResponse() != null) {
                return new AcquireResult(Outcome.CACHED, winner.getResponse());
            }
            return new AcquireResult(Outcome.IN_PROGRESS, null);
        }
        return new AcquireResult(Outcome.NEW, null);
    }

    @Transactional
    public void complete(UUID actorId, String key, String responseJson) {
        repository.findByActorIdAndKey(actorId, key).ifPresent(k -> {
            k.setResponse(responseJson);
            repository.save(k);
        });
    }

    @Transactional
    public void release(UUID actorId, String key) {
        repository.findByActorIdAndKey(actorId, key).ifPresent(repository::delete);
    }
}
