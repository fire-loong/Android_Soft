package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 写接口幂等键（指导书 13.3/11.4）。同一 actor 相同 key：
 *  - 进行中：返回 409 IDEMPOTENCY_IN_PROGRESS
 *  - 已完成：返回首次响应（response jsonb）
 *  - request_hash 不一致：返回 409 IDEMPOTENCY_MISMATCH
 * 主键 (actor_id, key)。
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@IdClass(IdempotencyKeyId.class)
public class IdempotencyKey {

    @Id
    @Column(name = "actor_id", columnDefinition = "uuid", nullable = false)
    private UUID actorId;

    @Id
    @Column(nullable = false)
    private String key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String response;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
