package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * 事项↔来源 关联（指导书 3.1 TaskSource）。追加写，无更新/软删。
 */
@Entity
@Table(name = "task_sources")
@Getter
@Setter
@NoArgsConstructor
public class TaskSource {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "task_id", columnDefinition = "uuid", nullable = false)
    private UUID taskId;

    @Column(name = "source_item_id", columnDefinition = "uuid", nullable = false)
    private UUID sourceItemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false)
    private TaskSourceRelation relationType;

    private String quote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Derivation derivation = Derivation.USER_CONFIRMED_AI;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
