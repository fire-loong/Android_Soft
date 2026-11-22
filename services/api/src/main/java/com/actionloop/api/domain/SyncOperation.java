package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 离线同步操作（指导书 11.4）。op_id 由客户端生成，base_version 做并发基线，
 * server_version 为服务端应用后的版本；冲突返回 CONFLICT。
 */
@Entity
@Table(name = "sync_operations")
@Getter
@Setter
@NoArgsConstructor
public class SyncOperation {

    @Id
    @Column(name = "op_id", columnDefinition = "uuid")
    private UUID opId;

    @Column(name = "device_id", columnDefinition = "uuid", nullable = false)
    private UUID deviceId;

    @Column(name = "user_id", columnDefinition = "uuid", nullable = false)
    private UUID userId;

    @Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false)
    private UUID aggregateId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "base_version", nullable = false)
    private Integer baseVersion = 0;

    @Column(name = "operation_type", nullable = false)
    private String operationType;

    @Column(columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncState state = SyncState.PENDING;

    @Column(name = "server_version")
    private Integer serverVersion;

    @Column(name = "error_code")
    private String errorCode;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
