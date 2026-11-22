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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 协助会话（指导书 8.x / 13.5）。只交换最小化信令与标注，不保存屏幕帧，不授予任何点击/输入能力。
 */
@Entity
@Table(name = "assist_sessions")
@Getter
@Setter
@NoArgsConstructor
public class AssistSession {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "requester_id", columnDefinition = "uuid", nullable = false)
    private UUID requesterId;

    /** 会话所属空间：接受、读事件、取 TURN 凭证均必须校验该空间成员身份。 */
    @Column(name = "workspace_id", columnDefinition = "uuid")
    private UUID workspaceId;

    @Column(name = "helper_id", columnDefinition = "uuid")
    private UUID helperId;

    @Column(name = "task_step_id", columnDefinition = "uuid")
    private UUID taskStepId;

    @Column(nullable = false)
    private String mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssistSessionState state = AssistSessionState.IDLE;

    @Column(name = "scopes_json", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String scopesJson = "[]";

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "stopped_reason")
    private String stoppedReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PrePersist
    void assignId() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }
}
