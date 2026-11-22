package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 可修改聚合的通用字段（指导书 3.2）：UUIDv7 主键、时间戳、创建者、软删除。
 * 软删除过滤：各具体实体类必须声明 {@code @SQLRestriction("deleted_at IS NULL")}。
 * 注意 Hibernate 6 不会从 {@code @MappedSuperclass} 继承该注解，因此不能只写在基类上；
 * 恢复/审计走原生查询绕过该过滤。
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseAggregate {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", columnDefinition = "uuid")
    private UUID createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
