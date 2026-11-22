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

/** 异步数据导出（指导书 13.2 / 11.7）：生成私有对象，短期签名下载。 */
@Entity
@Table(name = "exports")
@Getter
@Setter
@NoArgsConstructor
public class ExportJob {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(name = "requested_by", columnDefinition = "uuid", nullable = false)
    private UUID requestedBy;

    @Column(name = "export_type", nullable = false)
    private String exportType = "WORKSPACE_JSON";

    @Column(nullable = false)
    private String state = "PENDING";

    @Column(name = "storage_key")
    private String storageKey;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_code")
    private String errorCode;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "expires_at")
    private Instant expiresAt;

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
