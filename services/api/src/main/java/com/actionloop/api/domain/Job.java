package com.actionloop.api.domain;

import com.actionloop.shared.domain.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 异步作业（指导书 11.3）：租约 + FOR UPDATE SKIP LOCKED 抢占，禁止微服务化。 */
@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(name = "payload_ref", nullable = false)
    private String payloadRef;

    @Column(name = "workspace_id", columnDefinition = "uuid")
    private UUID workspaceId;

    @Column(name = "requested_by", columnDefinition = "uuid")
    private UUID requestedBy;

    @Column(nullable = false)
    private String state = "PENDING";

    @Column(name = "available_at", nullable = false)
    private Instant availableAt = Instant.now();

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 5;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /** 分段任务进度（如长截图 OCR 的第 x/y 段）；单段任务保持 null。 */
    @Column(name = "progress_current")
    private Integer progressCurrent;

    @Column(name = "progress_total")
    private Integer progressTotal;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

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
