package com.actionloop.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件上传会话（指导书 11.6）。客户端先声明 sha256/mime/size，服务端分配临时对象；
 * complete 时校验完整性后落 EvidenceAsset。会话有过期时间，过期后临时对象由清理任务删除。
 */
@Entity
@Table(name = "upload_sessions")
@Getter
@Setter
@NoArgsConstructor
public class UploadSession extends TimestampedEntity {

    @Column(name = "workspace_id", columnDefinition = "uuid", nullable = false)
    private UUID workspaceId;

    @Column(name = "created_by", columnDefinition = "uuid", nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private String sha256;

    @Column(nullable = false)
    private String mime;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "temp_key", nullable = false)
    private String tempKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadSessionState state = UploadSessionState.INITIATED;

    @Column(name = "asset_id", columnDefinition = "uuid")
    private UUID assetId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason")
    private String failureReason;
}
