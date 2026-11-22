package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** 协助事件：只保存最小化控制/标注负载，不保存原始屏幕帧（指导书 8.7）。 */
@Entity
@Table(name = "assist_events")
@Getter
@Setter
@NoArgsConstructor
public class AssistEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", columnDefinition = "uuid", nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private Long seq;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "payload_minimized", columnDefinition = "jsonb", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String payloadMinimized = "{}";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
